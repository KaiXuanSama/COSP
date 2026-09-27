#!/usr/bin/env node
'use strict';

/**
 * OpenAI Chat Completions 协议（`POST /chat/completions`）的场景实现。
 *
 * <p>本文件只负责「帧长什么样」—— 三协议无关的传输层病态行为
 * （错误码 / 重试 / 截断 / 挂起 / 延迟）由 `lib/transport.js` 提供，
 * 场景注册与分派在 `mock-upstream.js`。
 *
 * <p>场景命名口径：**名字描述「被测的 COSP 判定点」，不描述 mock 自己的动作**。
 * 域前缀即期望 —— `blank-*` 期望判空重试、`pass-*` 期望放行、`retry-*` 期望重试、
 * `fail-*` 期望快速失败、`phase-*` 期望相位正确、`cancel-*` 期望可中断。
 */

const {
  attachLifecycleLogs,
  log,
  sendEmptyBody,
  sendJson,
  writeCommentFrame,
  writeDataFrame,
  writeSseHead,
} = require('../lib/http');
const { sharedScenarios } = require('../lib/transport');

/* ── 可调参数 ─────────────────────────────────────────── */

/** 正常流的 chunk 间隔（毫秒）。 */
const NORMAL_CHUNK_INTERVAL_MS = 80;
/** 正常流的 chunk 数。 */
const NORMAL_CHUNK_COUNT = 30;
/** cancel-stall-* 停滞前先吐的 chunk 数。 */
const STALL_AFTER_CHUNKS = 5;
/** cancel-stall-resume 中途停滞时长（毫秒）。 */
const STALL_RESUME_MS = 35 * 1000;
/** cancel-stall-delayed 首字之前的延迟（毫秒）。 */
const STALL_DELAYED_LEAD_MS = 5 * 1000;
/** phase-slow 之外的慢速持续推流间隔（毫秒）。 */
const SLOW_STEADY_INTERVAL_MS = 3 * 1000;
/** phase-slow 之外的慢速推流 chunk 数。 */
const SLOW_STEADY_CHUNK_COUNT = 8;

/** 正常非流式响应的正文。 */
const NORMAL_CONTENT = '这是一条来自 mock-upstream 的完整非流式回复，用于验证 COSP 的 stream=false 路径。';

/* ── 帧构造 ───────────────────────────────────────────── */

function chunkEnvelope(id, model, delta, finishReason) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason ?? null }],
  };
}

/** 首个带 role 的 chunk（OpenAI 流首帧惯例）。 */
function roleFrame(id, model) {
  return chunkEnvelope(id, model, { role: 'assistant' });
}

/** 收尾 chunk（`finish_reason: stop`）。 */
function finishFrame(id, model, reason = 'stop') {
  return chunkEnvelope(id, model, {}, reason);
}

function contentFrame(id, model, content) {
  return chunkEnvelope(id, model, { content });
}

function newId() {
  return `chatcmpl-mock-${Date.now()}`;
}

/** 生成本次响应的 chunk 内容片段（简单可读文本）。 */
function makeChunkText(i) {
  return `token${i} `;
}

/** 构造非流式响应体（`chat.completion` 形态）。 */
function completionBody(id, model, message, finishReason, usage) {
  const body = {
    id,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: message === null
      ? []
      : [{ index: 0, message, finish_reason: finishReason }],
  };
  if (usage) {
    body.usage = usage;
  }
  return body;
}

const NORMAL_USAGE = { prompt_tokens: 26, completion_tokens: 41, total_tokens: 67 };

/** 该协议的 OpenAI 风格错误体。 */
function renderErrorBody(status, message) {
  return { error: { message, type: 'mock_error', code: status } };
}

/* ── 正常响应 ─────────────────────────────────────────── */

/**
 * 正常流式：role → N 个 content → finish → [DONE] → 关闭。
 *
 * @param onDone 收尾后的回调，供需要额外日志的场景使用
 */
function writeNormalStream(res, model, onDone) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  let i = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (i < NORMAL_CHUNK_COUNT) {
      writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
      i += 1;
      return;
    }
    clearInterval(timer);
    writeDataFrame(res, finishFrame(id, model));
    writeDataFrame(res, '[DONE]');
    res.end();
    log(`✓ baseline-normal 流完成  model=${model}  chunks=${NORMAL_CHUNK_COUNT}`);
    if (onDone) onDone();
  }, NORMAL_CHUNK_INTERVAL_MS);
  res.on('close', () => clearInterval(timer));
}

