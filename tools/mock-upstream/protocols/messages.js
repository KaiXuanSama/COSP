#!/usr/bin/env node
'use strict';

/**
 * Anthropic Messages 协议（`POST /messages`）的场景实现。
 *
 * <p>本文件只负责「帧长什么样」—— 协议无关的传输层病态行为由 `lib/transport.js` 提供。
 *
 * <h2>为何本协议不按模式拆两个文件</h2>
 * 与 OpenAI 侧的做法刻意不同，原因是协议本身：Chat 的流式与非流式是**两个形状**
 * （`chat.completion.chunk` vs `chat.completion`），而 Anthropic 两种模式共用同一端点、
 * 只是响应形态不同（事件流 vs 单个 `message`）。拆开会让「同一场景在两种模式下的对照」
 * 跨文件，而那正是最常要比对的东西。
 */

const {
  attachLifecycleLogs,
  log,
  sendEmptyBody,
  sendJson,
  writeCommentFrame,
  writeEventFrame,
  writeSseHead,
} = require('../lib/http');
const { sharedScenarios } = require('../lib/transport');

/* ── 可调参数 ─────────────────────────────────────────── */

/** 正常流的 delta 间隔（毫秒）。 */
const NORMAL_DELTA_INTERVAL_MS = 120;
/** 正常流的 delta 数（快照与恢复场景共用）。 */
const NORMAL_DELTA_COUNT = 30;
/** cancel-stall-* 停滞前先吐的 delta 数。 */
const STALL_AFTER_CHUNKS = 5;
/** cancel-stall-resume 中途停滞时长（毫秒）。 */
const STALL_RESUME_MS = 35 * 1000;
/** cancel-stall-delayed 首字之前的延迟（毫秒）。 */
const STALL_DELAYED_LEAD_MS = 5 * 1000;
/** phase-slow-steady 的 delta 间隔（毫秒）。 */
const SLOW_STEADY_INTERVAL_MS = 3 * 1000;
/** phase-slow-steady 的 delta 数。 */
const SLOW_STEADY_DELTA_COUNT = 8;

const NORMAL_TEXT = '这是一条来自 mock-upstream 的完整回复，用于验证 COSP 的 Anthropic Messages 路径。';

/* ── 帧构造 ───────────────────────────────────────────── */

function messageId() {
  return `msg_mock_${Date.now()}`;
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

/** Anthropic 风格错误体：比 OpenAI 多一层 `type: 'error'` 外壳。 */
function renderErrorBody(status, message) {
  return { type: 'error', error: { type: 'mock_error', code: status, message } };
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

/** 可指定 id / name 的工具块声明。id 必须各不相同，否则多轮对话会串线。 */
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

function signatureDelta(index, signature) {
  return { type: 'content_block_delta', index, delta: { type: 'signature_delta', signature } };
}

function inputJsonDelta(index, partialJson) {
  return { type: 'content_block_delta', index, delta: { type: 'input_json_delta', partial_json: partialJson } };
}

function blockStop(index) {
  return { type: 'content_block_stop', index };
}

/**
 * 收尾事件。
 *
 * <p>Anthropic 的真实 `message_delta` 通常只报最终 `output_tokens`；
 * **不要**默认塞 `input_tokens: 0` —— 那会让 COSP 的 merge 把 `message_start`
 * 的真实输入 token 覆盖掉，于是 mock 测到的是自己制造的假象。
 * 只有空 usage 对照场景才显式要求全 0。
 */
function messageDelta(outputTokens, stopReason = 'end_turn', includeZeroInput = false) {
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

/* ── 正常响应 ─────────────────────────────────────────── */

function writeNormalStream(res, model, onDone) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model));
  writeEventFrame(res, textBlockStart(0));
  const parts = ['这是一条来自 ', 'mock-upstream ', '的流式回复，', '用于验证 COSP ', '对 Anthropic ', '事件序列的原样透传。'];
  let index = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (index < parts.length) {
      writeEventFrame(res, textDelta(0, parts[index]));
      index += 1;
      return;
    }
    clearInterval(timer);
    writeEventFrame(res, blockStop(0));
    writeEventFrame(res, messageDelta(36));
    writeEventFrame(res, messageStop());
    res.end();
    log(`✓ baseline-normal 流完成  model=${model}  deltas=${parts.length}`);
    if (onDone) onDone();
  }, NORMAL_DELTA_INTERVAL_MS);
  res.on('close', () => clearInterval(timer));
}

function sendNormalNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [{ type: 'text', text: NORMAL_TEXT }]),
    'baseline-normal 已返回完整 message', model);
}

/** 正常非流式响应体的完整文本，供截断场景使用。 */
function serializeNormalBody(model) {
  return JSON.stringify(messageBody(messageId(), model, [{ type: 'text', text: NORMAL_TEXT }]));
}

/* ── baseline：常规形态 ───────────────────────────────── */

/** baseline-thinking-text：thinking + text 双 block（真实上游常见形态）。 */
function writeThinkingTextStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 42));
  writeEventFrame(res, thinkingBlockStart(0));
  writeEventFrame(res, thinkingDelta(0, '先分析用户的需求。'));
  writeEventFrame(res, thinkingDelta(0, '然后给出清晰答案。'));
  writeEventFrame(res, signatureDelta(0, 'sig_mock'));
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, textBlockStart(1));
  writeEventFrame(res, textDelta(1, '这是经过思考后的正文回答。'));
  writeEventFrame(res, blockStop(1));
  writeEventFrame(res, messageDelta(28));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ baseline-thinking-text 流完成  model=${model}`);
}

function sendThinkingTextNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'thinking', thinking: '先检查事件序列，再给出回答。', signature: 'sig_mock' },
    { type: 'text', text: '这是带思考链的正文回答。' },
  ], 'end_turn', usage(42, 28)), 'baseline-thinking-text 已返回 thinking + text', model);
}

/** baseline-tool-call：工具调用（`tool_use` 在 block_start 时已是实质载荷）。 */
function writeToolCallStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 31));
  writeEventFrame(res, namedToolBlockStart(0, 'toolu_mock_1', 'get_weather'));
  writeEventFrame(res, inputJsonDelta(0, '{"city":"Hang'));
  writeEventFrame(res, inputJsonDelta(0, 'zhou"}'));
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, messageDelta(14, 'tool_use'));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ baseline-tool-call 流完成  model=${model}`);
}

function sendToolCallNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'tool_use', id: 'toolu_mock_1', name: 'get_weather', input: { city: 'Hangzhou' } },
  ], 'tool_use', usage(31, 14)), 'baseline-tool-call 已返回纯工具调用', model);
}

/* ── baseline：工具调用与正文的顺序 ───────────────────── */

/**
 * 与 Chat 侧同义的同名场景。两个端点**同名同义**，便于比对同一顺序在两种协议下的
 * 下游行为差异 —— 这正是原 `mock-toolorder` 想做的事，合并后不再需要单独一个服务。
 *
 * <p>Anthropic 的 block index 覆盖**所有**块类型，因此顺序不同则 index 不同：
 * 这是「稀疏 index」问题的同构场景（任何把 block index 直接当 OpenAI
 * `tool_calls[].index` 用的实现，在这里都会产出稀疏数组）。
 */
