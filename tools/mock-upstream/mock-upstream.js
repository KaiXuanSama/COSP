#!/usr/bin/env node
'use strict';

/**
 * COSP 测试用模拟上游供应商 —— **三协议合一**。
 *
 * <h2>它替掉了什么</h2>
 * 合并前有四个独立脚本，各占一个端口，按四种互不相同的轴切分：
 * <ul>
 *   <li>`mock-upstream`（8081）—— 按**传输形态**切：只做 Chat 流式</li>
 *   <li>`mock-nonstream`（8082）—— 同样按形态切：只做 Chat 非流式</li>
 *   <li>`mock-anthropic`（8083）—— 按**协议**切：Messages 两种形态合并</li>
 *   <li>`mock-toolorder`（8084）—— 按**场景维度**切：工具调用与正文的先后顺序</li>
 * </ul>
 * 四种轴混用必然看起来乱，而且同一场景在多处各写一份（`error-500` / `error-401` /
 * `retry-then-succeed` / `hang-response` / `slow-response` 各三份，共 15 处等价代码）。
 *
 * <h2>现在的切分</h2>
 * <pre>
 * mock-upstream.js          入口：HTTP server · 路由 · /v1/models · 422 兜底
 * lib/http.js               机制：SSE 写帧 · 读 body · 日志
 * lib/transport.js          协议无关场景：错误码 / 重试 / 截断 / 挂起 / 延迟
 * protocols/chat.js         OpenAI Chat 形态的场景
 * protocols/messages.js     Anthropic Messages 形态的场景
 * protocols/responses.js    OpenAI Responses 形态的场景（待实现）
 * </pre>
 *
 * <h2>协议由端点路径决定，不由模型名决定</h2>
 * 同一个场景名在多个端点上都能打，行为按该协议的形态呈现：
 * <pre>
 * POST /chat/completions  + model=baseline-normal  → chat.js 的 baseline-normal
 * POST /messages          + model=baseline-normal  → messages.js 的 baseline-normal
 * </pre>
 * 因此 `/v1/models` 只列**一份去重清单**，且模型名不再需要 `ns-` / `at-` / `to-`
 * 这类跨进程避重前缀 —— 那类前缀的唯一用途是绕开「无前缀模型名要求唯一匹配」的
 * 路由约束，合到一个服务后那个约束自然消失。
 *
 * <h2>场景命名口径</h2>
 * **名字描述「被测的 COSP 判定点」，不描述 mock 自己的动作。**
 * 域前缀即期望：`baseline-*` 常规形态 · `blank-*` 应判空兜底 · `pass-*` 应放行 ·
 * `retry-*` 应重试 · `fail-*` 应快速失败 · `phase-*` 相位正确 · `cancel-*` 应可中断 ·
 * `translate-*` 翻译后形态正确。
 *
 * <p>合并前是另一套口径（`stall-forever` / `hang-first-byte` / `ns-sse-despite-nonstream`
 * 都在描述 mock 喂了什么），两种口径并存会让人每次重新猜「这名字是在说我喂了什么，
 * 还是在说 COSP 该做什么」。改名的依据是：选模型的人想知道的正是后者。
 *
 * <h2>传输模式的差异体现在响应码，不体现在命名</h2>
 * 有些场景只在一种模式下有意义（`cancel-stall` 对非流式无意义，`pass-sse-body`
 * 对流式无意义）。这类组合不通过改名字来区分，而是**回 422 并说明原因** ——
 * 详见 `lib/http.js` 的 `sendUnsupported`。
 *
 * 启动：node mock-upstream.js  （或 npm run mock:upstream）
 */

const http = require('http');

const {
  REQUIRED_ANTHROPIC_VERSION,
  attachLifecycleLogs,
  log,
  readJsonBody,
  requireAnthropicVersion,
  sendJson,
  sendUnsupported,
} = require('./lib/http');

const chat = require('./protocols/chat');
const messages = require('./protocols/messages');

/* ── 可调参数 ─────────────────────────────────────────── */

