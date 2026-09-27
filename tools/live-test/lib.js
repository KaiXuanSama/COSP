#!/usr/bin/env node
'use strict';

/**
 * COSP 实机测试脚手架 —— 打请求、读日志、数重试。
 *
 * <h2>它解决什么问题</h2>
 * 手动验证时最麻烦的三件事：
 * <ol>
 *   <li><b>PowerShell 5.1 的 curl 传 JSON 不可靠</b> —— 内联 body 的引号会被吃掉，
 *       表现为「模型名丢失、落到默认场景」，看起来像 COSP 有 bug。
 *       本脚本用 Node 的 http 模块直接打，body 精确可控。</li>
 *   <li><b>重试从 COSP 侧看不出来</b> —— `api_call_log` 每轮只落一条，
 *       `duration_ms` 只记一轮。真实的重试次数只在**上游 mock 的日志**里。</li>
 *   <li><b>流式与流式的错误传递方式不同</b> —— 流式恒 HTTP 200（错误在
 *       `event: error` 帧里），非流式才原样透传状态码。见 {@link ./README.md}。</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 * node lib.js login                  # 登录取 JWT 存 token.txt
 * node a-guard.js                    # A 组：空响应兜底
 * node b-retry.js                    # B 组：重试与放行
 * node d-translate.js                # D 组：C2M 翻译
 * </pre>
 *
 * <h2>前置</h2>
 * <ol>
 *   <li>COSP 运行中（默认 :11434）</li>
 *   <li>mock 运行中，且**输出落盘**到 `mock.log`：
 *       <pre>cd tools/mock-upstream; node mock-upstream.js 2>&1 | Tee-Object -FilePath ..\..\tools\live-test\mock.log</pre>
 *       落盘是必须的 —— 重试计数全靠它。</li>
 *   <li>mock 供应商已在管理后台配置好（33 个模型，三协议）</li>
 * </ol>
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

/* ── .env 自动读取（在配置解析之前） ──────────────────── */

/**
 * 若本目录存在 `.env`，把其中的 `KEY=VALUE` 载入 `process.env`。
 *
 * <h2>为何不依赖 `--env-file`</h2>
 * Node 24 支持 `node --env-file=.env a-guard.js`，但那要**每个脚本都加参数** ——
 * 七组脚本七个命令行，且 `node lib.js login` 单独跑时最容易忘。
 * 自动读取让「改 `.env` → 直接 `node xxx.js`」成为唯一用法。
 *
 * <h2>语义</h2>
 * - **已存在的环境变量优先**：命令行显式设的（`$env:COSP_ADMIN_PASS='x'`）不会被文件覆盖。
 *   这样临时试一次不必改文件。
 * - 只识别最简单的 `KEY=VALUE`：支持 `#` 注释、空行、值两端的引号。
 *   **不**做变量插值、不解析多行值 —— 这不是 dotenv 的替代品，
 *   够用即止（不想为此引入依赖）。
 * - 文件不存在时静默跳过（用默认值）。
 *
 * @returns 实际载入的键数（供 `node lib.js config` 展示）
 */
function loadDotEnv() {
  const file = path.join(__dirname, '.env');
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    return 0;   // 没有 .env 是常态（内网默认值就能跑）
  }
  let count = 0;
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq <= 0) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    // 去掉成对的引号（值里含空格/井号时需要）
    if (value.length >= 2
        && ((value.startsWith('"') && value.endsWith('"'))
            || (value.startsWith("'") && value.endsWith("'")))) {
      value = value.slice(1, -1);
    }
    // 已存在的环境变量优先 —— 命令行显式设的不会被文件覆盖
    if (process.env[key] === undefined) {
      process.env[key] = value;
      count += 1;
    }
  }
  return count;
}

const DOTENV_LOADED = loadDotEnv();

/* ── 配置（全部走环境变量，带内网默认值） ─────────────── */