const ORDER_CONTENT_PIECES = ['我来看一下', '这个文件的内容。'];
const ORDER_TOOL_NAME = 'read_file';
const ORDER_TOOL_ID = 'call_mock_1';
const ORDER_TOOL_ARG_JSON = JSON.stringify({
  filePath: process.env.MOCK_UPSTREAM_FILE || 'README.md',
  startLine: 1,
  endLine: 40,
});
const ORDER_TOOL_ARG_PIECES = [
  ORDER_TOOL_ARG_JSON.slice(0, Math.floor(ORDER_TOOL_ARG_JSON.length / 2)),
  ORDER_TOOL_ARG_JSON.slice(Math.floor(ORDER_TOOL_ARG_JSON.length / 2)),
];
/** 顺序场景的帧间隔：调大可在客户端 UI 上肉眼看清顺序。 */
const ORDER_FRAME_INTERVAL_MS = Number(process.env.MOCK_UPSTREAM_INTERVAL_MS || 120);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function writeOrderedStream(res, model, toolFirst) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 24));
  await sleep(ORDER_FRAME_INTERVAL_MS);

  /** 发送 text block。index 由调用方按实际顺序给出。 */
  async function text(index) {
    writeEventFrame(res, textBlockStart(index));
    await sleep(ORDER_FRAME_INTERVAL_MS);
    for (const piece of ORDER_CONTENT_PIECES) {
      writeEventFrame(res, textDelta(index, piece));
      await sleep(ORDER_FRAME_INTERVAL_MS);
    }
    writeEventFrame(res, blockStop(index));
    await sleep(ORDER_FRAME_INTERVAL_MS);
  }

  /** 发送 tool_use block，参数分两片走 input_json_delta。 */
  async function tool(index) {
    writeEventFrame(res, namedToolBlockStart(index, ORDER_TOOL_ID, ORDER_TOOL_NAME));
    await sleep(ORDER_FRAME_INTERVAL_MS);
    for (const piece of ORDER_TOOL_ARG_PIECES) {
      writeEventFrame(res, inputJsonDelta(index, piece));
      await sleep(ORDER_FRAME_INTERVAL_MS);
    }
    writeEventFrame(res, blockStop(index));
    await sleep(ORDER_FRAME_INTERVAL_MS);
  }

  if (toolFirst) {
    await tool(0);
    await text(1);
  } else {
    await text(0);
    await tool(1);
  }

  writeEventFrame(res, messageDelta(18, 'tool_use'));
  await sleep(ORDER_FRAME_INTERVAL_MS);
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ ${toolFirst ? 'baseline-tool-first' : 'baseline-content-first'} 流完成  model=${model}`);
}

/** 非流式：content 数组的顺序就是场景顺序，可直接观察下游是否按序处理。 */
function sendOrderedNonStream(res, model, toolFirst) {
  const textBlock = { type: 'text', text: ORDER_CONTENT_PIECES.join('') };
  const toolBlock = {
    type: 'tool_use', id: ORDER_TOOL_ID, name: ORDER_TOOL_NAME,
    input: JSON.parse(ORDER_TOOL_ARG_JSON),
  };
  sendJson(res, 200, messageBody(
    messageId(), model,
    toolFirst ? [toolBlock, textBlock] : [textBlock, toolBlock],
    'tool_use', usage(24, 18),
  ), 'baseline-tool-first 已返回（非流式 content 数组顺序即场景顺序）', model);
}

/* ── translate：跳协议翻译（仅 MESSAGES 有这些形状） ──── */

/**
 * 参数按固定长度切片，**刻意不避开转义序列**。
 *
 * <p>不避开是重点：`\"` 与 `\uXXXX` 被拦腰截断，是最容易暴露「谁在中途试图解析单片」
 * 这类缺陷的形状。正确实现应把每片当作**不透明字节**原样转发，只在下游完整拼接后
 * 才有 JSON 语义。
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
 * translate-tool-split-args：单工具、参数切成大量小片。
 *
 * <p>期望的下游形态：一个带 `name` 的帧（`arguments` 为空串），随后 N 个只带
 * `arguments` 的帧，全部落在 `tool_calls[0].index === 0`，按序拼接后等于参数原文。
 * 每片 12 字符以保证转义序列被截断，且分片数 30+。
 */