/** 正常非流式：完整正文 + 真实 usage。 */
function sendNormalNonStream(res, model) {
  const body = completionBody(
    newId(), model,
    { role: 'assistant', content: NORMAL_CONTENT },
    'stop',
    NORMAL_USAGE,
  );
  sendJson(res, 200, body, 'baseline-normal 已返回完整响应', model);
}

/** 正常非流式响应体的完整文本，供截断场景使用。 */
function serializeNormalBody(model) {
  return JSON.stringify(completionBody(
    newId(), model,
    { role: 'assistant', content: NORMAL_CONTENT },
    'stop',
    NORMAL_USAGE,
  ));
}

/* ── baseline：常规形态 ───────────────────────────────── */

/**
 * baseline-content-first / baseline-tool-first：工具调用与正文的先后顺序。
 *
 * <p>这对场景来自已合并的 `mock-toolorder`。它的调研结论是**顺序已被排除**
 * （四种组合各跑两轮，Copilot 全部正常解析并执行工具）—— 因此现在的作用是
 * **回归基准**：已知正常的形态仍然正常，下次怀疑流解析时先跑它排除干扰。
 *
 * <p>唯一变量是顺序，故两侧的正文与工具调用内容完全相同。
 *
 * <p>工具参数刻意切成**两片**且切点落在值内部：任何试图按单片解析 JSON 的实现
 * 都会在这里暴露。参数中三个字段在 `read_file` 的 schema 里都是 required ——
 * 曾经只发 `{"filePath":"README.md"}`（缺字段 + 相对路径），下游必然以参数校验
 * 失败告终，那种失败会把「顺序」这个唯一变量彻底淹没。
 */
const CONTENT_PIECES = ['我来看一下', '这个文件的内容。'];
const TOOL_NAME = 'read_file';
const TOOL_ID = 'call_mock_1';
const TOOL_ARG_JSON = JSON.stringify({
  filePath: process.env.MOCK_UPSTREAM_FILE || 'README.md',
  startLine: 1,
  endLine: 40,
});
const TOOL_ARG_PIECES = [
  TOOL_ARG_JSON.slice(0, Math.floor(TOOL_ARG_JSON.length / 2)),
  TOOL_ARG_JSON.slice(Math.floor(TOOL_ARG_JSON.length / 2)),
];
/** 顺序场景的帧间隔：调大可在客户端 UI 上肉眼看清顺序。 */
const ORDER_FRAME_INTERVAL_MS = Number(process.env.MOCK_UPSTREAM_INTERVAL_MS || 120);

/**
 * 工具调用首帧给全 id / type / name，后续帧只带 arguments 增量（OpenAI 标准分片）。
 *
 * @param label 日志用的场景名。`baseline-tool-call` 与 `pass-tool-call` 复用同一实现，
 *              若日志写死其中一个名字，另一个场景在日志里就找不到自己的痕迹 ——
 *              而排障时正是靠日志确认「请求进来了几次」。
 */
function toolCallFrames(id, model, includeRole) {
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
  const frames = [chunkEnvelope(id, model, firstDelta)];
  for (let i = 1; i < TOOL_ARG_PIECES.length; i += 1) {
    frames.push(chunkEnvelope(id, model, {
      tool_calls: [{ index: 0, function: { arguments: TOOL_ARG_PIECES[i] } }],
    }));
  }
  return frames;
}

function contentFrames(id, model, includeRole) {
  return CONTENT_PIECES.map((piece, i) => {
    const delta = { content: piece };
    if (includeRole && i === 0) {
      delta.role = 'assistant';
    }
    return chunkEnvelope(id, model, delta);
  });
}

function writeOrderedStream(res, model, toolFirst) {
  const id = newId();
  const label = toolFirst ? 'baseline-tool-first' : 'baseline-content-first';
  writeSseHead(res);
  const frames = toolFirst
    ? [...toolCallFrames(id, model, true), ...contentFrames(id, model, false)]
    : [...contentFrames(id, model, true), ...toolCallFrames(id, model, false)];
  let i = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (i < frames.length) {
      writeDataFrame(res, frames[i]);
      i += 1;
      return;
    }
    clearInterval(timer);
    // 收尾 finish_reason 必须是 tool_calls，下游靠它触发工具调用的组装。
    writeDataFrame(res, finishFrame(id, model, 'tool_calls'));
    writeDataFrame(res, '[DONE]');
    res.end();
    log(`✓ ${label} 流完成  model=${model}`);
  }, ORDER_FRAME_INTERVAL_MS);
  res.on('close', () => clearInterval(timer));
}