/**
 * COSP 地址。`COSP_BASE_URL` 可覆盖，接受 `http://host:port` 或裸 `host:port`。
 *
 * <p>收敛到一处的原因：四个 `f-*.js` 曾各自 `http.request` 并重复写死
 * `host: 'localhost', port: 11434` —— 改地址要动 5 个文件，**漏一个不会报错**，
 * 它会连到默认端口去，测试看着正常但打的是别的服务。
 */
function parseBaseUrl(raw) {
  const s = (raw || 'http://localhost:11434').trim();
  const withScheme = /^https?:\/\//i.test(s) ? s : `http://${s}`;
  try {
    const u = new URL(withScheme);
    return {
      protocol: u.protocol.replace(':', ''),
      host: u.hostname,
      port: Number(u.port || (u.protocol === 'https:' ? 443 : 80)),
    };
  } catch {
    throw new Error(`COSP_BASE_URL 无法解析: ${raw}`);
  }
}

const COSP = parseBaseUrl(process.env.COSP_BASE_URL);

/** 管理端点登录凭证。改过默认密码的环境用环境变量覆盖。 */
const ADMIN_USER = process.env.COSP_ADMIN_USER || 'root';
const ADMIN_PASS = process.env.COSP_ADMIN_PASS || 'root';

/**
 * 上游 mock 的供应商 key（`[key] model` 里的 key）。
 *
 * <p>默认 `mock`。若你的 mock 供应商叫别的名字（或另建了一个），
 * 用 `COSP_MOCK_PROVIDER` 覆盖 —— 不必改脚本。
 */
const MOCK_PROVIDER = process.env.COSP_MOCK_PROVIDER || 'mock';

/** 只勾 MESSAGES 的翻译测试供应商 key（D 组用）。 */
const TRANSLATE_PROVIDER = process.env.COSP_TRANSLATE_PROVIDER || 'translatemock';

const TOKEN_FILE = path.join(__dirname, 'token.txt');
const MOCK_LOG = path.join(__dirname, 'mock.log');

/** mock 侧日志的标记字符。 */
const MARK_CLOSE = '\u25a0';   // ■ 连接关闭（每请求恰好一条 → 请求次数的精确计数）
const MARK_ENTER = '\u25b6';   // ▶ 请求进入

/** 打印当前配置，便于排查「为什么打到别的地址去了」。 */
function printConfig() {
  console.log('  来源             : ' + (DOTENV_LOADED > 0
    ? `.env（载入 ${DOTENV_LOADED} 项；未列出的用默认值。命令行环境变量优先于 .env）`
    : '默认值（无 .env 文件）'));
  console.log(`  COSP 地址        : ${COSP.protocol}://${COSP.host}:${COSP.port}` +
    (process.env.COSP_BASE_URL ? '  (来自 COSP_BASE_URL)' : '  (默认)'));
  console.log(`  管理登录         : ${ADMIN_USER}` +
    (process.env.COSP_ADMIN_USER ? '  (来自 COSP_ADMIN_USER)' : '  (默认)'));
  console.log(`  mock 供应商 key  : ${MOCK_PROVIDER}` +
    (process.env.COSP_MOCK_PROVIDER ? '  (来自 COSP_MOCK_PROVIDER)' : '  (默认)'));
  console.log(`  翻译供应商 key   : ${TRANSLATE_PROVIDER}` +
    (process.env.COSP_TRANSLATE_PROVIDER ? '  (来自 COSP_TRANSLATE_PROVIDER)' : '  (默认)'));
}

/* ── 认证 ─────────────────────────────────────────────── */

let cachedToken = null;

/** 读取已保存的 JWT；不存在时返回 null。 */
function readToken() {
  if (cachedToken) return cachedToken;
  try {
    cachedToken = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
    return cachedToken;
  } catch {
    return null;
  }
}

/* ── 基础请求 ─────────────────────────────────────────── */

