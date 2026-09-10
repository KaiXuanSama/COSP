#!/usr/bin/env node
'use strict';

/**
 * COSP 测试用模拟 Anthropic 上游供应商。
 *
 * 提供 Anthropic Messages API 的 POST /v1/messages，通过模型名触发正常、空响应、
 * 传输中断、错误码、重试中途成功等场景。它既服务 stream=false，也服务 stream=true：
 * 两种模式共用同一端点但响应形态不同，这正是 Anthropic 协议与原有两个 OpenAI mock 的差异。
 *
 * 特性：
 *  - 纯 Node 内置 http 模块，零依赖，无需 npm install。
 *  - 严格校验 anthropic-version 头；缺失时 400，给 COSP 侧一个免费断言。
 *  - 流式严格使用 event: <type> + data: <json> 的 Anthropic 事件形态；
 *    不能只发 data，否则客户端状态机无法解析。
 *  - 模型名统一以 at-（Anthropic）开头，避免和 normal / ns- 场景撞名。
 *
 * 启动：node mock-anthropic.js（或 npm run mock:anthropic）
 */

const http = require('http');

// ── 可调参数（直接改这里）─────────────────────────────
const PORT = Number(process.env.MOCK_ANTHROPIC_PORT || 8083);
const NORMAL_DELTA_INTERVAL_MS = 50;
const HANG_RESPONSE_MS = 10 * 60 * 1000;
const SLOW_RESPONSE_MS = 5 * 1000;
const RETRY_SUCCESS_ATTEMPT = 3;
const RETRY_RESET_MS = 30 * 1000;
const REQUIRED_ANTHROPIC_VERSION = '2023-06-01';
// ────────────────────────────────────────────────────

/** 场景模型清单：GET /v1/models 并非 Anthropic 标准端点，只供 COSP 后台拉取模型时使用。 */
const MODELS = [
  { id: 'at-normal', desc: '按 stream 返回正常 Anthropic message / 完整 SSE 事件序列' },
  { id: 'at-thinking-text', desc: 'thinking + text 两个 content block（验证 block index 与 usage 跨事件合并）' },
  { id: 'at-tool-use', desc: '纯 tool_use，无正文（对照组：不应判空）' },
  { id: 'at-tool-split-args', desc: '单工具 + 参数切成 30+ 片（含转义序列跨片边界）；验证 A2O 拼接' },
  { id: 'at-tool-multi-split', desc: '三个工具各自参数分片，block index 从 1 起（验证稠密重映射 + 拼接）' },
  { id: 'at-tool-interleaved', desc: '两个工具的参数分片交错发送（刻意非规范，压 index 映射）' },
  { id: 'at-tool-no-args', desc: '工具无参数，零个 input_json_delta（下游只应收到 name 帧）' },
  { id: 'at-tool-then-max-tokens', desc: '完整工具调用 + stop_reason: max_tokens（A2O 的 finish_reason 必须是 tool_calls）' },
  { id: 'at-tool-then-context-exceeded', desc: '完整工具调用 + 非标 model_context_window_exceeded（线上实测形态）' },
  { id: 'at-thinking-only', desc: '纯 thinking，无正文（对照组：不应判空）' },
  { id: 'at-empty-content', desc: '非流式：200 + content: []（应空响应兜底重发）' },
  { id: 'at-empty-usage-zero', desc: '非流式：空 content + 全 0 usage（应兜底；usage 不是判据）' },
  { id: 'at-empty-body', desc: '非流式：200 + 0 字节 body（应兜底，耗尽后放行空 body）' },
  { id: 'at-empty-stream', desc: '流式：仅 message_start / message_stop，无实质载荷（应兜底重发）' },
  { id: 'at-empty-stream-usage-zero', desc: '流式：空事件流但 usage 全 0（应兜底）' },
  { id: 'at-malformed-json', desc: '非流式：200 + 残缺 JSON（解析失败应保守放行）' },
  { id: 'at-stream-malformed', desc: '流式：一帧残缺 JSON（解析失败应保守放行）' },
  { id: 'at-truncated', desc: '声明 Content-Length 后只写一半就断 socket（网络类失败，应重试）' },
  { id: 'at-error-500', desc: '返回 Anthropic 风格 500（COSP 应重试）' },
  { id: 'at-error-401', desc: '返回 Anthropic 风格 401（COSP 应快速失败不重试）' },
  { id: 'at-retry-then-succeed', desc: '前 2 次 500，第 3 次正常（验证共享重试预算）' },
  { id: 'at-hang-response', desc: '收到请求后长时间不返回（默认 10 分钟，验证取消路径）' },
  { id: 'at-slow-response', desc: '延迟 5s 后返回完整响应' },
];