/**
 * 顺序场景的非流式对照。
 *
 * <p>非流式**没有「顺序」可言** —— 正文与 `tool_calls` 同在一个 message 对象里。
 * 保留入口是为了确认「下游在非流式下能正常执行工具」，从而排除顺序之外的因素。
 */
function sendOrderedNonStream(res, model, toolFirst) {
  const label = toolFirst ? 'baseline-tool-first' : 'baseline-content-first';
  const body = completionBody(
    newId(), model,
    {
      role: 'assistant',
      content: CONTENT_PIECES.join(''),
      tool_calls: [{
        id: TOOL_ID,
        type: 'function',
        function: { name: TOOL_NAME, arguments: TOOL_ARG_JSON },
      }],
    },
    'tool_calls',
    { prompt_tokens: 24, completion_tokens: 18, total_tokens: 42 },
  );
  sendJson(res, 200, body, `${label} 已返回（非流式 content 顺序即场景顺序）`, model);
}

/** 纯工具调用，无正文。`baseline-tool-call` 与 `pass-tool-call` 共用（两者形状相同、期望不同）。 */
function sendToolCallNonStream(res, model, label) {
  const body = completionBody(
    newId(), model,
    {
      role: 'assistant',
      content: null,
      tool_calls: [{
        id: 'call_mock_1',
        type: 'function',
        function: { name: 'get_weather', arguments: '{"city":"Hangzhou"}' },
      }],
    },
    'tool_calls',
    { prompt_tokens: 31, completion_tokens: 18, total_tokens: 49 },
  );
  sendJson(res, 200, body, `${label} 已返回纯工具调用`, model);
}

function writeToolCallStream(res, model, label) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, chunkEnvelope(id, model, {
    role: 'assistant',
    tool_calls: [{
      index: 0,
      id: 'call_mock_1',
      type: 'function',
      function: { name: 'get_weather', arguments: '{}' },
    }],
  }));
  writeDataFrame(res, finishFrame(id, model, 'tool_calls'));
  writeDataFrame(res, '[DONE]');
  res.end();
  log(`✓ ${label} 已返回纯工具调用流  model=${model}`);
}

/* ── blank：期望判空并兜底重试 ────────────────────────── */

/** blank-empty-content 流式：200 + 仅 role / finish / [DONE]，无任何实质载荷。 */
function writeBlankContentStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  writeDataFrame(res, finishFrame(id, model));
  writeDataFrame(res, '[DONE]');
  res.end();
  log(`✓ blank-empty-content 已返回空流（role+finish+[DONE]，无内容）  model=${model}`);
}

/** blank-empty-content 非流式：结构完整但 content 为空串。 */
function sendBlankContentNonStream(res, model) {
  const body = completionBody(
    newId(), model,
    { role: 'assistant', content: '' },
    'stop',
    { prompt_tokens: 26, completion_tokens: 0, total_tokens: 26 },
  );
  sendJson(res, 200, body, 'blank-empty-content 已返回空正文', model);
}

/** blank-zero-usage 流式：空流但收尾 chunk 带全 0 usage。 */
function writeBlankZeroUsageStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  writeDataFrame(res, {
    ...finishFrame(id, model),
    usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
  });
  writeDataFrame(res, '[DONE]');
  res.end();
  log(`✓ blank-zero-usage 已返回空流 + 全 0 usage  model=${model}`);
}

/** blank-zero-usage 非流式：空正文 + 全 0 usage。 */
function sendBlankZeroUsageNonStream(res, model) {
  const body = completionBody(
    newId(), model,
    { role: 'assistant', content: '' },
    'stop',
    { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
  );
  sendJson(res, 200, body, 'blank-zero-usage 已返回空正文 + 全 0 usage', model);
}

/** blank-empty-choices：200 + `choices` 为空数组（仅非流式有意义）。 */
function sendBlankChoicesNonStream(res, model) {
  const body = completionBody(newId(), model, null, null, null);
  sendJson(res, 200, body, 'blank-empty-choices 已返回空 choices 数组', model);
}

/* ── pass：期望放行 ───────────────────────────────────── */

/**
 * pass-reasoning-only：只有 `reasoning_content`，没有 `content`。
 *
 * <p>对照组 —— 思考链也是实质载荷，不该判空。非流式侧顺带验证 reasoning fallback
 * （只有思考链无正文时，清洗阶段应把思考内容填进 `content`）。
 *
 * <p>流式侧是本次合并**补上的缺口**：原 `mock-upstream` 全文没有任何 reasoning
 * 帧构造，思考链的流式路径一直没法物理复现（`mock-nonstream` 的 README 已记录这一点）。
 */
const REASONING_TEXT = '让我想想……这个请求需要先确认判定口径，再决定返回什么形态。';

function writeReasoningOnlyStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  writeDataFrame(res, chunkEnvelope(id, model, { reasoning_content: REASONING_TEXT }));
  writeDataFrame(res, finishFrame(id, model));
  writeDataFrame(res, '[DONE]');
  res.end();
  log(`✓ pass-reasoning-only 已返回纯思考链流  model=${model}`);
}

