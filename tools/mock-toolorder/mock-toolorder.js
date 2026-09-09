#!/usr/bin/env node
'use strict';

/**
 * COSP 测试用模拟上游 —— 专攻「工具调用与正文的先后顺序」。
 *
 * 起因：某模型把 tool_calls 发在正文之前时，Copilot 直接终止对话且不执行工具，
 * 提示词无法纠正。COSP 侧的清洗已由单测证明无损（工具调用的 id / name / arguments
 * 与 finish_reason 全部原样透传），因此需要一个能<strong>稳定复现该顺序</strong>的上游，
 * 用来观察下游客户端的实际行为。
 *
 * 为何单独一个 mock 而不是往现有三个里加场景：
 *  - 现有 mock 各自的场景清单已经很长，再塞进「顺序」这一维会让模型名清单更难记；
 *  - 场景刻意只有两个且互为对照，多一个都会削弱「唯一变量是顺序」这件事。
 *
 * 两个端点（路径与真实上游一致，使 Base URL 的填法也与真实供应商一致）：
 *  - POST /chat/completions  OpenAI Chat Completions 形态（Copilot 走这条）
 *  - POST /messages          Anthropic Messages 形态（Claude 系客户端走这条）
 *
 * 另提供 /chat 与 /mess 两个短写别名，仅为手打 curl 时少敲几个字；
 * 接入 COSP 时用不到它们。
 *
 * 两个场景（按模型名触发，两个端点同名同义）：
 *  - to-content-first  正文在前、工具调用在后（对照组，已知可正常执行）
 *  - to-tool-first     工具调用在前、正文在后（复现组）
 *
 * 特性：
 *  - 纯 Node 内置 http 模块，零依赖，无需 npm install。
 *  - 两个端点的事件序列在语义上一一对应，便于比对「同一顺序在两种协议下」的差异。
 *  - /mess 同样严格校验 anthropic-version，与 mock-anthropic 保持同一口径。
 *
 * 启动：node mock-toolorder.js（或 npm run mock:toolorder）
 */

const http = require('http');
const path = require('path');
const fs = require('fs');

// ── 可调参数（直接改这里）─────────────────────────────
const PORT = Number(process.env.MOCK_TOOLORDER_PORT || 8084);
/** 帧间隔。调大可在客户端 UI 上肉眼观察到顺序，调小可加快脚本验证。 */
const FRAME_INTERVAL_MS = Number(process.env.MOCK_TOOLORDER_INTERVAL_MS || 120);
const REQUIRED_ANTHROPIC_VERSION = '2023-06-01';
// ────────────────────────────────────────────────────

/** 两个场景共用的正文与工具调用，使唯一变量只有顺序。 */
const CONTENT_PIECES = ['我来看一下', '这个文件的内容。'];
const TOOL_NAME = 'read_file';
const TOOL_ID = 'call_mock_toolorder_1';

/**
 * 工具调用的目标文件 —— 必须是<strong>绝对路径</strong>。
 *
 * <p>由本脚本自身位置反推仓库根（`tools/mock-toolorder/` 往上两级），
 * 换机器、换盘符都不用改代码。`MOCK_TOOLORDER_FILE` 可覆盖成任意文件。
 */
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const TOOL_TARGET_FILE = process.env.MOCK_TOOLORDER_FILE || path.join(REPO_ROOT, 'README.md');

/**
 * 工具参数。三个字段在 `read_file` 的 schema 里<strong>都是 required</strong>：
 * `filePath`（绝对路径）、`startLine`、`endLine`。
 *
 * <p>这里曾经只发 `{"filePath":"README.md"}` —— 缺两个必填字段、路径又是相对的，
 * 于是下游必然以参数校验失败告终。那种失败会把「顺序」这个唯一变量彻底淹没：
 * 看到的报错来自参数，而不是来自我们想观察的流解析行为。
 *
 * <p>教训是 mock 的载荷也要照下游的 schema 查证，不能只求「形态像个工具调用」。
 */