const NORMAL_TEXT = '这是一条来自 mock-anthropic 的完整回复，用于验证 COSP 的 Anthropic Messages 路径。';

function log(...args) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}]`, ...args);
}

function messageId() {
  return `msg_mock_at_${Date.now()}`;
}

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function usage(input = 24, output = 36, cacheRead = 0) {
  return {
    input_tokens: input,
    cache_creation_input_tokens: 0,
    cache_read_input_tokens: cacheRead,
    output_tokens: output,
    service_tier: 'standard',
  };
}

function messageBody(id, model, content, stopReason = 'end_turn', finalUsage = usage()) {
  return {
    id,
    type: 'message',
    role: 'assistant',
    model,
    content,
    stop_reason: stopReason,
    stop_sequence: null,
    usage: finalUsage,
  };
}

function sendJson(res, status, payload, label, model) {
  const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
  log(`✓ ${label}  model=${model}  bytes=${Buffer.byteLength(raw)}`);
}

function errorBody(type, message) {
  return { type: 'error', error: { type, message } };
}

function errorResponse(res, status, type, message, model) {
  sendJson(res, status, errorBody(type, message), `返回错误 ${status}`, model);
}

function writeSseHead(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
}

/** Anthropic SSE 帧：event 名与 JSON 里的 type 必须一致。 */
function writeEvent(res, payload) {
  const type = payload.type;
  res.write(`event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`);
}

function messageStart(id, model, inputTokens = 24) {
  return {
    type: 'message_start',
    message: {
      id,
      type: 'message',
      role: 'assistant',
      model,
      content: [],
      stop_reason: null,
      stop_sequence: null,
      usage: usage(inputTokens, 0),
    },
  };
}

function textBlockStart(index) {
  return { type: 'content_block_start', index, content_block: { type: 'text', text: '' } };
}

function thinkingBlockStart(index) {
  return { type: 'content_block_start', index, content_block: { type: 'thinking', thinking: '', signature: '' } };
}

function toolBlockStart(index) {
  return {
    type: 'content_block_start',
    index,
    content_block: { type: 'tool_use', id: 'toolu_mock_1', name: 'get_weather', input: {} },
  };
}

/**
 * 可指定 id / name 的工具块声明，供多工具场景使用。
 *
 * <p>id 必须各不相同：下游按 id 关联后续的 tool_result，重复 id 会让多轮对话串线。
 */
function namedToolBlockStart(index, toolId, name) {
  return {
    type: 'content_block_start',
    index,
    content_block: { type: 'tool_use', id: toolId, name, input: {} },
  };
}

function textDelta(index, text) {
  return { type: 'content_block_delta', index, delta: { type: 'text_delta', text } };
}

function thinkingDelta(index, thinking) {
  return { type: 'content_block_delta', index, delta: { type: 'thinking_delta', thinking } };
}

function inputJsonDelta(index, partialJson) {
  return { type: 'content_block_delta', index, delta: { type: 'input_json_delta', partial_json: partialJson } };
}

function blockStop(index) {
  return { type: 'content_block_stop', index };
}

function messageDelta(outputTokens, stopReason = 'end_turn', includeZeroInput = false) {
  // Anthropic 的真实 message_delta 通常只报最终 output_tokens；
  // 不要默认塞 input_tokens: 0，否则 COSP 的 merge 会把 message_start 的真实输入 token 覆盖掉，
  // mock 测到的是错误行为。只有空 usage 对照场景才显式要求全 0。
  const finalUsage = { output_tokens: outputTokens };
  if (includeZeroInput) {
    finalUsage.input_tokens = 0;
    finalUsage.cache_creation_input_tokens = 0;
    finalUsage.cache_read_input_tokens = 0;
  }
  return { type: 'message_delta', delta: { stop_reason: stopReason, stop_sequence: null }, usage: finalUsage };
}

function messageStop() {
  return { type: 'message_stop' };
}

function normalContent() {
  return [{ type: 'text', text: NORMAL_TEXT }];
}

/** 标准正文流：message_start → text block → N delta → stop。 */
function writeNormalStream(res, id, model, done) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model));
  writeEvent(res, textBlockStart(0));
  const parts = ['这是一条来自 ', 'mock-anthropic ', '的流式回复，', '用于验证 COSP ', '对 Anthropic ', '事件序列的原样透传。'];
  let index = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (index < parts.length) {
      writeEvent(res, textDelta(0, parts[index]));
      index += 1;
      return;
    }
    clearInterval(timer);
    writeEvent(res, blockStop(0));
    writeEvent(res, messageDelta(36));
    writeEvent(res, messageStop());
    res.end();
    log(`✓ at-normal 流完成  model=${model}  deltas=${parts.length}`);
    if (done) done();
  }, NORMAL_DELTA_INTERVAL_MS);
  res.on('close', () => clearInterval(timer));
}

/** thinking + text 双 block，真实上游常见形态。 */
function writeThinkingTextStream(res, id, model) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model, 42));
  writeEvent(res, thinkingBlockStart(0));
  writeEvent(res, thinkingDelta(0, '先分析用户的需求。'));
  writeEvent(res, thinkingDelta(0, '然后给出清晰答案。'));
  writeEvent(res, { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'sig_mock' } });
  writeEvent(res, blockStop(0));
  writeEvent(res, textBlockStart(1));
  writeEvent(res, textDelta(1, '这是经过思考后的正文回答。'));
  writeEvent(res, blockStop(1));
  writeEvent(res, messageDelta(28));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ at-thinking-text 流完成  model=${model}`);
}