function writeToolSplitArgsStream(res, model) {
  const id = messageId();
  const args = escapeHeavyArgs();
  const parts = sliceJson(args, 12);
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 57));
  writeEventFrame(res, namedToolBlockStart(0, 'toolu_split_1', 'create_file'));
  for (const part of parts) {
    writeEventFrame(res, inputJsonDelta(0, part));
  }
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, messageDelta(96, 'tool_use'));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ translate-tool-split-args 流完成  model=${model}  参数 ${args.length} 字符切成 ${parts.length} 片`);
}

function sendToolSplitArgsNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'tool_use', id: 'toolu_split_1', name: 'create_file', input: JSON.parse(escapeHeavyArgs()) },
  ], 'tool_use', usage(57, 96)), 'translate-tool-split-args 已返回完整 input（非流式无分片）', model);
}

/**
 * translate-tool-multi-split：三个工具各自分片，且 **block index 从 1 开始**（0 给 thinking）。
 *
 * <p>这是 tool index 双索引域最容易错的形状：Anthropic 的 block index 是 1/2/3，
 * 而 OpenAI 的 `tool_calls[].index` 必须是稠密的 0/1/2。直接透传会产出从 1 起的
 * 稀疏数组，下游拼不出第一个工具。
 */
function writeToolMultiSplitStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 63));

  // index 0 留给 thinking，把工具挤到 1/2/3。
  writeEventFrame(res, thinkingBlockStart(0));
  writeEventFrame(res, thinkingDelta(0, '需要连续调用三个工具。'));
  writeEventFrame(res, signatureDelta(0, 'sig_multi'));
  writeEventFrame(res, blockStop(0));

  const tools = [
    { blockIndex: 1, toolId: 'toolu_multi_a', name: 'create_directory', args: JSON.stringify({ dirPath: 'd:\\tmp\\alpha' }) },
    { blockIndex: 2, toolId: 'toolu_multi_b', name: 'create_file', args: escapeHeavyArgs() },
    { blockIndex: 3, toolId: 'toolu_multi_c', name: 'run_in_terminal', args: JSON.stringify({ command: 'Get-ChildItem -Path "d:\\tmp" -Recurse', mode: 'sync' }) },
  ];

  let sliceTotal = 0;
  for (const tool of tools) {
    writeEventFrame(res, namedToolBlockStart(tool.blockIndex, tool.toolId, tool.name));
    const parts = sliceJson(tool.args, 9);
    sliceTotal += parts.length;
    for (const part of parts) {
      writeEventFrame(res, inputJsonDelta(tool.blockIndex, part));
    }
    writeEventFrame(res, blockStop(tool.blockIndex));
  }

  writeEventFrame(res, messageDelta(184, 'tool_use'));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ translate-tool-multi-split 流完成  model=${model}  3 个工具共 ${sliceTotal} 片，block index 1..3`);
}

function sendToolMultiSplitNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'thinking', thinking: '需要连续调用三个工具。', signature: 'sig_multi' },
    { type: 'tool_use', id: 'toolu_multi_a', name: 'create_directory', input: { dirPath: 'd:\\tmp\\alpha' } },
    { type: 'tool_use', id: 'toolu_multi_b', name: 'create_file', input: JSON.parse(escapeHeavyArgs()) },
    { type: 'tool_use', id: 'toolu_multi_c', name: 'run_in_terminal', input: { command: 'Get-ChildItem -Path "d:\\tmp" -Recurse', mode: 'sync' } },
  ], 'tool_use', usage(63, 184)), 'translate-tool-multi-split 已返回三个工具', model);
}

/**
 * translate-tool-interleaved：两个工具的参数分片**交错**发送。
 *
 * <h2>为何测一个不规范的形状</h2>
 * Anthropic 官方文档描述的是块顺序完成（一个 block 的 delta 发完才开下一个），
 * 交错在实践中未观测到。但中转站会重排事件，且「按 block index 查表路由」这个实现
 * **本应**天然支持交错 —— 若某个实现偷懒用「当前活跃块」之类的隐式状态，
 * 交错就会把两个工具的参数搅在一起。
 *
 * <p>因此这是一个**实现健壮性**探针，而非协议合规性测试。
 */
function writeToolInterleavedStream(res, model) {
  const id = messageId();
  const first = JSON.stringify({ dirPath: 'd:\\tmp\\first-tool-path' });
  const second = JSON.stringify({ command: 'echo "second tool"', mode: 'async' });
  const firstParts = sliceJson(first, 8);
  const secondParts = sliceJson(second, 8);

  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 48));
  // 两个块都先声明，再交错发 delta。
  writeEventFrame(res, namedToolBlockStart(0, 'toolu_inter_a', 'create_directory'));
  writeEventFrame(res, namedToolBlockStart(1, 'toolu_inter_b', 'run_in_terminal'));
  const rounds = Math.max(firstParts.length, secondParts.length);
  for (let i = 0; i < rounds; i += 1) {
    if (i < firstParts.length) writeEventFrame(res, inputJsonDelta(0, firstParts[i]));
    if (i < secondParts.length) writeEventFrame(res, inputJsonDelta(1, secondParts[i]));
  }
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, blockStop(1));
  writeEventFrame(res, messageDelta(72, 'tool_use'));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ translate-tool-interleaved 流完成  model=${model}  ${firstParts.length}+${secondParts.length} 片交错`);
}

function sendToolInterleavedNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'tool_use', id: 'toolu_inter_a', name: 'create_directory', input: { dirPath: 'd:\\tmp\\first-tool-path' } },
    { type: 'tool_use', id: 'toolu_inter_b', name: 'run_in_terminal', input: { command: 'echo "second tool"', mode: 'async' } },
  ], 'tool_use', usage(48, 72)), 'translate-tool-interleaved 已返回两个工具（非流式无交错）', model);
}

/**
 * translate-tool-no-args：`content_block_start` 之后**零个** `input_json_delta`。
 *
 * <p>真实存在的形态（无参工具，如 `get_current_time`）。下游应只收到那一个带 `name`
 * 的帧、`arguments` 为空串 —— 不能凭空补一个 `{}`，也不能因为「没有 delta」
 * 就把整个工具调用丢掉。
 */
function writeToolNoArgsStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 19));
  writeEventFrame(res, namedToolBlockStart(0, 'toolu_noargs_1', 'get_current_time'));
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, messageDelta(8, 'tool_use'));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ translate-tool-no-args 流完成  model=${model}  零个 input_json_delta`);
}

function sendToolNoArgsNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'tool_use', id: 'toolu_noargs_1', name: 'get_current_time', input: {} },
  ], 'tool_use', usage(19, 8)), 'translate-tool-no-args 已返回无参工具', model);
}

/**
 * translate-finish-reason：完整工具调用 + 一个**非 `tool_use`** 的 stop_reason。
 *
 * <p>这是 finish_reason 覆盖规则的复现器（响应侧契约第 8 节）。OpenAI 语义要求
 * 含完整 `tool_calls` 的响应把 `finish_reason` 报成 `tool_calls`，优先于
 * `length` / `stop`。Copilot 收到 `length` 会判定回答被截断，于是
 * **放弃执行已经拿到的完整工具调用**并结束对话 —— 且全链路无任何报错。
 *
 * <p>工具块在这里是**正常闭合**的（参数完整 + `content_block_stop`），所以「截断」
 * 这个结论只可能来自 stop_reason 的直译，而不是真的残缺。
 *
 * @param stopReason 收尾用的 stop_reason，刻意不是 `tool_use`
 */
function writeToolThenStopReasonStream(res, model, stopReason) {
  const id = messageId();
  const args = JSON.stringify({
    todoList: [
      { id: 1, status: 'in-progress', title: '定位图片兼容规则' },
      { id: 2, status: 'not-started', title: '汇总成因与方案' },
    ],
  });
  const parts = sliceJson(args, 16);

  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 309971));
  // 先给一段正文，与实测序列一致（正文 + 工具调用同时存在）。
  writeEventFrame(res, textBlockStart(0));
  writeEventFrame(res, textDelta(0, '先核对默认规则与执行顺序，确认在哪一步丢失。'));
  writeEventFrame(res, blockStop(0));
  // 工具块正常闭合：参数齐全，content_block_stop 到达。
  writeEventFrame(res, namedToolBlockStart(1, 'toolu_finish_reason', 'manage_todo_list'));
  for (const part of parts) {
    writeEventFrame(res, inputJsonDelta(1, part));
  }
  writeEventFrame(res, blockStop(1));
  writeEventFrame(res, messageDelta(209, stopReason));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ translate-finish-reason 流完成  model=${model}  stop_reason=${stopReason}  期望下游 finish_reason=tool_calls`);
}

function sendToolThenStopReasonNonStream(res, model, stopReason) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'text', text: '先核对默认规则与执行顺序。' },
    { type: 'tool_use', id: 'toolu_finish_reason', name: 'manage_todo_list',
      input: { todoList: [{ id: 1, status: 'in-progress', title: '定位图片兼容规则' }] } },
  ], stopReason, usage(309971, 209)),
  `translate-finish-reason 已返回 stop_reason=${stopReason}，期望下游 finish_reason=tool_calls`, model);
}

/* ── pass：期望放行 ───────────────────────────────────── */

/** pass-thinking-only：纯 thinking、无正文（对照组：纯思考是实质载荷）。 */
function writeThinkingOnlyStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 26));
  writeEventFrame(res, thinkingBlockStart(0));
  writeEventFrame(res, thinkingDelta(0, '这里只有思考链，但它仍是实质载荷。'));
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, messageDelta(18));
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ pass-thinking-only 流完成  model=${model}`);
}