/** 沿用旧 `mock-upstream` 的 8081；`MOCK_UPSTREAM_PORT` 优先，`MOCK_PORT` 兼容旧用法。 */
const PORT = Number(process.env.MOCK_UPSTREAM_PORT || process.env.MOCK_PORT || 8081);

/** 三协议共用的场景注册表：`协议 id -> (场景名 -> 场景)`。 */
const REGISTRY = {
  chat: chat.scenarios(),
  messages: messages.scenarios(),
};

const PROTOCOL_MODULES = { chat, messages };

/**
 * 端点路径 → 协议 id。
 *
 * <p>路径刻意与真实上游**完全一致**（不带 `/v1`）：COSP 往 Base URL 上拼接的正是
 * `chat/completions` / `messages` / `responses`（见各上游执行器的 `endpointPath`），
 * 因此 Base URL 可以直接填 `http://localhost:8081`，不需要为 mock 特殊处理。
 *
 * <p>同时容忍带 `/v1` 的写法：那只是让人手打 curl 时少一个疑惑。
 */
const ROUTES = [
  { path: '/chat/completions', protocol: 'chat' },
  { path: '/v1/chat/completions', protocol: 'chat' },
  { path: '/messages', protocol: 'messages' },
  { path: '/v1/messages', protocol: 'messages' },
  { path: '/responses', protocol: 'responses' },
  { path: '/v1/responses', protocol: 'responses' },
];

/** Responses 协议尚未实现（下一轮补 `protocols/responses.js`）。 */
const UNIMPLEMENTED_PROTOCOLS = new Set(['responses']);

/** 默认场景名（请求不带 model、或带了未登记的名字时用）。 */
const DEFAULT_SCENARIO = 'baseline-normal';

/* ── 模型清单 ─────────────────────────────────────────── */

/**
 * 生成去重后的场景清单。
 *
 * <p>`protocols` 与 `modes` 字段列出该场景在哪些组合下可用 ——
 * 下游据此判断 422 是否会出现，管理后台里也能一眼看出「这个场景该配哪个协议」。
 */
function modelList() {
  const merged = new Map();
  for (const [protocolId, table] of Object.entries(REGISTRY)) {
    for (const [name, scenario] of Object.entries(table)) {
      if (!merged.has(name)) {
        merged.set(name, { id: name, desc: scenario.desc, protocols: new Set(), modes: new Set() });
      }
      const entry = merged.get(name);
      entry.protocols.add(protocolId);
      if (scenario.stream) entry.modes.add('stream');
      if (scenario.nonstream) entry.modes.add('nonstream');
    }
  }
  return [...merged.values()]
    .sort((a, b) => a.id.localeCompare(b.id))
    .map((entry) => ({
      id: entry.id,
      desc: entry.desc,
      protocols: [...entry.protocols].sort(),
      modes: [...entry.modes].sort(),
    }));
}

function handleModels(res) {
  const list = modelList();
  const data = list.map((m) => ({
    id: m.id,
    object: 'model',
    created: Math.floor(Date.now() / 1000),
    owned_by: 'mock-upstream',
  }));
  sendJson(res, 200, { object: 'list', data }, null, null);
  log(`↳ /v1/models 返回 ${data.length} 个场景`);
}

/* ── 请求分派 ─────────────────────────────────────────── */

/**
 * 决定「该走哪个场景的哪个模式」，或该回 422。
 *
 * @return 命中时 `{ scenario, model, mode }`；不适用时 `{ unsupported: { reason } }`
 */