/** 工具调用流：tool_use 在 block_start 时已经是实质载荷。 */
function writeToolUseStream(res, id, model) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model));
  writeEvent(res, toolBlockStart(0));
  writeEvent(res, inputJsonDelta(0, '{"city":"Hang'));
  writeEvent(res, inputJsonDelta(0, 'zhou"}'));
  writeEvent(res, blockStop(0));
  writeEvent(res, messageDelta(14, 'tool_use'));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ at-tool-use 流完成  model=${model}`);
}

// ── 参数分片场景 ───────────────────────────────────────
//
// 真实上游对「一个工具的参数 JSON 怎么切」没有共识：MiMo 实测把 6KB 参数
// 塞在单个 input_json_delta 里一次发完，而 Anthropic 官方会切成多片。
// 因此这一组场景是唯一能在本地压到多片路径的手段。
//
// 关键约束：每一片**单独都不是合法 JSON**，下游必须靠拼接还原。
// 翻译层只要在某一片上把 tool index 算错，拼出来的就是废串。

/**
 * 把一个 JSON 字符串按固定长度切片，**刻意不避开转义序列**。
 *
 * <p>不避开是重点：`\"` 与 `\uXXXX` 被拦腰截断，是最容易暴露「谁在中途试图
 * 解析单片」这类缺陷的形状。正确实现应把每片当作**不透明字节**原样转发，
 * 只在下游完整拼接后才有 JSON 语义。
 *
 * @param json 完整参数 JSON
 * @param size 每片长度；取小值可制造大量分片
 */
function sliceJson(json, size) {
  const parts = [];
  for (let i = 0; i < json.length; i += size) {
    parts.push(json.slice(i, i + size));
  }
  return parts;
}

/** 带转义地雷的参数：反斜杠路径、转义引号、换行、中文与 emoji。 */
function escapeHeavyArgs() {
  return JSON.stringify({
    filePath: 'd:\\KaiXuan\\Desktop\\copilotDebug\\a "quoted" name.py',
    content: '# 中文注释 with "双引号" and \\反斜杠\\\n'
      + 'def greet(name: str) -> str:\n'
      + '    """返回问候语。\n\n'
      + '    包含制表符\t与换行，以及 emoji 🎉 用于压 UTF-16 代理对。\n'
      + '    """\n'
      + '    return f"你好，{name}！"\n',
    mode: 'sync',
  });
}

/**
 * 单工具、参数切成大量小片。
 *
 * <p>期望的下游形态：一个带 `name` 的帧（`arguments` 为空串），
 * 随后 N 个只带 `arguments` 的帧，全部落在 `tool_calls[0].index === 0`，
 * 按序拼接后等于 `escapeHeavyArgs()` 的原文。
 */