function sendThinkingOnlyNonStream(res, model) {
  sendJson(res, 200, messageBody(messageId(), model, [
    { type: 'thinking', thinking: '这里只有思考链，它是实质载荷。', signature: 'sig_mock' },
  ], 'end_turn', usage(26, 18)), 'pass-thinking-only 已返回纯思考链', model);
}

/** pass-malformed：解析失败应保守放行，不判空。 */
function writeMalformedStream(res, model) {
  writeSseHead(res);
  // 故意保留 event 名、截断 data JSON。
  res.write('event: content_block_delta\ndata: {"type":"content_block_delta","delta":{"type":"text_delta","text":"te\n\n');
  res.end();
  log(`✓ pass-malformed 已返回残缺事件 JSON  model=${model}`);
}

function sendMalformedNonStream(res, model) {
  const raw = `{"id":"${messageId()}","type":"message","content":[{"type":"text","text":"te`;
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(raw) });
  res.end(raw);
  log(`✓ pass-malformed 已返回残缺 JSON  model=${model}`);
}

/* ── blank：期望判空并兜底重试 ────────────────────────── */

/**
 * `blank-empty-content`：信封合法但无任何实质载荷。
 *
 * @param includeZeroUsage 是否让收尾事件携带全 0 usage（`blank-zero-usage`）
 */
function writeBlankStream(res, model, includeZeroUsage) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model, 0));
  if (includeZeroUsage) {
    writeEventFrame(res, messageDelta(0, 'end_turn', true));
  }
  writeEventFrame(res, messageStop());
  res.end();
  log(`✓ blank-empty-content 已返回空事件流${includeZeroUsage ? ' + 全 0 usage' : ''}  model=${model}`);
}

function sendBlankNonStream(res, model, includeZeroUsage) {
  sendJson(res, 200, messageBody(messageId(), model, [], 'end_turn',
    includeZeroUsage ? usage(0, 0) : usage(24, 0)),
  `blank-empty-content 已返回空 content${includeZeroUsage ? ' + 全 0 usage' : ''}`, model);
}

/* ── lifecycle：流式专属 ─────────────────────────────── */

/** phase-done-early：发完 message_stop 但保持 TCP 不关闭。 */
function writeDoneEarlyStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model));
  writeEventFrame(res, textBlockStart(0));
  const parts = ['这是内容 ', '已发完但连接 ', '保持不关。'];
  let i = 0;
  for (const part of parts) {
    writeEventFrame(res, textDelta(0, part));
    i += 1;
  }
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, messageDelta(20));
  writeEventFrame(res, messageStop());
  // 关键：不 res.end()，模拟 keep-alive 不关连接。
  log(`… phase-done-early 已发 message_stop（${i} 个 delta），保持连接不关  model=${model}`);
}

/** phase-eof-fallback：发完内容直接关连接、不发 message_stop。 */
function writeEofFallbackStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model));
  writeEventFrame(res, textBlockStart(0));
  writeEventFrame(res, textDelta(0, '这是内容，但没有 message_stop。'));
  writeEventFrame(res, blockStop(0));
  writeEventFrame(res, messageDelta(20));
  res.end();
  log(`✓ phase-eof-fallback 已关连接（无 message_stop）  model=${model}`);
}

/** phase-slow-steady：每 3s 一个 delta，持续较久。 */
function writeSlowSteadyStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model));
  writeEventFrame(res, textBlockStart(0));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < SLOW_STEADY_DELTA_COUNT) {
      writeEventFrame(res, textDelta(0, makeChunkText(i)));
      i += 1;
      setTimeout(pump, SLOW_STEADY_INTERVAL_MS);
      return;
    }
    writeEventFrame(res, blockStop(0));
    writeEventFrame(res, messageDelta(20));
    writeEventFrame(res, messageStop());
    res.end();
    log(`✓ phase-slow-steady 完成  model=${model}`);
  }
  pump();
}

/** cancel-stall：吐若干 delta 后永久停滞，不关连接。 */
function writeStallStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model));
  writeEventFrame(res, textBlockStart(0));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS) {
      writeEventFrame(res, textDelta(0, makeChunkText(i)));
      i += 1;
      setTimeout(pump, NORMAL_DELTA_INTERVAL_MS);
      return;
    }
    log(`… cancel-stall 永久停滞（不关连接）  model=${model}`);
  }
  pump();
}

