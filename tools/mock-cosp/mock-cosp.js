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
 *  - POST /v1/chat/completions   实际聊天（顺序回复几个 chunk + [DONE]）
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
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });

  // 首帧带 role
  res.write(contentFrame(id, model, { role: 'assistant' }));
  log(`  ↳ 开始回复流：model=${model}`);

  let i = 0;
  const timer = setInterval(() => {
    if (i < REPLY_TOKENS.length) {
      res.write(contentFrame(id, model, { content: REPLY_TOKENS[i] }));
      i += 1;
      return;
    }
    clearInterval(timer);
    res.write(finishFrame(id, model));
    res.write('data: [DONE]\n\n');
    res.end();
    log('  ↳ 回复结束：finish + [DONE] + 关连接');
  }, CHUNK_INTERVAL_MS);

  // 下游断连时清理定时器
  req.on('close', () => {
    clearInterval(timer);
  });
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
      log(`  ↳ 请求体: ${bodyRaw.length > 500 ? bodyRaw.slice(0, 500) + '…(截断)' : bodyRaw}`);
    }

    if (url === '/api/show') return handleShow(req, res);
    if (url === '/v1/chat/completions') return handleChat(req, res, bodyRaw);

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
  log('把 Copilot 的 Ollama 端点指向上面的地址，然后：');
  log('  1) 观察模型发现阶段（/api/tags、/api/show）的请求头');
  log('  2) 选中 mock-sniffer 模型发起聊天，观察 /v1/chat/completions 的请求头');
  log('  3) 重点看每次请求末尾的「★ 认证相关头」提示');
  log('════════════════════════════════════════════════');
});