function writeToolSplitArgsStream(res, id, model) {
  writeSseHead(res);
  const args = escapeHeavyArgs();
  // 每片 12 字符：足够小以保证转义序列被截断，且分片数 30+。
  const parts = sliceJson(args, 12);

  writeEvent(res, messageStart(id, model, 57));
  writeEvent(res, namedToolBlockStart(0, 'toolu_split_1', 'create_file'));
  for (const part of parts) {
    writeEvent(res, inputJsonDelta(0, part));
  }
  writeEvent(res, blockStop(0));
  writeEvent(res, messageDelta(96, 'tool_use'));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ at-tool-split-args 流完成  model=${model}  参数 ${args.length} 字符切成 ${parts.length} 片`);
}

/**
 * 三个工具，各自参数分片，且 **block index 从 1 开始**（0 给 thinking）。
 *
 * <p>这是 tool index 双索引域最容易错的形状：Anthropic 的 block index 是
 * 1/2/3，而 OpenAI 的 `tool_calls[].index` 必须是稠密的 0/1/2。
 * 直接透传 block index 会产出从 1 起的稀疏数组，下游拼不出第一个工具。
 */
function writeToolMultiSplitStream(res, id, model) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model, 63));

  // index 0 留给 thinking，把工具挤到 1/2/3。
  writeEvent(res, thinkingBlockStart(0));
  writeEvent(res, thinkingDelta(0, '需要连续调用三个工具。'));
  writeEvent(res, { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'sig_multi' } });
  writeEvent(res, blockStop(0));

  const tools = [
    { blockIndex: 1, toolId: 'toolu_multi_a', name: 'create_directory',
      args: JSON.stringify({ dirPath: 'd:\\tmp\\alpha' }) },
    { blockIndex: 2, toolId: 'toolu_multi_b', name: 'create_file',
      args: escapeHeavyArgs() },
    { blockIndex: 3, toolId: 'toolu_multi_c', name: 'run_in_terminal',
      args: JSON.stringify({ command: 'Get-ChildItem -Path "d:\\tmp" -Recurse', mode: 'sync' }) },
  ];

  let sliceTotal = 0;
  for (const tool of tools) {
    writeEvent(res, namedToolBlockStart(tool.blockIndex, tool.toolId, tool.name));
    const parts = sliceJson(tool.args, 9);
    sliceTotal += parts.length;
    for (const part of parts) {
      writeEvent(res, inputJsonDelta(tool.blockIndex, part));
    }
    writeEvent(res, blockStop(tool.blockIndex));
  }

  writeEvent(res, messageDelta(184, 'tool_use'));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ at-tool-multi-split 流完成  model=${model}  3 个工具共 ${sliceTotal} 片，block index 1..3`);
}

/**
 * 两个工具的参数分片**交错**发送。
 *
 * <h2>为何测一个不规范的形状</h2>
 * Anthropic 官方文档描述的是块顺序完成（一个 block 的 delta 发完才开下一个），
 * 交错在实践中未观测到。但中转站会重排事件，且「按 block index 查表路由」
 * 这个实现<strong>本应</strong>天然支持交错 —— 若某个实现偷懒用「当前活跃块」
 * 之类的隐式状态，交错就会把两个工具的参数搅在一起。
 *
 * <p>因此这是一个**实现健壮性**探针，而非协议合规性测试。
 * 期望：两段参数各自独立拼接成合法 JSON，互不污染。
 */
function writeToolInterleavedStream(res, id, model) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model, 48));

  const first = JSON.stringify({ dirPath: 'd:\\tmp\\first-tool-path' });
  const second = JSON.stringify({ command: 'echo "second tool"', mode: 'async' });

  // 两个块都先声明，再交错发 delta。
  writeEvent(res, namedToolBlockStart(0, 'toolu_inter_a', 'create_directory'));
  writeEvent(res, namedToolBlockStart(1, 'toolu_inter_b', 'run_in_terminal'));

  const firstParts = sliceJson(first, 8);
  const secondParts = sliceJson(second, 8);
  const rounds = Math.max(firstParts.length, secondParts.length);
  for (let i = 0; i < rounds; i += 1) {
    if (i < firstParts.length) writeEvent(res, inputJsonDelta(0, firstParts[i]));
    if (i < secondParts.length) writeEvent(res, inputJsonDelta(1, secondParts[i]));
  }

  writeEvent(res, blockStop(0));
  writeEvent(res, blockStop(1));
  writeEvent(res, messageDelta(72, 'tool_use'));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ at-tool-interleaved 流完成  model=${model}  ${firstParts.length}+${secondParts.length} 片交错`);
}

/**
 * 工具无参数：`content_block_start` 之后<strong>零个</strong> `input_json_delta`。
 *
 * <p>真实存在的形态（无参工具，如 `get_current_time`）。下游应只收到那一个带
 * `name` 的帧，`arguments` 为空串 —— 不能凭空补一个 `{}` 帧，也不能因为
 * 「没有 delta」就把整个工具调用丢掉。
 */
function writeToolNoArgsStream(res, id, model) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model, 19));
  writeEvent(res, namedToolBlockStart(0, 'toolu_noargs_1', 'get_current_time'));
  writeEvent(res, blockStop(0));
  writeEvent(res, messageDelta(8, 'tool_use'));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ at-tool-no-args 流完成  model=${model}  零个 input_json_delta`);
}