const TOOL_ARGS = {
  filePath: TOOL_TARGET_FILE,
  startLine: 1,
  endLine: 40,
};

const TOOL_ARG_JSON = JSON.stringify(TOOL_ARGS);

/**
 * 参数切成两片，覆盖「参数跨片」这一常见形态。
 *
 * <p>切点取中点，必然落在 `filePath` 的值内部 —— 任何试图按单片解析 JSON 的实现
 * 都会在这里暴露。Windows 路径里的反斜杠由 `JSON.stringify` 负责转义，
 * <strong>不要手写字面量</strong>：手写的 `d:\PublicFolder\...` 会产出非法 JSON。
 */
const TOOL_ARG_PIECES = [
  TOOL_ARG_JSON.slice(0, Math.floor(TOOL_ARG_JSON.length / 2)),
  TOOL_ARG_JSON.slice(Math.floor(TOOL_ARG_JSON.length / 2)),
];

const SCENARIOS = [
  {
    id: 'to-content-first',
    desc: '正文在前、工具调用在后（对照组：已知 Copilot 可正常执行工具）',
    toolFirst: false,
  },
  {
    id: 'to-tool-first',
    desc: '工具调用在前、正文在后（复现组：Copilot 疑似静默终止且不执行工具）',
    toolFirst: true,
  },
];

