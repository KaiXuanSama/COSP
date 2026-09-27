#!/usr/bin/env node
'use strict';

/**
 * 共用骨架：协议无关的 HTTP / SSE 机制与日志。
 *
 * <p>本文件不认识任何协议，也不认识任何场景 —— 它只回答「怎么把字节写出去」
 * 与「怎么把请求读进来」。三种协议的帧构造在 `protocols/` 下，协议无关的场景在
 * `transport.js`，按场景名分派的入口在 `mock-upstream.js`。
 *
 * <p>为何单独一层：合并前三个 mock 各自实现了一份 SSE 写头、读 body、日志与错误响应，
 * 5 × 3 = 15 处等价代码。分开写时它们已经漂过（有的写 `Content-Length`、有的不写），
 * 那类漂移不会被任何测试发现 —— mock 本身不参与自动化测试。
 */

const REQUIRED_ANTHROPIC_VERSION = '2023-06-01';

/* ── 日志 ─────────────────────────────────────────────── */

/** 简单时间戳日志。前缀用于区分 mock 自身的日志与上游真实返回的字节。 */
function log(...args) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}]`, ...args);
}

/** 每个请求都会打的一条，便于对照 COSP 侧的调用日志。 */
function logRequest(kind, model, extra) {
  log(`▶ ${kind}  model=${model}${extra ? `  ${extra}` : ''}`);
}

/**
 * 挂上断开/关闭日志。
 *
 * <p>取消路径（右键断连 → ABORTED）只能靠这两条日志观察，因此每个聊天端点都要挂。
 */
function attachLifecycleLogs(req, res, model) {
  req.on('aborted', () => log(`⨯ 客户端断开  model=${model}`));
  res.on('close', () => log(`■ 连接关闭  model=${model}`));
}

/* ── 读请求 ───────────────────────────────────────────── */

/**
 * 读取并解析请求体 JSON。
 *
 * <p>解析失败返回空对象而不是抛错：mock 关心的是「模型名是什么」，
 * 请求体畸形时不该让 mock 崩掉，那会让「COSP 发出来的报文对不对」这件事失去观察窗口。
 */
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

/* ── 写响应 ───────────────────────────────────────────── */

/** 写 SSE 响应头。 */
function writeSseHead(res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
}

/** 写一帧 OpenAI 风格的裸 `data:` 帧（无 event 名）。 */
function writeDataFrame(res, payload) {
  const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
  res.write(`data: ${raw}\n\n`);
}

/**
 * 写一帧 Anthropic 风格的事件帧。
 *
 * <p>Anthropic 的 SSE 帧带 `event:` 名，且**必须与 JSON 里的 `type` 一致** ——
 * 不一致时下游按 event 名分派、按 type 判定，会得到互相矛盾的两份结论。
 */
function writeEventFrame(res, payload) {
  res.write(`event: ${payload.type}\ndata: ${JSON.stringify(payload)}\n\n`);
}

/** SSE 注释帧：不算数据，只用于把响应头 flush 出去。 */
function writeCommentFrame(res) {
  res.write(':\n\n');
}

/**
 * 写 JSON 响应并结束。
 *
 * @param extraHeaders 追加的响应头（如 429 的 `Retry-After`）
 */
function sendJson(res, status, payload, label, model, extraHeaders) {
  const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
    ...(extraHeaders || {}),
  });
  res.end(raw);
  if (label) {
    log(`✓ ${label}${model ? `  model=${model}` : ''}  bytes=${Buffer.byteLength(raw)}`);
  }
}

/**
 * 返回 HTTP 错误响应。
 *
 * @param renderErrorBody 该协议的错误体渲染函数，签名 (status, message) —— 三种协议的
 *                        错误形状不同（Chat 是 `{error:{...}}`，Messages 多一层
 *                        `{type:'error',error:{...}}`），故由协议模块提供
 * @param extraHeaders    追加的响应头（如 429 的 `Retry-After`）
 */
function sendError(res, status, message, model, renderErrorBody, extraHeaders) {
  sendJson(res, status, renderErrorBody(status, message), `返回错误 ${status}`, model, extraHeaders);
}

/* ── 422：不支持的模式 / 协议组合 ─────────────────────── */

/**
 * 场景存在，但当前「协议 × 传输模式」组合下没有实现。
 *
 * <h2>为何是 422 而不是 400 / 501</h2>
 * COSP 的 `UpstreamRetryPolicy.isRetryableStatus` 白名单是 **`429` / `5xx` / `400`**：
 * <ul>
 *   <li>回 <strong>400</strong> → 落在白名单里，COSP 会重试满预算（默认 5 次、2s/30s），
 *       白等 62 秒才看到「模式不支持」；</li>
 *   <li>回 <strong>501</strong>（语义上最贴切的「未实现」）→ 它是 5xx，
 *       {@code status.is5xxServerError()} 同样命中，同样白等；</li>
 *   <li><strong>422 三者都不沾</strong> → COSP 快速失败，body 经
 *       {@code FailureKind.UPSTREAM_HTTP} 原样透传给下游。</li>
 * </ul>
 *
 * <p>另有一条更本质的理由：mock 自己的「不支持」**不是上游语义**。真实 OpenAI /
 * Anthropic 几乎不用 422，因此日志里一出现 422 就知道是 mock 在说话，
 * 不会与「上游真的报错」混淆 —— 借用 400 则两者在日志里长得一模一样。
 *
 * @param reason 追加在固定前缀之后的可操作指引（例如「请改用非流式」）
 */
function sendUnsupported(res, model, reason, renderErrorBody) {
  const message = `mock 场景 ${model} 在当前「协议 × 传输模式」组合下没有实现。${reason}`;
  log(`✗ 422 场景不适用  model=${model}  ${reason}`);
  sendJson(res, 422, renderErrorBody(422, message), null, null);
}
/* ── 通用的传输层病态行为 ─────────────────────────────── */

/**
 * 声明完整 Content-Length 却只写一半就销毁 socket。
 *
 * <p>制造「声明了长度却没写完」的截断，COSP 侧应看到可重试的网络类失败
 * （日志状态码 `-1`），而不是一个内容为空的成功响应。
 *
 * @param full 完整响应体文本
 */
function writeTruncated(res, full, model) {
  const half = full.slice(0, Math.floor(full.length / 2));
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(full),
  });
  res.write(half);
  // 不 end：直接销毁底层 socket。
  res.socket.destroy();
  log(`✗ 截断 已写 ${Buffer.byteLength(half)}/${Buffer.byteLength(full)} 字节后断开 socket  model=${model}`);
}

/** 200 + 0 字节 body。 */
function sendEmptyBody(res, model) {
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': 0,
  });
  res.end();
  log(`✓ 已返回 200 + 0 字节 body  model=${model}`);
}

/* ── 重试计数器 ───────────────────────────────────────── */

/**
 * 「前 N-1 次失败、第 N 次成功」的计数器。
 *
 * <p>一个实例对应一个场景名。计数器跨请求持久，成功后或空闲超过 {@code resetMs}
 * 自动清零，便于反复触发而不必重启服务。
 *
 * <p>之所以做成实例而不是模块级变量：合并前三个 mock 各有一份模块级
 * `retryAttempt` / `retryLastAt`，三份代码逐字相同却各自维护状态 ——
 * 而「同一场景在不同协议下共用同一个计数器」是这里的正确语义
 * （它就是同一个上游在抖动），不该按协议分家。
 */
class RetryCounter {
  /**
   * @param successAttempt 第几次请求返回成功（前 N-1 次失败）
   * @param resetMs        空闲多久后清零
   */
  constructor(successAttempt, resetMs) {
    this.successAttempt = successAttempt;
    this.resetMs = resetMs;
    this.attempt = 0;
    this.lastAt = 0;
  }

  /** 记一次请求，返回是否应当成功。 */
  next() {
    const now = Date.now();
    if (this.lastAt > 0 && now - this.lastAt > this.resetMs) {
      log(`↺ retry 计数器空闲 ${Math.round((now - this.lastAt) / 1000)}s，已清零`);
      this.attempt = 0;
    }
    this.lastAt = now;
    this.attempt += 1;
    return {
      attempt: this.attempt,
      success: this.attempt >= this.successAttempt,
    };
  }

  /** 成功后立即清零，下一轮从头计数。 */
  reset() {
    this.attempt = 0;
  }
}

/** 校验 anthropic-version 头；不通过时直接回 400 并返回 false。 */
function requireAnthropicVersion(req, res, renderErrorBody) {
  const version = req.headers['anthropic-version'];
  if (version === REQUIRED_ANTHROPIC_VERSION) {
    return true;
  }
  log(`✗ 拒绝请求：anthropic-version=${version || '(缺失)'}，要求 ${REQUIRED_ANTHROPIC_VERSION}`);
  sendJson(res, 400, renderErrorBody(400, `要求 anthropic-version: ${REQUIRED_ANTHROPIC_VERSION}`), null, null);
  return false;
}

module.exports = {
  REQUIRED_ANTHROPIC_VERSION,
  RetryCounter,
  attachLifecycleLogs,
  log,
  logRequest,
  readJsonBody,
  requireAnthropicVersion,
  sendEmptyBody,
  sendError,
  sendJson,
  sendUnsupported,
  writeCommentFrame,
  writeDataFrame,
  writeEventFrame,
  writeSseHead,
  writeTruncated,
};
