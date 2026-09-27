#!/usr/bin/env node
'use strict';

/**
 * 协议无关的场景构建块。
 *
 * <h2>为什么单独一层</h2>
 * 有一类场景的**行为**与协议完全无关 —— 返回错误码、重试后成功、挂起不响应、
 * 延迟响应、截断 socket。合并前三个 mock 各自实现了一份（`error-500` / `error-401` /
 * `retry-then-succeed` / `hang-response` / `slow-response` 各三份，共 15 处等价代码），
 * 而它们唯一真正的差异只是**错误体形状**与**正常响应的构造方式**。
 *
 * <p>因此本文件只做一件事：把「协议无关的部分」写死，把「协议相关的部分」通过
 * 传入的 `protocol` 对象回调出去。协议模块调用这些构建块把自己的场景表拼出来 ——
 * 场景的**声明**仍在各协议文件里（那里能看到该协议支持哪些场景），
 * 只是**实现**不再重复。
 *
 * <h2>protocol 对象契约</h2>
 * <ul>
 *   <li>{@code id} —— `chat` / `messages`，用于场景注册表的键</li>
 *   <li>{@code renderErrorBody(status, message)} —— 该协议的错误体</li>
 *   <li>{@code newId()} —— 该协议的响应 id 生成</li>
 *   <li>{@code writeNormalStream(res, model)} —— 正常流式响应（供 retry-recover 复用）</li>
 *   <li>{@code sendNormalNonStream(res, model)} —— 正常非流式响应（同上）</li>
 *   <li>{@code serializeNormalBody(model)} —— 正常非流式响应体的完整文本（供截断场景用）</li>
 * </ul>
 */

const {
  RetryCounter,
  log,
  sendJson,
  sendError,
  writeCommentFrame,
  writeSseHead,
  writeTruncated,
} = require('./http');

/* ── 可调参数 ─────────────────────────────────────────── */

/** cancel-hang 挂起的时长（毫秒）。默认 10 分钟。 */
const HANG_MS = 10 * 60 * 1000;
/** phase-slow 的延迟时长（毫秒）。 */
const SLOW_MS = 5 * 1000;
/** retry-recover 在第几次请求时成功（前 N-1 次返回对应错误码）。 */
const RETRY_SUCCESS_ATTEMPT = 3;
/** retry-recover 计数器空闲清零时长（毫秒）。 */
const RETRY_RESET_MS = 30 * 1000;

/**
 * retry-recover 的计数器。
 *
 * <p>刻意做成模块级单例而不是按协议各一份：它模拟的是**同一个上游在抖动**，
 * 三条线路打过去看到的是同一个故障。按协议分家会让「先打 Chat 两次失败、
 * 再打 Messages」观察到不同的计数状态，那不是真实上游的行为。
 */
const retryCounter = new RetryCounter(RETRY_SUCCESS_ATTEMPT, RETRY_RESET_MS);

/* ── 构建块 ───────────────────────────────────────────── */

/**
 * 构建「返回指定状态码」的场景。
 *
 * @param protocol   协议对象
 * @param status     HTTP 状态码
 * @param message    错误消息
 * @param extraHeaders 追加响应头（429 用 `Retry-After`）
 */
function errorScenario(protocol, status, message, extraHeaders) {
  const run = (res, model) => {
    sendError(res, status, message, model, protocol.renderErrorBody, extraHeaders);
  };
  return { stream: run, nonstream: run };
}

/**
 * 构建 retry-recover：前 N-1 次返回 500，第 N 次正常回复。
 *
 * <p>用于验证「两态与三协议共用同一份重试预算」—— 若某条线路另起了一套重试配置，
 * 改动 `retry_max_attempts` 时它的表现会与其它线路不一致。计数器跨请求持久，
 * 成功后立即清零便于连续测试。
 */
function retryRecoverScenario(protocol) {
  const run = (res, model, mode) => {
    const { attempt, success } = retryCounter.next();
    if (!success) {
      log(`✗ retry-recover 第 ${attempt} 次请求，返回 500  model=${model}`);
      return sendError(res, 500, `mock retry attempt ${attempt} failed (500)`, model, protocol.renderErrorBody);
    }
    retryCounter.reset();
    log(`↻ retry-recover 第 ${attempt} 次请求，正常回复  model=${model}`);
    return mode === 'stream' ? protocol.writeNormalStream(res, model) : protocol.sendNormalNonStream(res, model);
  };
  return {
    stream: (res, model) => run(res, model, 'stream'),
    nonstream: (res, model) => run(res, model, 'nonstream'),
  };
}