/**
 * 完整工具调用 + 一个**非 `tool_use`** 的 stop_reason。
 *
 * <p>这是 A2O `finish_reason` 覆盖规则的复现器（响应侧契约第 8.1 节）。
 * OpenAI 语义要求含完整 `tool_calls` 的响应把 `finish_reason` 报成 `tool_calls`，
 * 优先于 `length` / `stop`。Copilot 收到 `length` 会判定回答被截断，于是
 * **放弃执行已经拿到的完整工具调用**并结束对话 —— 且全链路无任何报错。
 *
 * <p>工具块在这里是**正常闭合**的（参数完整 + `content_block_stop`），
 * 所以「截断」这个结论只可能来自 stop_reason 的直译，而不是真的残缺。
 *
 * <p>两个 stop_reason 都要覆盖：`max_tokens` 是标准值，
 * `model_context_window_exceeded` 是线上实测那次撑爆上下文时上游给的非标值。
 * 前者证明这不是某个非标值的专属问题 —— 只针对一个值打补丁换个 stop_reason 就会再犯。
 *
 * @param stopReason 收尾用的 stop_reason，刻意不是 tool_use
 */
function writeToolThenStopReasonStream(res, id, model, stopReason) {
  writeSseHead(res);
  const args = JSON.stringify({
    todoList: [
      { id: 1, status: 'in-progress', title: '定位图片兼容规则' },
      { id: 2, status: 'not-started', title: '汇总成因与方案' },
    ],
  });
  const parts = sliceJson(args, 16);

  writeEvent(res, messageStart(id, model, 309971));
  // 先给一段正文，与实测序列一致（正文 + 工具调用同时存在）。
  writeEvent(res, textBlockStart(0));
  writeEvent(res, textDelta(0, '先核对默认规则与执行顺序，确认在哪一步丢失。'));
  writeEvent(res, blockStop(0));
  // 工具块正常闭合：参数齐全，content_block_stop 到达。
  writeEvent(res, namedToolBlockStart(1, 'toolu_finish_reason', 'manage_todo_list'));
  for (const part of parts) {
    writeEvent(res, inputJsonDelta(1, part));
  }
  writeEvent(res, blockStop(1));
  writeEvent(res, messageDelta(209, stopReason));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ 工具调用 + stop_reason=${stopReason} 流完成  model=${model}  `
    + `期望下游 finish_reason=tool_calls`);
}

/** 纯思考链流：对照组，不应被 COSP 判为空。 */
function writeThinkingOnlyStream(res, id, model) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model));
  writeEvent(res, thinkingBlockStart(0));
  writeEvent(res, thinkingDelta(0, '这里只有思考链，但它仍是实质载荷。'));
  writeEvent(res, blockStop(0));
  writeEvent(res, messageDelta(18));
  writeEvent(res, messageStop());
  res.end();
  log(`✓ at-thinking-only 流完成  model=${model}`);
}

function streamEmpty(res, id, model, withZeroUsage) {
  writeSseHead(res);
  writeEvent(res, messageStart(id, model, 0));
  if (withZeroUsage) {
    writeEvent(res, messageDelta(0, 'end_turn', true));
  }
  writeEvent(res, messageStop());
  res.end();
  log(`✓ ${withZeroUsage ? 'at-empty-stream-usage-zero' : 'at-empty-stream'} 已返回空事件流  model=${model}`);
}

function streamMalformed(res, model) {
  writeSseHead(res);
  // 故意保留 event 名、截断 data JSON：COSP 解析失败必须保守放行，不能判空重试。
  res.write('event: content_block_delta\ndata: {"type":"content_block_delta","delta":{"type":"text_delta","text":"te\n\n');
  res.end();
  log(`✓ at-stream-malformed 已返回残缺事件 JSON  model=${model}`);
}

function nonStreamNormal(res, id, model) {
  sendJson(res, 200, messageBody(id, model, normalContent()), 'at-normal 已返回完整 message', model);
}

function nonStreamThinkingText(res, id, model) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'thinking', thinking: '先检查事件序列，再给出回答。', signature: 'sig_mock' },
    { type: 'text', text: '这是带思考链的正文回答。' },
  ], 'end_turn', usage(42, 28)), 'at-thinking-text 已返回 thinking + text', model);
}

function nonStreamToolUse(res, id, model) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'tool_use', id: 'toolu_mock_1', name: 'get_weather', input: { city: 'Hangzhou' } },
  ], 'tool_use', usage(31, 14)), 'at-tool-use 已返回纯工具调用', model);
}

/**
 * 分片场景的非流式对照。
 *
 * <p>非流式**没有分片概念** —— `input` 是一个完整对象。保留这些入口是为了让
 * 「同一模型名在两种模式下都能打」这个约定不破，同时提供一份「参数原文应该
 * 长什么样」的基准：流式拼接的结果必须与这里的 `input` 序列化后一致。
 */
function nonStreamToolSplitArgs(res, id, model) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'tool_use', id: 'toolu_split_1', name: 'create_file', input: JSON.parse(escapeHeavyArgs()) },
  ], 'tool_use', usage(57, 96)), 'at-tool-split-args 已返回完整 input（非流式无分片）', model);
}

function nonStreamToolMultiSplit(res, id, model) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'thinking', thinking: '需要连续调用三个工具。', signature: 'sig_multi' },
    { type: 'tool_use', id: 'toolu_multi_a', name: 'create_directory', input: { dirPath: 'd:\\tmp\\alpha' } },
    { type: 'tool_use', id: 'toolu_multi_b', name: 'create_file', input: JSON.parse(escapeHeavyArgs()) },
    { type: 'tool_use', id: 'toolu_multi_c', name: 'run_in_terminal',
      input: { command: 'Get-ChildItem -Path "d:\\tmp" -Recurse', mode: 'sync' } },
  ], 'tool_use', usage(63, 184)), 'at-tool-multi-split 已返回三个工具', model);
}

function nonStreamToolInterleaved(res, id, model) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'tool_use', id: 'toolu_inter_a', name: 'create_directory',
      input: { dirPath: 'd:\\tmp\\first-tool-path' } },
    { type: 'tool_use', id: 'toolu_inter_b', name: 'run_in_terminal',
      input: { command: 'echo "second tool"', mode: 'async' } },
  ], 'tool_use', usage(48, 72)), 'at-tool-interleaved 已返回两个工具（非流式无交错）', model);
}

function nonStreamToolNoArgs(res, id, model) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'tool_use', id: 'toolu_noargs_1', name: 'get_current_time', input: {} },
  ], 'tool_use', usage(19, 8)), 'at-tool-no-args 已返回无参工具', model);
}

/**
 * finish_reason 覆盖场景的非流式对照。
 *
 * <p>非流式侧有同一个缺陷且更直白：翻译器上一行刚判断过 `tool_calls` 非空并写入，
 * 下一行却直接采用 stop_reason 的映射结果。结果是同一个 choice 里既挂着完整工具调用、
 * 又声称回答被截断。
 */
function nonStreamToolThenStopReason(res, id, model, stopReason) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'text', text: '先核对默认规则与执行顺序。' },
    { type: 'tool_use', id: 'toolu_finish_reason', name: 'manage_todo_list',
      input: { todoList: [{ id: 1, status: 'in-progress', title: '定位图片兼容规则' }] } },
  ], stopReason, usage(309971, 209)),
  `工具调用 + stop_reason=${stopReason}，期望下游 finish_reason=tool_calls`, model);
}

function nonStreamThinkingOnly(res, id, model) {
  sendJson(res, 200, messageBody(id, model, [
    { type: 'thinking', thinking: '这里只有思考链，它是实质载荷。', signature: 'sig_mock' },
  ], 'end_turn', usage(26, 18)), 'at-thinking-only 已返回纯思考链', model);
}

function nonStreamEmpty(res, id, model, withZeroUsage) {
  sendJson(res, 200, messageBody(id, model, [], 'end_turn', withZeroUsage ? usage(0, 0) : usage(24, 0)),
    withZeroUsage ? 'at-empty-usage-zero 已返回空 content + 0 usage' : 'at-empty-content 已返回空 content', model);
}

function emptyBody(res, model) {
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': 0 });
  res.end();
  log(`✓ at-empty-body 已返回 200 + 0 字节 body  model=${model}`);
}

function malformedJson(res, id, model) {
  const raw = `{"id":"${id}","type":"message","content":[{"type":"text","text":"te`;
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(raw) });
  res.end(raw);
  log(`✓ at-malformed-json 已返回残缺 JSON  model=${model}`);
}

function truncated(res, id, model) {
  const full = JSON.stringify(messageBody(id, model, normalContent()));
  const half = full.slice(0, Math.floor(full.length / 2));
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(full),
  });
  res.write(half);
  res.socket.destroy();
  log(`✗ at-truncated 写 ${Buffer.byteLength(half)}/${Buffer.byteLength(full)} 字节后断开 socket  model=${model}`);
}

