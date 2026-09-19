#!/usr/bin/env node
'use strict';

/**
 * COSP 最小实现 Mock —— 下游请求头嗅探器。
 *
 * 目的：验证 GitHub Copilot 把本服务当作 Ollama 端点时，
 * 在发起 OpenAI 聊天请求（/v1/chat/completions）以及模型发现请求
 * （/api/tags、/api/show）时，**到底会不会带 Authorization 头**，
 * 以及带了哪些其它头。这决定了「下游 API Key」方案能否走标准 Bearer 认证。
 *
 * 与 tools/mock-upstream 的区别：
 *  - mock-upstream 模拟的是「上游供应商」，供 COSP 转发请求过去。
 *  - mock-cosp   模拟的是「COSP 自己」，供 Copilot 直接连接。
 *
 * 端点（对齐 COSP 对外契约）：
 *  - GET  /api/version           Ollama 版本探测
 *  - GET  /api/tags              模型发现（返回一个 mock 模型）
 *  - POST /api/show              模型能力查询（返回 mock 模型能力）
 *  - GET  /v1/models             OpenAI 模型列表（返回一个 mock 模型）
 *  - POST /v1/chat/completions   Chat 线路（顺序回复几个 chunk + [DONE]）
 *  - POST /v1/messages           Messages 线路（Anthropic 事件序列）
 *  - POST /v1/responses          Responses 线路（OpenAI Responses 事件序列）
 *
 * 三个聊天端点都只做「收下 → 打印 → 按该协议形态回包」，不做任何协议翻译：
 * 本 mock 的职责是嗅探下游真实发出的报文，翻译会把要观察的对象本身改动掉。
 * 因此各端点按各自协议原样应答 —— 下游拿到合法响应才会走完流程，
 * 否则它会在请求头打印出来之前就先报协议错断开。
 *
 * 每个请求都会在控制台完整打印请求方法、路径与全部请求头，
 * 重点观察 authorization 是否出现。
 *
 * 启动：node mock-cosp.js  （默认端口 11333，可用 MOCK_COSP_PORT 覆盖）
 * Copilot 可自定义 Ollama 端点地址，把它指向 http://localhost:11333 即可。
 */

const http = require('http');

// ── 可调参数 ─────────────────────────────────────────
const PORT = Number(process.env.MOCK_COSP_PORT || 11333);
/** mock 模型名（在 /api/tags、/v1/models 中暴露）。 */
const MODEL_NAME = 'mock-sniffer';
/** 聊天回复的 chunk 内容片段。 */
const REPLY_TOKENS = ['Hello', ' from', ' mock', ' COSP'];
/** chunk 之间的间隔（毫秒）。 */
const CHUNK_INTERVAL_MS = 120;
// ────────────────────────────────────────────────────