function sendReasoningOnlyNonStream(res, model) {
  const body = completionBody(
    newId(), model,
    { role: 'assistant', content: '', reasoning_content: REASONING_TEXT },
    'stop',
    { prompt_tokens: 26, completion_tokens: 33, total_tokens: 59 },
  );
  sendJson(res, 200, body, 'pass-reasoning-only 已返回纯思考链', model);
}

/**
 * pass-malformed：200 + 残缺 JSON。
 *
 * <p>检验判定器「解析失败一律保守放行」这条口径：宁可放行一个没见过的格式，
 * 也不要因为结构陌生就判空重试。
 */
function sendMalformedNonStream(res, model) {
  const raw = `{"id":"${newId()}","object":"chat.completion","choices":[{"index":0,"message":{"role":"assist`;
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
  log(`✓ pass-malformed 已返回残缺 JSON（${Buffer.byteLength(raw)} 字节）  model=${model}`);
}

function writeMalformedStream(res, model) {
  writeSseHead(res);
  // 保留 data 前缀但让 JSON 残缺：COSP 解析失败必须保守放行。
  res.write('data: {"id":"chatcmpl-mock","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"te\n\n');
  res.end();
  log(`✓ pass-malformed 已返回残缺 chunk JSON  model=${model}`);
}

/**
 * pass-sse-body：无视 `stream=false`，仍返回 `text/event-stream`（仅非流式有意义）。
 *
 * <p>最现实的一档：不认 `stream` 参数的中转站相当常见。COSP 的非流式路径用
 * `.toEntity(String.class)` 收响应体，会把整段 SSE 文本当成 body 原样透传给下游 ——
 * 下游拿到的是一堆 `data: {...}` 而不是一个 JSON 对象。
 */
function sendSseBodyNonStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, chunkEnvelope(id, model, { role: 'assistant', content: '我无视了 stream=false。' }));
  writeDataFrame(res, finishFrame(id, model));
  writeDataFrame(res, '[DONE]');
  res.end();
  log(`✓ pass-sse-body 已用 SSE 回应非流式请求  model=${model}`);
}

/* ── lifecycle：流式专属的相位与停滞 ──────────────────── */

/** phase-done-early：发完 `[DONE]` 但保持 TCP 不关闭（验证 Layer 1 先收尾）。 */
function writeDoneEarlyStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  let i = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (i < NORMAL_CHUNK_COUNT) {
      writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
      i += 1;
      return;
    }
    clearInterval(timer);
    writeDataFrame(res, finishFrame(id, model));
    writeDataFrame(res, '[DONE]');
    // 关键：发完 [DONE] 后不调用 res.end()，模拟 keep-alive 不关连接。
    log(`… phase-done-early 已发 [DONE]，保持连接不关  model=${model}`);
  }, NORMAL_CHUNK_INTERVAL_MS);
  res.on('close', () => clearInterval(timer));
}

/** phase-eof-fallback：发完内容直接关连接、不发 `[DONE]`（验证 Layer 2 兜底）。 */
function writeEofFallbackStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  let i = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (i < NORMAL_CHUNK_COUNT) {
      writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
      i += 1;
      return;
    }
    clearInterval(timer);
    writeDataFrame(res, finishFrame(id, model));
    res.end();
    log(`✓ phase-eof-fallback 已关连接（无 [DONE]）  model=${model}`);
  }, NORMAL_CHUNK_INTERVAL_MS);
  res.on('close', () => clearInterval(timer));
}

