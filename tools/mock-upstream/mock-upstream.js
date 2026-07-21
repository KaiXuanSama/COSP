#!/usr/bin/env node
'use strict';

/**
 * COSP 测试用模拟上游供应商。
 *
 * 提供 OpenAI 兼容的 /v1/models 与 /v1/chat/completions 端点，
 * 通过“模型名”触发各种上游行为（正常 / 卡首字 / 停滞 / 不关连接 / 错误码等），
 * 从而在真实 HTTP + SSE 连接下物理复现 COSP 的各种 Toast 生命周期状态，
 * 而不依赖真实上游 API。
 *
 * 特性：
 *  - 纯 Node 内置 http 模块，零依赖，无需 npm install。
 *  - 每个连接的定时行为用 setTimeout 异步驱动，挂起连接几乎零成本。
 *  - 详细日志：每个请求的模型、阶段（连接/首字/chunk/结束）都打印，状态易感。
 *
 * 启动：node mock-upstream.js  （或经 npm run mock）
 */

const http = require('http');

// ── 可调参数（直接改这里）─────────────────────────────
const PORT = Number(process.env.MOCK_PORT || 8081);
/** hang-first-byte 卡住首字的时长（毫秒）。默认 10 分钟。 */
const HANG_FIRST_BYTE_MS = 10 * 60 * 1000;
/** stall-recover 中途停滞时长（毫秒）。需 > COSP 的 30s STALLED 阈值，以触发 STALLED 警告后再恢复。 */
const STALL_RECOVER_MS = 35 * 1000;
/** stall-recover / stall-forever 停滞前先吐的 chunk 数。 */
const STALL_AFTER_CHUNKS = 5;
/** normal 等正常流的 chunk 间隔（毫秒）。 */
const NORMAL_CHUNK_INTERVAL_MS = 80;
/** normal 流的 chunk 数。 */
const NORMAL_CHUNK_COUNT = 30;
/** slow-steady 的 chunk 间隔（毫秒）。需 < 30s，验证不会误判 STALLED。 */
const SLOW_STEADY_INTERVAL_MS = 3 * 1000;
/** slow-steady 的 chunk 数。 */
const SLOW_STEADY_CHUNK_COUNT = 8;
// ────────────────────────────────────────────────────

/** 模型清单：name -> 行为描述（供 /v1/models 输出与文档参考）。 */
const MODELS = [
  { id: 'normal', desc: '正常流式，完整 chunk + [DONE] + 关连接' },
  { id: 'hang-first-byte', desc: 'CONNECTED 后卡住不吐首字（默认 10 分钟）' },
  { id: 'stall-recover', desc: '吐若干 chunk 后停滞 35s（触发 STALLED），再继续到结束' },
  { id: 'stall-forever', desc: '吐若干 chunk 后永久停滞（触发 STALLED，等待用户手动断连）' },
  { id: 'done-no-close', desc: '发完内容 + [DONE]，但保持 TCP 不关闭' },
  { id: 'no-done-close', desc: '发完内容后直接关连接，不发 [DONE]' },
  { id: 'error-500', desc: '返回 500（COSP 应重试）' },
  { id: 'error-401', desc: '返回 401（COSP 应快速失败不重试）' },
  { id: 'slow-steady', desc: '每 3s 一个 chunk，持续较久（验证不误判 STALLED）' },
];

const MODEL_IDS = new Set(MODELS.map((m) => m.id));