/**
 * 底层 HTTP 请求，**地址与认证统一取自本模块的配置**。
 *
 * <p>四个 `f-*.js` 需要自己控制连接生命周期（长挂起、手动 destroy），无法走
 * {@link request} 的「收集完再返回」模式；它们用本函数建连，
 * 从而不必各自重复写死 `host: 'localhost', port: 11434`（那样改地址要动 5 个文件，
 * 且漏一个不会报错 —— 它会连到默认端口，测试看着正常但打的是别的服务）。
 *
 * <p>请求体已在本函数内写出并 `end()`，调用方拿到的是**已发出**的请求，
 * 只需监听 `response` / `error`。
 *
 * @param opts.path    路径
 * @param opts.method  方法，默认 GET（传了 body 则 POST）
 * @param opts.body    请求体对象（自动 JSON 序列化 + JSON 头）
 * @param opts.headers 追加头
 * @param opts.auth    是否带 JWT（管理端点）
 * @param opts.onResponse 响应回调 `(res) => void`，可选
 * @returns {http.ClientRequest}
 */
function httpRequest(opts) {
  const payload = opts.body === undefined ? null : JSON.stringify(opts.body);
  const headers = { ...(opts.headers || {}) };
  if (payload) {
    headers['Content-Type'] = 'application/json';
    headers['Content-Length'] = Buffer.byteLength(payload);
  }
  if (opts.auth) {
    const token = readToken();
    if (!token) throw new Error('缺少 token.txt —— 先跑 `node lib.js login`');
    headers.Authorization = `Bearer ${token}`;
  }

  const req = http.request({
    protocol: `${COSP.protocol}:`,
    host: COSP.host,
    port: COSP.port,
    path: opts.path,
    method: opts.method || (payload ? 'POST' : 'GET'),
    headers,
  }, opts.onResponse);
  if (payload) req.write(payload);
  req.end();
  return req;
}

/**
 * 打一次请求，收集完整响应。
 *
 * @param opts.path      路径（如 /v1/chat/completions）
 * @param opts.body      请求体对象（自动 JSON 序列化；传 undefined 则发 GET）
 * @param opts.auth      是否带 JWT（管理端点需要）
 * @param opts.timeoutMs 超时；超时后销毁连接并返回已收到的内容
 * @param opts.onEvent   每收到一个 SSE `data:` 帧调用一次
 */
function request(opts) {
  return new Promise((resolve) => {
    const started = Date.now();
    const frames = [];
    let raw = '';
    let settled = false;
    let resp = null;

    const finish = (note) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve({
        status: resp ? resp.statusCode : 0,
        headers: resp ? resp.headers : {},
        raw,
        frames,
        ms: Date.now() - started,
        note,
      });
    };

    // 用 httpRequest 建连（地址 / 头 / body / auth 都由它处理），此处只管收流。
    const req = httpRequest({
      path: opts.path,
      method: opts.method,
      body: opts.body,
      auth: opts.auth,
      onResponse: (res) => {
        resp = res;
        res.setEncoding('utf8');
        res.on('data', (chunk) => {
          raw += chunk;
          for (const line of chunk.split('\n')) {
            if (line.startsWith('data:')) {
              const data = line.slice(5).trim();
              frames.push(data);
              if (opts.onEvent) opts.onEvent(data, frames.length);
            }
          }
        });
        res.on('end', () => finish('响应结束'));
        res.on('error', (err) => finish(`响应错误 ${err.code || err.message}`));
      },
    });

    // 超时必须在请求发出**之前**挂上：否则连不上时（服务没起）永远不触发。
    const timer = setTimeout(() => finish('超时'), opts.timeoutMs || 30000);
    req.on('error', (err) => finish(`连接错误 ${err.code || err.message}`));

    if (opts.abortAfterMs) {
      setTimeout(() => { req.destroy(); finish('主动中断'); }, opts.abortAfterMs);
    }
  });
}