function resolveScenario(protocolId, body) {
  const table = REGISTRY[protocolId];
  const model = typeof body.model === 'string' && body.model ? body.model : DEFAULT_SCENARIO;
  const mode = body.stream === true ? 'stream' : 'nonstream';
  const scenario = table[model];

  if (!scenario) {
    // 场景名在本协议没有：可能是打错了，也可能是别的协议专属的场景名。
    const elsewhere = Object.entries(REGISTRY)
      .filter(([id, t]) => id !== protocolId && t[model])
      .map(([id]) => id);
    if (elsewhere.length > 0) {
      return {
        unsupported: {
          reason: `该场景只在 ${elsewhere.join(' / ')} 端点可用，请改用对应端点或换一个场景名。`,
        },
      };
    }
    // 完全未知的名字：回落到默认场景（便于随手用一个未登记的名字试探连通性）。
    log(`? 未知场景 ${model}，按 ${DEFAULT_SCENARIO} 处理`);
    return { scenario: table[DEFAULT_SCENARIO], model: DEFAULT_SCENARIO, mode };
  }

  if (!scenario[mode]) {
    const other = mode === 'stream' ? '非流式' : '流式';
    const hint = mode === 'stream' ? '把 stream 设为 false' : '把 stream 设为 true';
    return {
      unsupported: {
        reason: `该场景只有${other}实现 —— ${hint}，`
          + `或换用两态都支持的场景（如 baseline-normal / blank-empty-content）。`,
      },
    };
  }
  return { scenario, model, mode };
}

/** 处理一次聊天请求。 */
async function handleChatRequest(req, res, protocolId, protocol) {
  if (protocolId === 'messages' && !requireAnthropicVersion(req, res, protocol.renderErrorBody)) {
    return;
  }

  const body = await readJsonBody(req);
  const mode = body.stream === true ? 'stream' : 'nonstream';
  const model = typeof body.model === 'string' && body.model ? body.model : DEFAULT_SCENARIO;
  const streamLabel = body.stream === undefined ? '(缺省→false)' : String(body.stream);

  attachLifecycleLogs(req, res, model);
  log(`▶ ${protocolId}  model=${model}  stream=${streamLabel}`);

  const resolved = resolveScenario(protocolId, body);
  if (resolved.unsupported) {
    return sendUnsupported(res, model, resolved.unsupported.reason, protocol.renderErrorBody);
  }
  const handler = resolved.scenario[mode];
  return handler(res, resolved.model);
}

/* ── server ───────────────────────────────────────────── */

const server = http.createServer(async (req, res) => {
  const rawUrl = req.url || '';
  const pathname = rawUrl.split('?')[0];

  if (req.method === 'GET' && (pathname === '/v1/models' || pathname === '/models')) {
    return handleModels(res);
  }

  const route = ROUTES.find((r) => r.path === pathname);
  if (req.method === 'POST' && route) {
    if (UNIMPLEMENTED_PROTOCOLS.has(route.protocol)) {
      log(`✗ 422 Responses 协议尚未实现  ${req.method} ${pathname}`);
      return sendJson(res, 422, chat.renderErrorBody(422,
        '打的是 /responses 端点，但本 mock 的 Responses 协议实现尚未落地。'
        + '请改用 /chat/completions（OpenAI Chat）或 /messages（Anthropic），'
        + '或在 COSP 里把该供应商的协议配置切到 CHAT / MESSAGES。'), null, null);
    }
    return handleChatRequest(req, res, route.protocol, PROTOCOL_MODULES[route.protocol]);
  }

  const raw = JSON.stringify(chat.renderErrorBody(404, `not found: ${req.method} ${rawUrl}`));
  res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(raw) });
  res.end(raw);
  log(`404  ${req.method} ${rawUrl}`);
});

server.listen(PORT, () => {
  log(`COSP 模拟上游（三协议合一）已启动: http://localhost:${PORT}`);
  log('  GET  /v1/models');
  log('  POST /chat/completions     OpenAI Chat（流式与非流式）');
  log(`  POST /messages             Anthropic Messages（流式与非流式，要求 anthropic-version: ${REQUIRED_ANTHROPIC_VERSION}）`);
  log('  POST /responses            ⚠ 尚未实现，当前返回 422');
  const list = modelList();
  log(`可用场景 ${list.length} 个（带 scope 标注；打不支持的组合会回 422）：`);
  for (const m of list) {
    log(`  - ${m.id.padEnd(34)} [${m.protocols.join(',')} · ${m.modes.join('+')}]`);
  }
  log(`Base URL 填 http://localhost:${PORT}（不带 /v1）`);
  log('供应商的协议集合至少勾一个：CHAT → /chat/completions，MESSAGES → /messages。');
});