/** cancel-stall-delayed：先静默若干秒，再吐若干 delta，然后永久停滞。 */
function writeStallDelayedStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeCommentFrame(res);
  log(`… cancel-stall-delayed 已建立连接，${STALL_DELAYED_LEAD_MS / 1000}s 后才吐首字  model=${model}`);

  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS) {
      writeEventFrame(res, textDelta(0, makeChunkText(i)));
      i += 1;
      const t = setTimeout(pump, NORMAL_DELTA_INTERVAL_MS);
      res.on('close', () => clearTimeout(t));
      return;
    }
    log(`… cancel-stall-delayed 永久停滞（不关连接）  model=${model}`);
  }

  const lead = setTimeout(() => {
    if (res.writableEnded) return;
    writeEventFrame(res, messageStart(id, model));
    writeEventFrame(res, textBlockStart(0));
    pump();
  }, STALL_DELAYED_LEAD_MS);
  res.on('close', () => clearTimeout(lead));
}

/** cancel-stall-resume：吐若干 delta → 停滞 → 恢复并正常收尾。 */
function writeStallResumeStream(res, model) {
  const id = messageId();
  writeSseHead(res);
  writeEventFrame(res, messageStart(id, model));
  writeEventFrame(res, textBlockStart(0));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    writeEventFrame(res, textDelta(0, makeChunkText(i)));
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
    setTimeout(pump, NORMAL_DELTA_INTERVAL_MS);
  }
  function resume() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS + NORMAL_DELTA_COUNT) {
      writeEventFrame(res, textDelta(0, makeChunkText(i)));
      i += 1;
      setTimeout(resume, NORMAL_DELTA_INTERVAL_MS);
      return;
    }
    writeEventFrame(res, blockStop(0));
    writeEventFrame(res, messageDelta(36));
    writeEventFrame(res, messageStop());
    res.end();
    log(`✓ cancel-stall-resume 完成  model=${model}`);
  }
  pump();
}

function makeChunkText(i) {
  return `token${i} `;
}

/* ── 场景表 ───────────────────────────────────────────── */