/* ── 管理端点 ─────────────────────────────────────────── */

/** 最近 N 条调用日志。 */
async function recentLogs(n = 5) {
  const r = await request({ path: `/config/api/logs?pageSize=${n}`, auth: true });
  try {
    return JSON.parse(r.raw).items || [];
  } catch {
    return [];
  }
}

/** 当前最新日志 id（用作 {@link waitForLog} 的基准）。 */
async function latestLogId() {
  const items = await recentLogs(1);
  return items.length > 0 ? items[0].id : 0;
}

/**
 * 等到出现指定模型名的新日志（id 大于 sinceId）。
 *
 * <p>按 id 递增判定「新」而不是按时间：同一轮测试会连续打同一模型多次，
 * 时间戳粒度是秒级，只按模型名找会命中上一轮。
 *
 * <p>**注意用无前缀名** —— 日志的 `model_name` 列存的是路由后剥掉前缀的名字。
 */
async function waitForLog(model, sinceId = 0, timeoutMs = 40000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const items = await recentLogs(15);
    const hit = items.find((l) => l && l.model_name === model && l.id > sinceId);
    if (hit) return hit;
    await new Promise((r) => setTimeout(r, 500));
  }
  return null;
}

/* ── mock 侧计数（重试的唯一可靠判据） ────────────────── */

function mockMark() {
  try {
    return fs.readFileSync(MOCK_LOG, 'utf8').split('\n').length;
  } catch {
    return 0;
  }
}

/**
 * 数 mark 之后该场景的请求次数。
 *
 * <p>按 `■ 连接关闭` 行计（每请求恰好一条）。不用 `▶ 请求进入` 行：
 * 部分场景复用同一实现函数，日志里的场景名可能与请求的 model 名不同。
 *
 * <p>**用无前缀场景名** —— COSP 发往上游时已剥掉前缀。
 */
function requestCount(model, mark) {
  return fs.readFileSync(MOCK_LOG, 'utf8').split('\n').slice(mark)
    .filter((l) => l.includes(MARK_CLOSE) && l.includes(`model=${model}`)).length;
}