/** cancel-stall：吐 K 个 chunk 后永久停滞，且不关连接。 */
function writeStallStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS) {
      writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
      i += 1;
      setTimeout(pump, NORMAL_CHUNK_INTERVAL_MS);
      return;
    }
    log(`… cancel-stall 永久停滞（不关连接）  model=${model}`);
    // 什么都不做：连接保持打开，永不再吐 chunk，也不 end。
  }
  pump();
}

/**
 * cancel-stall-delayed：先静默若干秒，再吐 K 个 chunk，然后永久停滞。
 *
 * <p>与 cancel-stall 的区别只在前半段：那个立刻吐首字，本场景刻意把
 * 「等待首字」与「产出后停滞」两个阶段都拉长到可观察，验证两阶段下右键断连都可用。
 */
function writeStallDelayedStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeCommentFrame(res);
  log(`… cancel-stall-delayed 已建立连接，${STALL_DELAYED_LEAD_MS / 1000}s 后才吐首字  model=${model}`);

  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS) {
      writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
      i += 1;
      const t = setTimeout(pump, NORMAL_CHUNK_INTERVAL_MS);
      res.on('close', () => clearTimeout(t));
      return;
    }
    log(`… cancel-stall-delayed 永久停滞（不关连接）  model=${model}`);
  }

  const lead = setTimeout(() => {
    if (res.writableEnded) return;
    writeDataFrame(res, roleFrame(id, model));
    pump();
  }, STALL_DELAYED_LEAD_MS);
  res.on('close', () => clearTimeout(lead));
}

/** cancel-stall-resume：吐 K 个 chunk → 停滞若干秒 → 恢复并正常收尾。 */
function writeStallResumeStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
    i += 1;
    if (i === STALL_AFTER_CHUNKS) {
      log(`… cancel-stall-resume 停滞 ${STALL_RESUME_MS / 1000}s  model=${model}`);
      const t = setTimeout(() => {
        log(`↻ cancel-stall-resume 恢复  model=${model}`);
        resume();
      }, STALL_RESUME_MS);
      res.on('close', () => clearTimeout(t));
      return;
    }
    setTimeout(pump, NORMAL_CHUNK_INTERVAL_MS);
  }
  function resume() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS + NORMAL_CHUNK_COUNT) {
      writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
      i += 1;
      setTimeout(resume, NORMAL_CHUNK_INTERVAL_MS);
      return;
    }
    writeDataFrame(res, finishFrame(id, model));
    writeDataFrame(res, '[DONE]');
    res.end();
    log(`✓ cancel-stall-resume 完成  model=${model}`);
  }
  pump();
}

/** phase-slow 之外的慢速持续推流：每 3s 一个 chunk，持续较久。 */
function writeSlowSteadyStream(res, model) {
  const id = newId();
  writeSseHead(res);
  writeDataFrame(res, roleFrame(id, model));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < SLOW_STEADY_CHUNK_COUNT) {
      writeDataFrame(res, contentFrame(id, model, makeChunkText(i)));
      i += 1;
      setTimeout(pump, SLOW_STEADY_INTERVAL_MS);
      return;
    }
    writeDataFrame(res, finishFrame(id, model));
    writeDataFrame(res, '[DONE]');
    res.end();
    log(`✓ phase-slow-steady 完成  model=${model}`);
  }
  pump();
}

/* ── 场景表 ───────────────────────────────────────────── */

/**
 * 本协议的场景注册表。
 *
 * <p>每个场景声明 `stream` 与 `nonstream` 两个入口；某个模式没有实现时**不写该键**，
 * 入口会据此回 422 并说明原因（见 `lib/http.js` 的 `sendUnsupported`）。
 */