let retryAttempt = 0;
let retryLastAt = 0;
function retryThenSucceed(res, id, model, stream) {
  const now = Date.now();
  if (retryLastAt > 0 && now - retryLastAt > RETRY_RESET_MS) {
    log(`↺ retry 计数器空闲 ${Math.round((now - retryLastAt) / 1000)}s，已清零`);
    retryAttempt = 0;
  }
  retryLastAt = now;
  retryAttempt += 1;
  if (retryAttempt < RETRY_SUCCESS_ATTEMPT) {
    log(`✗ at-retry-then-succeed 第 ${retryAttempt} 次请求，返回 500  model=${model}`);
    return errorResponse(res, 500, 'api_error', `mock Anthropic retry (attempt ${retryAttempt})`, model);
  }
  log(`↻ at-retry-then-succeed 第 ${retryAttempt} 次请求，正常回复  model=${model}`);
  retryAttempt = 0;
  return stream ? writeNormalStream(res, id, model) : nonStreamNormal(res, id, model);
}

function hangResponse(res, model) {
  log(`… at-hang-response 收到请求，挂起 ${HANG_RESPONSE_MS / 1000}s 不返回  model=${model}`);
  const timer = setTimeout(() => {
    if (res.writableEnded) return;
    nonStreamNormal(res, messageId(), model);
  }, HANG_RESPONSE_MS);
  res.on('close', () => clearTimeout(timer));
}