/** 抽 mark 之后的请求进入时间戳（秒），用于观察退避节奏。 */
function enterSecs(model, mark) {
  return fs.readFileSync(MOCK_LOG, 'utf8').split('\n').slice(mark)
    .filter((l) => l.includes(MARK_ENTER) && l.includes(`model=${model}`))
    .map((l) => {
      const m = l.match(/\[(\d\d):(\d\d):(\d\d)\./);
      return m ? (+m[1]) * 3600 + (+m[2]) * 60 + (+m[3]) : null;
    })
    .filter((x) => x !== null);
}

/**
 * 轮询等计数稳定（不再增长）后返回。
 *
 * <p>必须轮询：mock 的 stdout 经 `Tee-Object` 落盘有缓冲，
 * 请求刚结束时日志可能还没写进去 —— 直接读一次会得到 0。
 */
async function settledCount(model, mark, { minWaitMs = 500, quietMs = 700, maxWaitMs = 5000 } = {}) {
  await new Promise((r) => setTimeout(r, minWaitMs));
  let last = requestCount(model, mark);
  let lastChange = Date.now();
  const t0 = Date.now();
  while (Date.now() - t0 < maxWaitMs) {
    await new Promise((r) => setTimeout(r, 200));
    const now = requestCount(model, mark);
    if (now !== last) { last = now; lastChange = Date.now(); }
    else if (Date.now() - lastChange >= quietMs) break;
  }
  return last;
}

/* ── 高层封装 ─────────────────────────────────────────── */

/**
 * 打一次聊天请求并数出上游真实被调次数。
 *
 * @param model    **无前缀**场景名（函数内部加 `[providerKey] ` 前缀）
 * @param opts.providerKey 供应商 key，默认取 {@link MOCK_PROVIDER}（可由环境变量覆盖）
 */
async function probe(model, { stream = true, timeoutMs = 60000, providerKey = MOCK_PROVIDER, apiPath = '/v1/chat/completions' } = {}) {
  const mark = mockMark();
  const qualified = `[${providerKey}] ${model}`;
  const body = apiPath === '/v1/messages'
    ? { model: qualified, stream, max_tokens: 64, messages: [{ role: 'user', content: 'hi' }] }
    : { model: qualified, stream, messages: [{ role: 'user', content: 'hi' }] };
  const t0 = Date.now();
  const resp = await request({ path: apiPath, body, timeoutMs });
  const count = await settledCount(model, mark);
  return { resp, count, secs: enterSecs(model, mark), wallMs: Date.now() - t0 };
}

/** 相邻时间戳的间隔（秒）。 */
function gaps(secs) {
  const out = [];
  for (let i = 1; i < secs.length; i += 1) out.push(secs[i] - secs[i - 1]);
  return out;
}

/* ── 打印 ─────────────────────────────────────────────── */

function h(title) {
  console.log(`\n${'═'.repeat(72)}\n  ${title}\n${'═'.repeat(72)}`);
}
function ok(msg) { console.log(`  ✓ ${msg}`); }
function bad(msg) { console.log(`  ✗ ${msg}`); }
function info(msg) { console.log(`    ${msg}`); }

/* ── CLI：登录取 token ────────────────────────────────── */

async function login() {
  const r = await request({
    path: '/auth/login',
    body: { username: ADMIN_USER, password: ADMIN_PASS },
  });
  if (r.status !== 200) {
    console.error(`登录失败 HTTP ${r.status}: ${r.raw.slice(0, 200)}`);
    process.exit(1);
  }
  const token = JSON.parse(r.raw).token;
  fs.writeFileSync(TOKEN_FILE, token, 'utf8');
  console.log(`✓ token 已写入 ${TOKEN_FILE}（长度 ${token.length}）`);
}

if (require.main === module) {
  const cmd = process.argv[2];
  if (cmd === 'login') {
    console.log('使用配置：');
    printConfig();
    login().catch((e) => { console.error(e.message); process.exit(1); });
  } else if (cmd === 'config') {
    console.log('当前配置：');
    printConfig();
  } else {
    console.log('用法: node lib.js [login|config]');
    console.log('');
    console.log('配置来源（优先级从高到低）：');
    console.log('  1. 命令行环境变量   $env:COSP_ADMIN_PASS=\'xxx\'  （只影响当前 PowerShell 会话）');
    console.log('  2. 本目录的 .env    见 .env.example（会自动读取，不必加 --env-file）');
    console.log('  3. 代码里的默认值   内网单机开发环境即默认值，见 README');
    console.log('');
    console.log('可配置的环境变量：');
    console.log('  COSP_BASE_URL           COSP 地址，默认 http://localhost:11434');
    console.log('  COSP_ADMIN_USER         登录用户名，默认 root');
    console.log('  COSP_ADMIN_PASS         登录密码，默认 root');
    console.log('  COSP_MOCK_PROVIDER      上游 mock 供应商 key，默认 mock');
    console.log('  COSP_TRANSLATE_PROVIDER 翻译供应商 key，默认 translatemock');
    console.log('');
    console.log('（本文件是库；测试脚本见 a-guard.js / b-retry.js / c-direct.js / d-translate.js / f-*.js）');
  }
}

module.exports = {
  COSP,
  ADMIN_USER,
  ADMIN_PASS,
  MOCK_PROVIDER,
  TRANSLATE_PROVIDER,
  DOTENV_LOADED,
  MARK_CLOSE,
  MARK_ENTER,
  printConfig,
  readToken,
  httpRequest,
  request,
  recentLogs,
  latestLogId,
  waitForLog,
  mockMark,
  requestCount,
  enterSecs,
  settledCount,
  probe,
  gaps,
  h,
  ok,
  bad,
  info,
};