function scenarios() {
  return {
    /* ── baseline ── */
    'baseline-normal': {
      desc: '正常响应，完整内容 + 结束标记 + 关连接',
      stream: (res, model) => writeNormalStream(res, model),
      nonstream: (res, model) => sendNormalNonStream(res, model),
    },
    'baseline-tool-call': {
      desc: '纯工具调用、无正文（后端应原样透传，下游能执行工具）',
      stream: (res, model) => writeToolCallStream(res, model, 'baseline-tool-call'),
      nonstream: (res, model) => sendToolCallNonStream(res, model, 'baseline-tool-call'),
    },
    'baseline-content-first': {
      desc: '正文在前、工具调用在后（顺序基准：已知下游可正常执行工具）',
      stream: (res, model) => writeOrderedStream(res, model, false),
      nonstream: (res, model) => sendOrderedNonStream(res, model, false),
    },
    'baseline-tool-first': {
      desc: '工具调用在前、正文在后（顺序基准的另一半）',
      stream: (res, model) => writeOrderedStream(res, model, true),
      nonstream: (res, model) => sendOrderedNonStream(res, model, true),
    },

    /* ── blank：期望判空并兜底重试 ── */
    'blank-empty-content': {
      desc: '响应结构合法但内容为空（应判空 → 兜底重试）',
      stream: (res, model) => writeBlankContentStream(res, model),
      nonstream: (res, model) => sendBlankContentNonStream(res, model),
    },
    'blank-empty-body': {
      desc: '200 + 0 字节 body（应判空 → 兜底重试）',
      stream: (res, model) => {
        writeSseHead(res);
        // 关键：写头后立即 end，0 个 SSE data 帧。
        res.end();
        log(`✓ blank-empty-body 已返回 200 空 body（0 帧）  model=${model}`);
      },
      nonstream: (res, model) => sendEmptyBody(res, model),
    },
    'blank-empty-choices': {
      desc: '200 + choices 为空数组（应判空 → 兜底重试）',
      nonstream: (res, model) => sendBlankChoicesNonStream(res, model),
    },
    'blank-zero-usage': {
      desc: '内容为空且 usage 全 0（usage 不是判据，仍应判空）',
      stream: (res, model) => writeBlankZeroUsageStream(res, model),
      nonstream: (res, model) => sendBlankZeroUsageNonStream(res, model),
    },

    /* ── pass：期望放行（对照组） ── */
    'pass-tool-call': {
      desc: '纯工具调用、无正文（工具调用是实质载荷，不该判空）',
      stream: (res, model) => writeToolCallStream(res, model, 'pass-tool-call'),
      nonstream: (res, model) => sendToolCallNonStream(res, model, 'pass-tool-call'),
    },
    'pass-reasoning-only': {
      desc: '只有思考链、无正文（思考链是实质载荷；非流式应触发 fallback 填入正文）',
      stream: (res, model) => writeReasoningOnlyStream(res, model),
      nonstream: (res, model) => sendReasoningOnlyNonStream(res, model),
    },
    'pass-malformed': {
      desc: '残缺 JSON（解析失败应保守放行，不判空）',
      stream: (res, model) => writeMalformedStream(res, model),
      nonstream: (res, model) => sendMalformedNonStream(res, model),
    },
    'pass-sse-body': {
      desc: '无视 stream=false 仍回 SSE（非流式路径应原样透传文本，不判空）',
      nonstream: (res, model) => sendSseBodyNonStream(res, model),
    },

    /* ── lifecycle：流式专属的相位与停滞 ── */
    'phase-done-early': {
      desc: '发完 [DONE] 但保持 TCP 不关（COMPLETED 不应等连接关闭）',
      stream: (res, model) => writeDoneEarlyStream(res, model),
    },
    'phase-eof-fallback': {
      desc: '发完内容直接关连接、不发 [DONE]（TCP 关闭应兜底完成）',
      stream: (res, model) => writeEofFallbackStream(res, model),
    },
    'phase-slow-steady': {
      desc: '每 3s 一个 chunk，持续较久（Toast 计数应持续更新）',
      stream: (res, model) => writeSlowSteadyStream(res, model),
    },
    'cancel-stall': {
      desc: '吐若干 chunk 后永久停滞（停滞期右键断连 → ABORTED）',
      stream: (res, model) => writeStallStream(res, model),
    },
    'cancel-stall-delayed': {
      desc: '延迟 5s 才吐首字，随后永久停滞（两个阶段都应可断连）',
      stream: (res, model) => writeStallDelayedStream(res, model),
    },
    'cancel-stall-resume': {
      desc: '吐若干 chunk 后停滞 35s 再恢复（停滞期可断连；恢复后继续）',
      stream: (res, model) => writeStallResumeStream(res, model),
    },

    /* ── 协议无关的传输层场景（实现来自 lib/transport.js） ── */
    ...sharedScenarios({
      renderErrorBody,
      newId,
      writeNormalStream: (res, model) => writeNormalStream(res, model),
      sendNormalNonStream,
      serializeNormalBody,
    }),
  };
}

/** 该协议的错误体渲染（供入口在 422 / 404 等场景复用）。 */
module.exports = {
  id: 'chat',
  attachLifecycleLogs,
  log,
  renderErrorBody,
  scenarios,
};