function slowResponse(res, id, model, stream) {
  log(`… at-slow-response 延迟 ${SLOW_RESPONSE_MS / 1000}s 后返回  model=${model}`);
  const timer = setTimeout(() => {
    if (res.writableEnded) return;
    if (stream) writeNormalStream(res, id, model);
    else nonStreamNormal(res, id, model);
  }, SLOW_RESPONSE_MS);
  res.on('close', () => clearTimeout(timer));
}

function handleMessages(req, res, body) {
  const model = typeof body.model === 'string' ? body.model : 'at-normal';
  const stream = body.stream === true;
  const id = messageId();
  log(`▶ messages 开始  model=${model}  stream=${stream}`);
  req.on('aborted', () => log(`⨯ 客户端断开  model=${model}`));
  res.on('close', () => log(`■ 连接关闭  model=${model}`));

  switch (model) {
    case 'at-normal': return stream ? writeNormalStream(res, id, model) : nonStreamNormal(res, id, model);
    case 'at-thinking-text': return stream ? writeThinkingTextStream(res, id, model) : nonStreamThinkingText(res, id, model);
    case 'at-tool-use': return stream ? writeToolUseStream(res, id, model) : nonStreamToolUse(res, id, model);
    case 'at-tool-split-args': return stream ? writeToolSplitArgsStream(res, id, model) : nonStreamToolSplitArgs(res, id, model);
    case 'at-tool-multi-split': return stream ? writeToolMultiSplitStream(res, id, model) : nonStreamToolMultiSplit(res, id, model);
    case 'at-tool-interleaved': return stream ? writeToolInterleavedStream(res, id, model) : nonStreamToolInterleaved(res, id, model);
    case 'at-tool-no-args': return stream ? writeToolNoArgsStream(res, id, model) : nonStreamToolNoArgs(res, id, model);
    case 'at-tool-then-max-tokens': return stream
      ? writeToolThenStopReasonStream(res, id, model, 'max_tokens')
      : nonStreamToolThenStopReason(res, id, model, 'max_tokens');
    case 'at-tool-then-context-exceeded': return stream
      ? writeToolThenStopReasonStream(res, id, model, 'model_context_window_exceeded')
      : nonStreamToolThenStopReason(res, id, model, 'model_context_window_exceeded');
    case 'at-thinking-only': return stream ? writeThinkingOnlyStream(res, id, model) : nonStreamThinkingOnly(res, id, model);
    case 'at-empty-content': return stream ? streamEmpty(res, id, model, false) : nonStreamEmpty(res, id, model, false);
    case 'at-empty-usage-zero': return stream ? streamEmpty(res, id, model, true) : nonStreamEmpty(res, id, model, true);
    case 'at-empty-body': return stream ? streamEmpty(res, id, model, false) : emptyBody(res, model);
    case 'at-empty-stream': return stream ? streamEmpty(res, id, model, false) : nonStreamEmpty(res, id, model, false);
    case 'at-empty-stream-usage-zero': return stream ? streamEmpty(res, id, model, true) : nonStreamEmpty(res, id, model, true);
    case 'at-malformed-json': return stream ? streamMalformed(res, model) : malformedJson(res, id, model);
    case 'at-stream-malformed': return stream ? streamMalformed(res, model) : malformedJson(res, id, model);
    case 'at-truncated': return truncated(res, id, model);
    case 'at-error-500': return errorResponse(res, 500, 'api_error', 'mock Anthropic error (500)', model);
    case 'at-error-401': return errorResponse(res, 401, 'authentication_error', 'mock Anthropic unauthorized (401)', model);
    case 'at-retry-then-succeed': return retryThenSucceed(res, id, model, stream);
    case 'at-hang-response': return hangResponse(res, model);
    case 'at-slow-response': return slowResponse(res, id, model, stream);
    default:
      log(`? 未知模型 ${model}，按 at-normal 处理`);
      return stream ? writeNormalStream(res, id, model) : nonStreamNormal(res, id, model);
  }
}