function scenarios() {
  return {
    /* ── baseline ── */
    'baseline-normal': {
      desc: '正常响应，完整 events + 关连接',
      stream: (res, model) => writeNormalStream(res, model),
      nonstream: (res, model) => sendNormalNonStream(res, model),
    },
    'baseline-thinking-text': {
      desc: 'thinking 块 + text 块（真实上游常见形态，正文与思考都到达下游）',
      stream: (res, model) => writeThinkingTextStream(res, model),
      nonstream: (res, model) => sendThinkingTextNonStream(res, model),
    },
    'baseline-tool-call': {
      desc: '纯工具调用、无正文（后端应原样透传，下游能执行工具）',
      stream: (res, model) => writeToolCallStream(res, model),
      nonstream: (res, model) => sendToolCallNonStream(res, model),
    },
    'baseline-content-first': {
      desc: '正文在前、工具调用在后（顺序基准；block index 0/1）',
      stream: (res, model) => writeOrderedStream(res, model, false),
      nonstream: (res, model) => sendOrderedNonStream(res, model, false),
    },
    'baseline-tool-first': {
      desc: '工具调用在前、正文在后（顺序基准的另一半；block index 0/1 互换）',
      stream: (res, model) => writeOrderedStream(res, model, true),
      nonstream: (res, model) => sendOrderedNonStream(res, model, true),
    },

    /* ── translate：仅 MESSAGES 有这些形状 ── */
    'translate-tool-split-args': {
      desc: '单工具参数切成 30+ 小片，转义序列被拦腰截断（拼接后应为合法 JSON）',
      stream: (res, model) => writeToolSplitArgsStream(res, model),
      nonstream: (res, model) => sendToolSplitArgsNonStream(res, model),
    },
    'translate-tool-multi-split': {
      desc: '三工具各分片，block index 从 1 起（tool index 应稠密重映射为 0/1/2）',
      stream: (res, model) => writeToolMultiSplitStream(res, model),
      nonstream: (res, model) => sendToolMultiSplitNonStream(res, model),
    },
    'translate-tool-interleaved': {
      desc: '两工具参数分片交错发送（各自独立拼接，互不污染）',
      stream: (res, model) => writeToolInterleavedStream(res, model),
      nonstream: (res, model) => sendToolInterleavedNonStream(res, model),
    },
    'translate-tool-no-args': {
      desc: '工具无参数、零个 input_json_delta（不凭空补 {}，也不丢失工具）',
      stream: (res, model) => writeToolNoArgsStream(res, model),
      nonstream: (res, model) => sendToolNoArgsNonStream(res, model),
    },
    'translate-finish-reason': {
      desc: '完整工具调用 + stop_reason: max_tokens（finish_reason 必须是 tool_calls）',
      stream: (res, model) => writeToolThenStopReasonStream(res, model, 'max_tokens'),
      nonstream: (res, model) => sendToolThenStopReasonNonStream(res, model, 'max_tokens'),
    },
    'translate-finish-reason-nonstandard': {
      desc: '同上，但用非标 model_context_window_exceeded（线上实测形态）',
      stream: (res, model) => writeToolThenStopReasonStream(res, model, 'model_context_window_exceeded'),
      nonstream: (res, model) => sendToolThenStopReasonNonStream(res, model, 'model_context_window_exceeded'),
    },

    /* ── pass：期望放行（对照组） ── */
    'pass-thinking-only': {
      desc: '纯 thinking、无正文（思考链是实质载荷，不该判空）',
      stream: (res, model) => writeThinkingOnlyStream(res, model),
      nonstream: (res, model) => sendThinkingOnlyNonStream(res, model),
    },
    'pass-malformed': {
      desc: '残缺事件 JSON（解析失败应保守放行，不判空）',
      stream: (res, model) => writeMalformedStream(res, model),
      nonstream: (res, model) => sendMalformedNonStream(res, model),
    },

    /* ── blank：期望判空并兜底重试 ── */
    'blank-empty-content': {
      desc: '仅 message_start / message_stop，无实质载荷（应判空 → 兜底重试）',
      stream: (res, model) => writeBlankStream(res, model, false),
      nonstream: (res, model) => sendBlankNonStream(res, model, false),
    },
    'blank-empty-body': {
      desc: '200 + 0 字节 body（应判空 → 兜底重试）',
      stream: (res, model) => writeBlankStream(res, model, false),
      nonstream: (res, model) => sendEmptyBody(res, model),
    },
    'blank-zero-usage': {
      desc: '空内容 + 全 0 usage（usage 不是判据，仍应判空）',
      stream: (res, model) => writeBlankStream(res, model, true),
      nonstream: (res, model) => sendBlankNonStream(res, model, true),
    },

    /* ── lifecycle：流式专属 ── */
    'phase-done-early': {
      desc: '发完 message_stop 但保持 TCP 不关（COMPLETED 不应等连接关闭）',
      stream: (res, model) => writeDoneEarlyStream(res, model),
    },
    'phase-eof-fallback': {
      desc: '发完内容直接关连接、不发 message_stop（TCP 关闭应兜底完成）',
      stream: (res, model) => writeEofFallbackStream(res, model),
    },
    'phase-slow-steady': {
      desc: '每 3s 一个 delta，持续较久（Toast 计数应持续更新）',
      stream: (res, model) => writeSlowSteadyStream(res, model),
    },
    'cancel-stall': {
      desc: '吐若干 delta 后永久停滞（停滞期右键断连 → ABORTED）',
      stream: (res, model) => writeStallStream(res, model),
    },
    'cancel-stall-delayed': {
      desc: '延迟 5s 才吐首字，随后永久停滞（两个阶段都应可断连）',
      stream: (res, model) => writeStallDelayedStream(res, model),
    },
    'cancel-stall-resume': {
      desc: '吐若干 delta 后停滞 35s 再恢复（停滞期可断连；恢复后继续）',
      stream: (res, model) => writeStallResumeStream(res, model),
    },

    /* ── 协议无关的传输层场景 ── */
    ...sharedScenarios({
      renderErrorBody,
      writeNormalStream: (res, model) => writeNormalStream(res, model),
      sendNormalNonStream,
      serializeNormalBody,
    }),
  };
}

module.exports = {
  id: 'messages',
  attachLifecycleLogs,
  log,
  renderErrorBody,
  scenarios,
};