/** 简单时间戳日志。 */
function log(...args) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}]`, ...args);
}

/** 构造一个 OpenAI 风格的流式 content chunk 的 SSE 帧字符串。 */
function contentFrame(id, model, content) {
  const payload = {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: { content }, finish_reason: null }],
  };
  return `data: ${JSON.stringify(payload)}\n\n`;
}

/** 构造首个带 role 的 chunk（OpenAI 流首帧惯例）。 */
function roleFrame(id, model) {
  const payload = {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: { role: 'assistant' }, finish_reason: null }],
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

/** 写 SSE 响应头。 */
function writeSseHead(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
}

/** 生成本次响应的 chunk 内容片段（简单可读文本）。 */
function makeChunkText(i) {
  return `token${i} `;
}

/**
 * 处理 /v1/chat/completions，按模型名分派行为。
 */
function handleChat(req, res, model) {
  const id = `chatcmpl-mock-${Date.now()}`;
  log(`▶ chat 开始  model=${model}`);

  // 客户端断开时打印，便于观察 COSP 取消 / 断连行为。
  req.on('aborted', () => log(`⨯ 客户端断开  model=${model}`));
  res.on('close', () => log(`■ 连接关闭  model=${model}`));

  switch (model) {
    case 'normal':
      return streamNormal(res, id, model);
    case 'hang-first-byte':
      return hangFirstByte(res, id, model);
    case 'stall-recover':
      return stallRecover(res, id, model);
    case 'stall-forever':
      return stallForever(res, id, model);
    case 'done-no-close':
      return doneNoClose(res, id, model);
    case 'no-done-close':
      return noDoneClose(res, id, model);
    case 'error-500':
      return errorResponse(res, 500, 'mock upstream error (500)', model);
    case 'error-401':
      return errorResponse(res, 401, 'mock upstream unauthorized (401)', model);
    case 'slow-steady':
      return slowSteady(res, id, model);
    default:
      // 未知模型：当作 normal 处理，方便随手测试。
      log(`? 未知模型 ${model}，按 normal 处理`);
      return streamNormal(res, id, model);
  }
}

/** normal：正常流式，role → N 个 content → finish → [DONE] → 关闭。 */
function streamNormal(res, id, model) {
  writeSseHead(res);
  res.write(roleFrame(id, model));
  let i = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (i < NORMAL_CHUNK_COUNT) {
      res.write(contentFrame(id, model, makeChunkText(i)));
      i += 1;
      return;
    }
    clearInterval(timer);
    res.write(finishFrame(id, model));
    res.write('data: [DONE]\n\n');
    res.end();
    log(`✓ normal 完成  model=${model}  chunks=${NORMAL_CHUNK_COUNT}`);
  }, NORMAL_CHUNK_INTERVAL_MS);
}

/** hang-first-byte：写 SSE 头（连接建立），但长时间不吐任何 chunk。 */
function hangFirstByte(res, id, model) {
  writeSseHead(res);
  // 立即 flush 响应头，让 COSP 进入 CONNECTED（等待首字）。
  res.write(':\n\n'); // SSE 注释帧，不算数据 chunk，仅确保头被 flush
  log(`… hang-first-byte 已建立连接，卡首字 ${HANG_FIRST_BYTE_MS / 1000}s  model=${model}`);
  const timer = setTimeout(() => {
    if (!res.writableEnded) {
      res.write(contentFrame(id, model, '（终于来了）'));
      res.write(finishFrame(id, model));
      res.write('data: [DONE]\n\n');
      res.end();
      log(`✓ hang-first-byte 卡满后完成  model=${model}`);
    }
  }, HANG_FIRST_BYTE_MS);
  res.on('close', () => clearTimeout(timer));
}

/** stall-recover：吐 K 个 chunk → 停滞 35s → 再吐到结束。 */
function stallRecover(res, id, model) {
  writeSseHead(res);
  res.write(roleFrame(id, model));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    res.write(contentFrame(id, model, makeChunkText(i)));
    i += 1;
    if (i === STALL_AFTER_CHUNKS) {
      log(`… stall-recover 停滞 ${STALL_RECOVER_MS / 1000}s  model=${model}`);
      const t = setTimeout(() => {
        log(`↻ stall-recover 恢复  model=${model}`);
        resume();
      }, STALL_RECOVER_MS);
      res.on('close', () => clearTimeout(t));
      return;
    }
    setTimeout(pump, NORMAL_CHUNK_INTERVAL_MS);
  }
  function resume() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS + NORMAL_CHUNK_COUNT) {
      res.write(contentFrame(id, model, makeChunkText(i)));
      i += 1;
      setTimeout(resume, NORMAL_CHUNK_INTERVAL_MS);
      return;
    }
    res.write(finishFrame(id, model));
    res.write('data: [DONE]\n\n');
    res.end();
    log(`✓ stall-recover 完成  model=${model}`);
  }
  pump();
}

/** stall-forever：吐 K 个 chunk 后永久停滞，且不关连接。 */
function stallForever(res, id, model) {
  writeSseHead(res);
  res.write(roleFrame(id, model));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < STALL_AFTER_CHUNKS) {
      res.write(contentFrame(id, model, makeChunkText(i)));
      i += 1;
      setTimeout(pump, NORMAL_CHUNK_INTERVAL_MS);
      return;
    }
    log(`… stall-forever 永久停滞（不关连接）  model=${model}`);
    // 什么都不做：连接保持打开，永不再吐 chunk，也不 end。
  }
  pump();
}

/** done-no-close：发完内容 + [DONE]，但保持 TCP 不关闭。 */
function doneNoClose(res, id, model) {
  writeSseHead(res);
  res.write(roleFrame(id, model));
  let i = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (i < NORMAL_CHUNK_COUNT) {
      res.write(contentFrame(id, model, makeChunkText(i)));
      i += 1;
      return;
    }
    clearInterval(timer);
    res.write(finishFrame(id, model));
    res.write('data: [DONE]\n\n');
    // 关键：发完 [DONE] 后不调用 res.end()，模拟 keep-alive 不关连接。
    log(`… done-no-close 已发 [DONE]，保持连接不关  model=${model}`);
  }, NORMAL_CHUNK_INTERVAL_MS);
}

/** no-done-close：发完内容后直接关连接，不发 [DONE]。 */
function noDoneClose(res, id, model) {
  writeSseHead(res);
  res.write(roleFrame(id, model));
  let i = 0;
  const timer = setInterval(() => {
    if (res.writableEnded) {
      clearInterval(timer);
      return;
    }
    if (i < NORMAL_CHUNK_COUNT) {
      res.write(contentFrame(id, model, makeChunkText(i)));
      i += 1;
      return;
    }
    clearInterval(timer);
    res.write(finishFrame(id, model));
    // 关键：不发 [DONE]，直接关连接。COSP 应靠 Layer 2（TCP 关闭）兜底完成。
    res.end();
    log(`✓ no-done-close 已关连接（无 [DONE]）  model=${model}`);
  }, NORMAL_CHUNK_INTERVAL_MS);
}

/** slow-steady：稳定每 3s 一个 chunk，持续较久，chunk 间隔 < 5s 不应触发 STALLED。 */
function slowSteady(res, id, model) {
  writeSseHead(res);
  res.write(roleFrame(id, model));
  let i = 0;
  function pump() {
    if (res.writableEnded) return;
    if (i < SLOW_STEADY_CHUNK_COUNT) {
      res.write(contentFrame(id, model, makeChunkText(i)));
      i += 1;
      setTimeout(pump, SLOW_STEADY_INTERVAL_MS);
      return;
    }
    res.write(finishFrame(id, model));
    res.write('data: [DONE]\n\n');
    res.end();
    log(`✓ slow-steady 完成  model=${model}`);
  }
  pump();
}

/** 错误响应：返回指定状态码 + OpenAI 风格错误体。 */
function errorResponse(res, status, message, model) {
  const body = JSON.stringify({ error: { message, type: 'mock_error', code: status } });
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
  log(`✗ 返回错误 ${status}  model=${model}`);
}

/** /v1/models：列出所有场景模型。 */
function handleModels(res) {
  const data = MODELS.map((m) => ({
    id: m.id,
    object: 'model',
    created: Math.floor(Date.now() / 1000),
    owned_by: 'mock-upstream',
  }));
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ object: 'list', data }));
  log(`↳ /v1/models 返回 ${data.length} 个模型`);
}

/** 读取并解析请求体 JSON。 */
function readJsonBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
    });
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

const server = http.createServer(async (req, res) => {
  const url = req.url || '';

  if (req.method === 'GET' && (url === '/v1/models' || url.startsWith('/v1/models?'))) {
    return handleModels(res);
  }

  if (req.method === 'POST' && (url === '/v1/chat/completions' || url.startsWith('/v1/chat/completions?'))) {
    const body = await readJsonBody(req);
    const model = typeof body.model === 'string' ? body.model : 'normal';
    return handleChat(req, res, model);
  }

  // 其它路径：404
  res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ error: { message: `not found: ${req.method} ${url}`, type: 'mock_error' } }));
  log(`404  ${req.method} ${url}`);
});

server.listen(PORT, () => {
  log(`COSP 模拟上游已启动: http://localhost:${PORT}`);
  log(`  GET  /v1/models`);
  log(`  POST /v1/chat/completions`);
  log('可用模型：');
  for (const m of MODELS) {
    log(`  - ${m.id.padEnd(16)} ${m.desc}`);
  }
  log('在 COSP 管理后台新增供应商，Base URL 指向 http://localhost:' + PORT + ' 即可。');
});