function handleModels(res) {
  const data = MODELS.map((m) => ({
    id: m.id,
    object: 'model',
    created: nowSeconds(),
    owned_by: 'mock-anthropic',
  }));
  sendJson(res, 200, { object: 'list', data }, `/v1/models 返回 ${data.length} 个模型`, '-');
}

function readJsonBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (chunk) => { raw += chunk; });
    req.on('end', () => {
      try { resolve(raw ? JSON.parse(raw) : {}); } catch { resolve({}); }
    });
    req.on('error', () => resolve({}));
  });
}

const server = http.createServer(async (req, res) => {
  const url = req.url || '';

  if (req.method === 'GET' && (url === '/v1/models' || url.startsWith('/v1/models?'))) {
    return handleModels(res);
  }

  if (req.method === 'POST' && (url === '/v1/messages' || url.startsWith('/v1/messages?'))) {
    const version = req.headers['anthropic-version'];
    if (version !== REQUIRED_ANTHROPIC_VERSION) {
      log(`✗ 拒绝请求：anthropic-version=${version || '(缺失)'}，要求 ${REQUIRED_ANTHROPIC_VERSION}`);
      return errorResponse(res, 400, 'invalid_request_error',
        `mock-anthropic 要求 anthropic-version: ${REQUIRED_ANTHROPIC_VERSION}`, '-');
    }
    const body = await readJsonBody(req);
    return handleMessages(req, res, body);
  }

  const raw = JSON.stringify(errorBody('not_found_error', `not found: ${req.method} ${url}`));
  res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(raw) });
  res.end(raw);
  log(`404  ${req.method} ${url}`);
});

server.listen(PORT, () => {
  log(`COSP 模拟上游（Anthropic）已启动: http://localhost:${PORT}`);
  log(`  GET  /v1/models`);
  log(`  POST /v1/messages  （要求 anthropic-version: ${REQUIRED_ANTHROPIC_VERSION}）`);
  log('可用模型：');
  for (const m of MODELS) log(`  - ${m.id.padEnd(30)} ${m.desc}`);
  log('在 COSP 管理后台新增供应商，Base URL 填 http://localhost:' + PORT + '/v1 即可。');
});