function log(...args) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}]`, ...args);
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function readJsonBody(req) {
  return new Promise(resolve => {
    let raw = '';
    req.on('data', chunk => { raw += chunk; });
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch {
        resolve({});
      }
    });
    req.on('error', () => resolve({}));
  });
}

/**
 * 提取下游请求里声明的工具名。OpenAI 是 `tools[].function.name`，
 * Anthropic 是 `tools[].name`，两种一起认。
 */
function toolNames(body) {
  const tools = Array.isArray(body.tools) ? body.tools : [];
  return tools
    .map(t => (t && t.function && t.function.name) || (t && t.name))
    .filter(Boolean);
}

/**
 * 记录下游有没有把目标工具放进 tools。
 *
 * <p>没放进去时工具调用必然以「工具未找到」失败，那与「顺序导致的静默终止」
 * 是两种完全不同的现象 —— 日志里要能一眼分开，否则会把前者误判成后者。
 */
function logToolAvailability(body) {
  const names = toolNames(body);
  if (names.length === 0) {
    log(`  ⚠ 下游未声明任何工具，工具调用必然失败，与顺序无关`);
    return;
  }
  const has = names.includes(TOOL_NAME);
  log(`  下游声明 ${names.length} 个工具，${has ? `含 ${TOOL_NAME}` : `不含 ${TOOL_NAME} ⚠ 必然报「工具未找到」`}`);
}

function resolveScenario(model) {
  const name = typeof model === 'string' ? model : '';
  // 允许带 [provider-key] 前缀：COSP 路由用的是前缀形态，透传到上游时通常已剥离，
  // 但手打请求时容易带上，这里一并容忍。
  const bare = name.replace(/^\[[^\]]*\]\s*/, '').trim();
  return SCENARIOS.find(s => s.id === bare) || SCENARIOS[0];
}

function sendJson(res, status, payload) {
  const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
}

function writeSseHead(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
}

// ════════════════════════════════════════════════════
// OpenAI Chat Completions：POST /chat
// ════════════════════════════════════════════════════

function chunkId() {
  return `chatcmpl-mock-to-${Date.now()}`;
}

/** OpenAI chunk 骨架。delta 由调用方给，其余字段固定。 */
function openAiChunk(id, model, delta, finishReason = null) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }],
  };
}

function writeChunk(res, payload) {
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

/**
 * 发送 OpenAI 形态的正文帧序列。
 *
 * <p>首帧带 {@code role: assistant} —— 这是 OpenAI 的惯例，部分客户端靠它判定
 * 助手轮开始。刻意保留，以免「顺序」之外多出一个变量。
 */
async function writeOpenAiContent(res, id, model, includeRole) {
  for (let i = 0; i < CONTENT_PIECES.length; i++) {
    const delta = { content: CONTENT_PIECES[i] };
    if (includeRole && i === 0) {
      delta.role = 'assistant';
    }
    writeChunk(res, openAiChunk(id, model, delta));
    await sleep(FRAME_INTERVAL_MS);
  }
}

/**
 * 发送 OpenAI 形态的工具调用帧序列。
 *
 * <p>首帧给全 {@code id} / {@code type} / {@code function.name}，后续帧只带
 * {@code arguments} 增量 —— 这是 OpenAI 协议的标准分片方式，也是 Copilot 的
 * {@code StreamingToolCall} 期待的形态（它只在 {@code toolCall.id} 为 truthy 时赋值）。
 */
async function writeOpenAiToolCall(res, id, model, includeRole) {
  const firstDelta = {
    tool_calls: [{
      index: 0,
      id: TOOL_ID,
      type: 'function',
      function: { name: TOOL_NAME, arguments: TOOL_ARG_PIECES[0] },
    }],
  };
  if (includeRole) {
    firstDelta.role = 'assistant';
  }
  writeChunk(res, openAiChunk(id, model, firstDelta));
  await sleep(FRAME_INTERVAL_MS);

  for (let i = 1; i < TOOL_ARG_PIECES.length; i++) {
    writeChunk(res, openAiChunk(id, model, {
      tool_calls: [{ index: 0, function: { arguments: TOOL_ARG_PIECES[i] } }],
    }));
    await sleep(FRAME_INTERVAL_MS);
  }
}

async function handleChat(req, res, body) {
  const scenario = resolveScenario(body.model);
  const model = typeof body.model === 'string' && body.model ? body.model : scenario.id;
  const stream = body.stream !== false;

  log(`→ /chat/completions  model=${model}  scenario=${scenario.id}  stream=${stream}  toolFirst=${scenario.toolFirst}`);
  logToolAvailability(body);

  if (!stream) {
    // 非流式没有「顺序」可言：正文与 tool_calls 同在一个 message 对象里。
    // 仍然提供，用于确认下游在非流式下能否正常执行工具（排除顺序之外的因素）。
    return sendJson(res, 200, {
      id: chunkId(),
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model,
      choices: [{
        index: 0,
        message: {
          role: 'assistant',
          content: CONTENT_PIECES.join(''),
          tool_calls: [{
            id: TOOL_ID,
            type: 'function',
            function: { name: TOOL_NAME, arguments: TOOL_ARG_PIECES.join('') },
          }],
        },
        finish_reason: 'tool_calls',
      }],
      usage: { prompt_tokens: 24, completion_tokens: 18, total_tokens: 42 },
    });
  }

  writeSseHead(res);
  const id = chunkId();

  if (scenario.toolFirst) {
    // 复现组：工具调用先出，正文随后。
    // role 挂在工具调用首帧上 —— 助手轮的第一帧就是它。
    await writeOpenAiToolCall(res, id, model, true);
    await writeOpenAiContent(res, id, model, false);
  } else {
    await writeOpenAiContent(res, id, model, true);
    await writeOpenAiToolCall(res, id, model, false);
  }

  // 收尾：finish_reason 必须是 tool_calls，下游靠它触发工具调用的组装。
  writeChunk(res, openAiChunk(id, model, {}, 'tool_calls'));
  res.write('data: [DONE]\n\n');
  res.end();
  log(`✓ /chat/completions  scenario=${scenario.id}  完成`);
}

// ════════════════════════════════════════════════════
// Anthropic Messages：POST /mess
// ════════════════════════════════════════════════════

function messageId() {
  return `msg_mock_to_${Date.now()}`;
}

function writeEvent(res, payload) {
  res.write(`event: ${payload.type}\ndata: ${JSON.stringify(payload)}\n\n`);
}

/**
 * 发送 Anthropic 形态的 text block。
 *
 * @param index 该 block 的 index。Anthropic 的 index 覆盖<strong>所有</strong>块类型，
 *              因此它由调用方按实际顺序给出，而不是固定 0 —— 顺序不同则 index 不同，
 *              这正是本 mock 要暴露的差异之一。
 */
async function writeAnthropicText(res, index) {
  writeEvent(res, {
    type: 'content_block_start',
    index,
    content_block: { type: 'text', text: '' },
  });
  await sleep(FRAME_INTERVAL_MS);
  for (const piece of CONTENT_PIECES) {
    writeEvent(res, {
      type: 'content_block_delta',
      index,
      delta: { type: 'text_delta', text: piece },
    });
    await sleep(FRAME_INTERVAL_MS);
  }
  writeEvent(res, { type: 'content_block_stop', index });
  await sleep(FRAME_INTERVAL_MS);
}

/** 发送 Anthropic 形态的 tool_use block，参数分两片走 input_json_delta。 */
async function writeAnthropicToolUse(res, index) {
  writeEvent(res, {
    type: 'content_block_start',
    index,
    content_block: { type: 'tool_use', id: TOOL_ID, name: TOOL_NAME, input: {} },
  });
  await sleep(FRAME_INTERVAL_MS);
  for (const piece of TOOL_ARG_PIECES) {
    writeEvent(res, {
      type: 'content_block_delta',
      index,
      delta: { type: 'input_json_delta', partial_json: piece },
    });
    await sleep(FRAME_INTERVAL_MS);
  }
  writeEvent(res, { type: 'content_block_stop', index });
  await sleep(FRAME_INTERVAL_MS);
}

async function handleMessages(req, res, body) {
  const scenario = resolveScenario(body.model);
  const model = typeof body.model === 'string' && body.model ? body.model : scenario.id;
  const stream = body.stream === true;

  log(`→ /messages  model=${model}  scenario=${scenario.id}  stream=${stream}  toolFirst=${scenario.toolFirst}`);
  logToolAvailability(body);

  const id = messageId();

  if (!stream) {
    // 非流式：content 数组的顺序就是场景顺序，可直接观察下游是否按序处理。
    const textBlock = { type: 'text', text: CONTENT_PIECES.join('') };
    const toolBlock = {
      type: 'tool_use', id: TOOL_ID, name: TOOL_NAME,
      input: TOOL_ARGS,
    };
    return sendJson(res, 200, {
      id,
      type: 'message',
      role: 'assistant',
      model,
      content: scenario.toolFirst ? [toolBlock, textBlock] : [textBlock, toolBlock],
      stop_reason: 'tool_use',
      stop_sequence: null,
      usage: {
        input_tokens: 24,
        cache_creation_input_tokens: 0,
        cache_read_input_tokens: 0,
        output_tokens: 18,
      },
    });
  }

  writeSseHead(res);
  writeEvent(res, {
    type: 'message_start',
    message: {
      id,
      type: 'message',
      role: 'assistant',
      model,
      content: [],
      stop_reason: null,
      stop_sequence: null,
      usage: { input_tokens: 24, output_tokens: 0 },
    },
  });
  await sleep(FRAME_INTERVAL_MS);

  if (scenario.toolFirst) {
    // tool_use 占 index 0，text 占 index 1。
    // 这正是「thinking 占 0」那类稀疏 index 问题的同构场景：任何把 Anthropic index
    // 直接当 OpenAI tool_calls[].index 用的实现，在这里都会产出稀疏数组。
    await writeAnthropicToolUse(res, 0);
    await writeAnthropicText(res, 1);
  } else {
    await writeAnthropicText(res, 0);
    await writeAnthropicToolUse(res, 1);
  }

  writeEvent(res, {
    type: 'message_delta',
    delta: { stop_reason: 'tool_use', stop_sequence: null },
    usage: { output_tokens: 18 },
  });
  await sleep(FRAME_INTERVAL_MS);
  writeEvent(res, { type: 'message_stop' });
  res.end();
  log(`✓ /messages  scenario=${scenario.id}  完成`);
}

// ════════════════════════════════════════════════════
// 模型列表与路由
// ════════════════════════════════════════════════════

/** 两个端点共用同一份模型清单，便于在 COSP 后台一次拉全。 */
function handleModels(res) {
  sendJson(res, 200, {
    object: 'list',
    data: SCENARIOS.map(s => ({
      id: s.id,
      object: 'model',
      created: Math.floor(Date.now() / 1000),
      owned_by: 'mock-toolorder',
      description: s.desc,
    })),
  });
}

const server = http.createServer(async (req, res) => {
  const url = req.url || '';
  // 不叫 path：那会遮蔽模块顶层引入的 node:path。
  const pathname = url.split('?')[0];

  if (req.method === 'GET' && (pathname === '/models' || pathname === '/v1/models')) {
    return handleModels(res);
  }

  // 主形态是 /chat/completions（与 COSP 拼接的路径一致）；
  // /v1/chat/completions 容忍 Base URL 多写了 /v1，/chat 是手打用的短写。
  if (req.method === 'POST' && (pathname === '/chat/completions'
      || pathname === '/v1/chat/completions' || pathname === '/chat')) {
    const body = await readJsonBody(req);
    return handleChat(req, res, body);
  }

  // 主形态是 /messages；同样容忍 /v1 前缀与 /mess 短写。
  if (req.method === 'POST' && (pathname === '/messages'
      || pathname === '/v1/messages' || pathname === '/mess')) {
    // 与 mock-anthropic 同一口径：anthropic-version 是官方必需头，缺失就该响亮失败。
    const version = req.headers['anthropic-version'];
    if (version !== REQUIRED_ANTHROPIC_VERSION) {
      log(`✗ /messages 拒绍：anthropic-version=${version || '(缺失)'}，要求 ${REQUIRED_ANTHROPIC_VERSION}`);
      return sendJson(res, 400, {
        type: 'error',
        error: {
          type: 'invalid_request_error',
          message: `mock-toolorder 要求 anthropic-version: ${REQUIRED_ANTHROPIC_VERSION}`,
        },
      });
    }
    const body = await readJsonBody(req);
    return handleMessages(req, res, body);
  }

  sendJson(res, 404, {
    error: { type: 'not_found_error', message: `not found: ${req.method} ${url}` },
  });
  log(`404  ${req.method} ${url}`);
});

server.listen(PORT, () => {
  log(`COSP 模拟上游（工具调用顺序）已启动: http://localhost:${PORT}`);
  log(`  POST /chat/completions  OpenAI Chat Completions 形态（短写 /chat）`);
  log(`  POST /messages          Anthropic Messages 形态（短写 /mess，要求 anthropic-version: ${REQUIRED_ANTHROPIC_VERSION}）`);
  log(`  GET  /models            模型清单`);
  log('两个场景（两个端点同名同义）：');
  for (const s of SCENARIOS) log(`  - ${s.id.padEnd(18)} ${s.desc}`);
  log(`工具调用: ${TOOL_NAME}(${TOOL_ARG_JSON})`);
  if (!fs.existsSync(TOOL_TARGET_FILE)) {
    log(`  ⚠ 目标文件不存在，下游会报「文件未找到」而非顺序问题`);
    log(`    用 MOCK_TOOLORDER_FILE 指定一个存在的绝对路径`);
  }
  log(`帧间隔 ${FRAME_INTERVAL_MS}ms（MOCK_TOOLORDER_INTERVAL_MS 可覆盖）`);
  log(`在 COSP 后台新增供应商，两个 Base URL 都填 http://localhost:${PORT}`);
  log(`  COSP 会自行接 /chat/completions 与 /messages`);
});