/**
 * 构建 retry-truncated：声明完整 Content-Length 却只写一半就断开 socket。
 *
 * <p>两态与三协议共用同一实现 —— 截断是传输层行为，与协议形态无关。
 * 下游应看到可重试的网络类失败（调用日志状态码 `-1`），而不是一个内容为空的成功响应。
 */
function truncatedScenario(protocol) {
  const run = (res, model) => writeTruncated(res, protocol.serializeNormalBody(model), model);
  return { stream: run, nonstream: run };
}

/**
 * 构建 cancel-hang：响应不返回任何数据，供验证「右键断连 → ABORTED」。
 *
 * <h2>为何两态机制不同</h2>
 * 流式会先写出响应头（再补一个 SSE 注释帧把它 flush 出去），于是 COSP 能进入
 * **CONNECTED（等待首字）** 相位 —— 这正是要观察的状态。非流式没有「首字」概念，
 * 连响应头都不写，客户端处于**纯等待**状态（`Mono.firstWithSignal` 的取消路径）。
 * 两种形态都值得单独验，故不做成同一份代码。
 */
function hangScenario(protocol) {
  return {
    stream: (res, model) => {
      writeSseHead(res);
      writeCommentFrame(res);
      log(`… cancel-hang 已建立连接，卡首字 ${HANG_MS / 60000} 分钟  model=${model}`);
      const timer = setTimeout(() => {
        if (!res.writableEnded) {
          log(`✓ cancel-hang 卡满后正常收尾  model=${model}`);
          protocol.writeNormalStream(res, model);
        }
      }, HANG_MS);
      res.on('close', () => clearTimeout(timer));
    },
    nonstream: (res, model) => {
      log(`… cancel-hang 已收到请求，挂起 ${HANG_MS / 60000} 分钟 不返回  model=${model}`);
      const timer = setTimeout(() => {
        if (!res.writableEnded) {
          protocol.sendNormalNonStream(res, model);
        }
      }, HANG_MS);
      res.on('close', () => clearTimeout(timer));
    },
  };
}

/**
 * 构建 phase-slow：延迟若干秒后正常响应。
 *
 * <p>验证「耗时较长」不会被误判为停滞 —— Toast 应停在 CONNECTED / CHUNK 而
 * 不进入任何错误分支，取消仍可用。
 */
function slowScenario(protocol) {
  const run = (res, model, mode) => {
    log(`… phase-slow 延迟 ${SLOW_MS / 1000}s 后返回  model=${model}`);
    const timer = setTimeout(() => {
      if (res.writableEnded) return;
      return mode === 'stream' ? protocol.writeNormalStream(res, model) : protocol.sendNormalNonStream(res, model);
    }, SLOW_MS);
    res.on('close', () => clearTimeout(timer));
  };
  return {
    stream: (res, model) => run(res, model, 'stream'),
    nonstream: (res, model) => run(res, model, 'nonstream'),
  };
}

/**
 * 全部协议无关场景。
 *
 * @param protocol 协议对象
 * @param opts     可选限制，用于「该协议尚未实现某场景」的情况
 * @param opts.omit 要跳过的场景名集合
 */
function sharedScenarios(protocol, opts = {}) {
  const all = {
    'retry-5xx': { desc: '返回 500（COSP 应重试）', ...errorScenario(protocol, 500, 'mock upstream error (500)') },
    'retry-429': {
      desc: '返回 429 + Retry-After: 5（COSP 应重试，且日志带 Retry-After）',
      ...errorScenario(protocol, 429, 'mock upstream rate limited (429)', { 'Retry-After': '5' }),
    },
    'retry-truncated': { desc: '声明 Content-Length 后只写一半就断 socket（网络类失败，应重试）', ...truncatedScenario(protocol) },
    'retry-recover': { desc: '前 2 次返回 500，第 3 次正常（验证三协议两态共享同一份重试预算）', ...retryRecoverScenario(protocol) },
    'fail-401': { desc: '返回 401（COSP 应快速失败，不重试）', ...errorScenario(protocol, 401, 'mock upstream unauthorized (401)') },
    'cancel-hang': { desc: '响应不吐任何数据（流式已建立连接），验证右键断连 → ABORTED', ...hangScenario(protocol) },
    'phase-slow': { desc: '延迟 5s 后正常返回（验证长耗时不被误判为停滞）', ...slowScenario(protocol) },
  };
  const omit = opts.omit || new Set();
  return Object.fromEntries(Object.entries(all).filter(([name]) => !omit.has(name)));
}

module.exports = {
  HANG_MS,
  SLOW_MS,
  sharedScenarios,
};