/** 简单时间戳日志。 */
function log(...args) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}]`, ...args);
}

/**
 * 完整打印一次请求的方法、路径与所有请求头。
 * 这是本 mock 的核心：把下游（Copilot）真实发来的头原样吐出，
 * 重点看 authorization / api-key / x-api-key 等认证相关字段。
 */
function dumpRequest(req) {
  log('──────────────────────────────────────────────');
  log(`▶ ${req.method} ${req.url}`);
  const headers = req.headers;
  const keys = Object.keys(headers).sort();
  for (const k of keys) {
    log(`    ${k}: ${headers[k]}`);
  }
  // 认证头单独高亮，方便一眼定位
  const authLike = keys.filter((k) => /auth|api[-_]?key|token|bearer/i.test(k));
  if (authLike.length > 0) {
    log(`  ★ 认证相关头: ${authLike.join(', ')}`);
  } else {
    log('  ★ 未发现任何认证相关头（authorization / api-key / token 等均缺失）');
  }
}

/** 读取请求体（用于打印 POST body，便于观察 model 字段等）。 */
function readBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
    });
    req.on('end', () => resolve(raw));
    req.on('error', () => resolve(raw));
  });
}

/** GET /api/version —— Ollama 版本探测。返回高版本号避免 Copilot 触发能力降级。 */
function handleVersion(req, res) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ version: '9.9.9' }));
}

/** GET /api/tags —— 模型发现，返回一个 mock 模型。 */
function handleTags(req, res) {
  const body = {
    models: [
      {
        name: MODEL_NAME,
        model: MODEL_NAME,
        modified_at: new Date().toISOString(),
        size: 0,
        digest: 'mock-digest',
        details: {
          parent_model: '',
          format: 'gguf',
          family: 'mock',
          families: ['mock'],
          parameter_size: '1B',
          quantization_level: 'Q4_0',
        },
      },
    ],
  };
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

/** POST /api/show —— 模型能力查询。 */
function handleShow(req, res) {
  const body = {
    license: 'mock',
    modelfile: '# mock',
    parameters: 'stop "<|im_end|>"',
    template: '{{ .Prompt }}',
    details: {
      parent_model: '',
      format: 'gguf',
      family: 'mock',
      families: ['mock'],
      parameter_size: '1B',
      quantization_level: 'Q4_0',
    },
    model_info: {
      'general.architecture': 'mock',
      'mock.context_length': 8192,
    },
    capabilities: ['completion', 'tools'],
  };
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

/** GET /v1/models —— OpenAI 模型列表。 */
function handleV1Models(req, res) {
  const body = {
    object: 'list',
    data: [
      {
        id: MODEL_NAME,
        object: 'model',
        created: Math.floor(Date.now() / 1000),
        owned_by: 'mock-cosp',
      },
    ],
  };
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

/** 构造一个 OpenAI 流式 content chunk 的 SSE 帧。 */
function contentFrame(id, model, delta) {
  const payload = {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: null }],
  };
  return `data: ${JSON.stringify(payload)}\n\n`;
}

/** 构造收尾 chunk（finish_reason: stop）。 */
function finishFrame(id, model) {
  const payload = {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
  };
  return `data: ${JSON.stringify(payload)}\n\n`;
}

/**
 * 构造一个带事件名的 SSE 帧。
 *
 * Messages 与 Responses 都用 `event: <type>` + `data: <json>` 两行形态
 * （Chat 只有裸 `data:`），因此两者共用这一个构造器。
 */
function typedFrame(type, payload) {
  return `event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`;
}

/** SSE 响应头 —— 三个聊天端点一致。 */
const SSE_HEADERS = {
  'Content-Type': 'text/event-stream; charset=utf-8',
  'Cache-Control': 'no-cache',
  Connection: 'keep-alive',
};

/**
 * 按固定间隔依次写出预构造好的帧，写完后执行收尾。
 *
 * 三个协议的流式回复共用这一段：帧内容各协议不同，但「间隔逐帧写、写完收尾、
 * 下游断连时清定时器」这套节奏完全一样，抄三份必然漂移。
 *
 * @param frames 已构造好的完整帧字符串数组
 * @param finish 最后写出的一帧（无额外收尾帧时传 null）
 * @param label  收尾日志的文案
 * @param onEnd  收尾回调（可选）
 */
function streamFrames(req, res, frames, finish, label, onEnd) {
  let i = 0;
  const timer = setInterval(() => {
    if (i < frames.length) {
      res.write(frames[i]);
      i += 1;
      return;
    }
    clearInterval(timer);
    if (finish) {
      res.write(finish);
    }
    res.end();
    log(`  ↳ ${label}，已关连接`);
    if (onEnd) {
      onEnd();
    }
  }, CHUNK_INTERVAL_MS);

  // 下游断连时清理定时器，避免往已关闭的连接继续写
  req.on('close', () => {
    clearInterval(timer);
  });
}

/**
 * POST /v1/chat/completions —— 顺序回复几个 chunk + [DONE]。
 * 无论下游 stream 与否，这里都用 SSE 流式回复（Copilot 默认走流式）。
 */
function handleChat(req, res, bodyRaw) {
  let model = MODEL_NAME;
  try {
    const parsed = JSON.parse(bodyRaw || '{}');
    if (parsed.model) model = parsed.model;
  } catch {
    // 忽略解析失败，用默认模型名
  }

  const id = `chatcmpl-mock-${Date.now()}`;
  res.writeHead(200, SSE_HEADERS);

  // 首帧带 role
  res.write(contentFrame(id, model, { role: 'assistant' }));
  log(`  ↳ 开始回复流：model=${model}`);

  const frames = REPLY_TOKENS.map((token) => contentFrame(id, model, { content: token }));
  frames.push(finishFrame(id, model));
  streamFrames(req, res, frames, 'data: [DONE]\n\n', '回复结束：finish + [DONE]');
}

/**
 * POST /v1/messages —— Anthropic 线路。
 *
 * 事件序列照官方契约：
 * message_start → content_block_start（文本块）→ N × content_block_delta
 * → content_block_stop → message_delta（stop_reason + 输出 token）→ message_stop。
 *
 * message_delta 刻意只报 output_tokens：真实 Anthropic 也不在那里重报 input_tokens，
 * 而下游若把两处 usage 合并，多写一个 `input_tokens: 0` 会把 message_start 里的
 * 真实输入 token 覆盖成 0。
 */
function handleMessages(req, res, bodyRaw) {
  let model = MODEL_NAME;
  let stream = false;
  try {
    const parsed = JSON.parse(bodyRaw || '{}');
    if (parsed.model) model = parsed.model;
    stream = parsed.stream === true;
  } catch {
    // 忽略解析失败，用默认模型名
  }

  const id = `msg_mock_${Date.now()}`;
  const text = REPLY_TOKENS.join('');
  const usage = { input_tokens: 24, output_tokens: 36 };

  // 非流式：真实 Messages API 在 stream 未显式置 true 时返回单个 JSON 对象。
  // 这条分支主要给 curl 手工验证用（Claude 系客户端默认走流式）。
  if (!stream) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({
      id,
      type: 'message',
      role: 'assistant',
      model,
      content: [{ type: 'text', text }],
      stop_reason: 'end_turn',
      stop_sequence: null,
      usage,
    }));
  }

  res.writeHead(200, SSE_HEADERS);
  log(`  ↳ 开始回复流：model=${model}`);

  const frames = [
    typedFrame('message_start', {
      type: 'message_start',
      message: {
        id,
        type: 'message',
        role: 'assistant',
        model,
        content: [],
        stop_reason: null,
        stop_sequence: null,
        usage: { input_tokens: usage.input_tokens, output_tokens: 0 },
      },
    }),
    typedFrame('content_block_start', {
      type: 'content_block_start',
      index: 0,
      content_block: { type: 'text', text: '' },
    }),
    ...REPLY_TOKENS.map((token) => typedFrame('content_block_delta', {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: token },
    })),
    typedFrame('content_block_stop', { type: 'content_block_stop', index: 0 }),
    typedFrame('message_delta', {
      type: 'message_delta',
      delta: { stop_reason: 'end_turn', stop_sequence: null },
      usage: { output_tokens: usage.output_tokens },
    }),
    typedFrame('message_stop', { type: 'message_stop' }),
  ];
  streamFrames(req, res, frames, null, '回复结束：message_stop');
}

/**
 * POST /v1/responses —— OpenAI Responses 线路。
 *
 * 事件序列照官方契约：
 * response.created → response.in_progress → response.output_item.added
 * → response.content_part.added → N × response.output_text.delta
 * → response.output_text.done → response.content_part.done
 * → response.output_item.done → response.completed。
 *
 * 整条链一帧都不能省：中间每一帧都携带下游初始化 UI 所需的结构，
 * 缺 response.created 时客户端会拿不到 response id 而无法开始渲染；
 * 而 response.completed 是终态，少了它下游只能等 TCP 关闭才收尾。
 */
function handleResponses(req, res, bodyRaw) {
  let model = MODEL_NAME;
  let stream = false;
  try {
    const parsed = JSON.parse(bodyRaw || '{}');
    if (parsed.model) model = parsed.model;
    stream = parsed.stream === true;
  } catch {
    // 忽略解析失败，用默认模型名
  }

  const responseId = `resp_mock_${Date.now()}`;
  const itemId = `msg_mock_${Date.now()}`;
  const created = Math.floor(Date.now() / 1000);
  const text = REPLY_TOKENS.join('');
  const usage = { input_tokens: 24, output_tokens: 36, total_tokens: 60 };

  /** 已完成态的响应对象 —— 终态事件与非流式响应共用同一份结构。 */
  const completedResponse = {
    id: responseId,
    object: 'response',
    created_at: created,
    status: 'completed',
    model,
    output: [
      {
        id: itemId,
        type: 'message',
        status: 'completed',
        role: 'assistant',
        content: [{ type: 'output_text', text, annotations: [] }],
      },
    ],
    usage,
  };

  // 非流式分支，给 curl 手工验证用。
  if (!stream) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(completedResponse));
  }

  res.writeHead(200, SSE_HEADERS);
  log(`  ↳ 开始回复流：model=${model}`);

  const frames = [
    typedFrame('response.created', {
      type: 'response.created',
      response: {
        id: responseId,
        object: 'response',
        created_at: created,
        status: 'in_progress',
        model,
        output: [],
      },
    }),
    typedFrame('response.in_progress', {
      type: 'response.in_progress',
      response: {
        id: responseId,
        object: 'response',
        created_at: created,
        status: 'in_progress',
        model,
        output: [],
      },
    }),
    typedFrame('response.output_item.added', {
      type: 'response.output_item.added',
      output_index: 0,
      item: {
        id: itemId,
        type: 'message',
        status: 'in_progress',
        role: 'assistant',
        content: [],
      },
    }),
    typedFrame('response.content_part.added', {
      type: 'response.content_part.added',
      item_id: itemId,
      output_index: 0,
      content_index: 0,
      part: { type: 'output_text', text: '', annotations: [] },
    }),
    ...REPLY_TOKENS.map((token) => typedFrame('response.output_text.delta', {
      type: 'response.output_text.delta',
      item_id: itemId,
      output_index: 0,
      content_index: 0,
      delta: token,
    })),
    typedFrame('response.output_text.done', {
      type: 'response.output_text.done',
      item_id: itemId,
      output_index: 0,
      content_index: 0,
      text,
    }),
    typedFrame('response.content_part.done', {
      type: 'response.content_part.done',
      item_id: itemId,
      output_index: 0,
      content_index: 0,
      part: { type: 'output_text', text, annotations: [] },
    }),
    typedFrame('response.output_item.done', {
      type: 'response.output_item.done',
      output_index: 0,
      item: {
        id: itemId,
        type: 'message',
        status: 'completed',
        role: 'assistant',
        content: [{ type: 'output_text', text, annotations: [] }],
      },
    }),
    typedFrame('response.completed', {
      type: 'response.completed',
      response: completedResponse,
    }),
  ];
  streamFrames(req, res, frames, null, '回复结束：response.completed');
}

/** 主路由分派。 */
const server = http.createServer(async (req, res) => {
  dumpRequest(req);

  const method = req.method || 'GET';
  const url = (req.url || '').split('?')[0];

  // 需要读 body 的 POST 端点先把 body 读出来（同时用于打印）
  if (method === 'POST') {
    const bodyRaw = await readBody(req);
    if (bodyRaw) {
      // 不截断：请求体正是本 mock 要观察的对象之一（system 提示词、tools 定义、
      // 思考参数都藏在这里），截断会让「谁发的什么」变成猜谜。
      log(`  ↳ 请求体: ${bodyRaw}`);
    }

    if (url === '/api/show') return handleShow(req, res);
    if (url === '/v1/chat/completions') return handleChat(req, res, bodyRaw);
    if (url === '/v1/messages') return handleMessages(req, res, bodyRaw);
    if (url === '/v1/responses') return handleResponses(req, res, bodyRaw);

    res.writeHead(404, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ error: `unknown POST ${url}` }));
  }

  if (url === '/api/version') return handleVersion(req, res);
  if (url === '/api/tags') return handleTags(req, res);
  if (url === '/v1/models') return handleV1Models(req, res);

  res.writeHead(404, { 'Content-Type': 'application/json' });
  return res.end(JSON.stringify({ error: `unknown GET ${url}` }));
});

server.listen(PORT, () => {
  log('════════════════════════════════════════════════');
  log(`COSP Mock（下游请求头嗅探器）已启动`);
  log(`监听: http://localhost:${PORT}`);
  log(`模型: ${MODEL_NAME}`);
  log('');
  log('把 Copilot / Claude 系客户端的端点指向上面的地址，然后：');
  log('  1) 观察模型发现阶段（/api/tags、/api/show）的请求头');
  log('  2) 发起聊天，观察对应端点的请求头：');
  log('       Copilot        → /v1/chat/completions');
  log('       Claude 系客户端 → /v1/messages');
  log('       直连测试        → /v1/responses');
  log('  3) 重点看每次请求末尾的「★ 认证相关头」提示');
  log('════════════════════════════════════════════════');
});
