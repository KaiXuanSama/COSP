#!/usr/bin/env node
'use strict';

/**
 * COSP 测试用模拟上游供应商 —— 非流式（stream=false）专用。
 *
 * 与 mock-upstream（流式）刻意分成两个服务，而不是在一个服务里按 stream 分支：
 * 两种模式的病态形状**几乎不重叠**。流式那边「吐了几个 chunk 后停住」这类场景在非流式里
 * 根本不存在（响应是一次性的，没有「中途」），反过来「0 字节 body」「JSON 截断」
 * 「无视 stream=false 回 SSE」这类形状也是非流式独有。合在一个文件里会让两套场景清单
 * 互相污染，各自的 switch 里塞满对另一模式无意义的 case。
 *
 * 特性：
 *  - 纯 Node 内置 http 模块，零依赖，无需 npm install。
 *  - **严格校验 stream 字段**：收到 stream=true 直接 400。这是本 mock 与另外两个 mock
 *    最大的行为差异 —— mock-upstream / mock-cosp 都静默忽略 stream，导致「拿它们验证
 *    非流式」看起来能跑其实无效。这里让它响亮地失败，等于给 COSP 侧免费加了一道
 *    「有没有误发流式」的断言。
 *  - 详细日志：每个请求的模型、stream 值、响应形态都打印。
 *
 * 启动：node mock-nonstream.js  （或经 npm run mock:nonstream）
 */

const http = require('http');

// ── 可调参数（直接改这里）─────────────────────────────
const PORT = Number(process.env.MOCK_NONSTREAM_PORT || 8082);
/** hang-response 卡住整个响应的时长（毫秒）。默认 10 分钟。 */
const HANG_RESPONSE_MS = 10 * 60 * 1000;
/** slow-response 延迟多久才返回完整响应（毫秒）。 */
const SLOW_RESPONSE_MS = 5 * 1000;
/** retry-then-succeed 在第几次请求时成功（前 N-1 次返回 500）。 */
const RETRY_SUCCESS_ATTEMPT = 3;
/** retry-then-succeed 计数器空闲清零时长（毫秒）：最后一次请求后超过此时长无新请求则清零。 */
const RETRY_RESET_MS = 30 * 1000;
// ────────────────────────────────────────────────────

/**
 * 模型清单：name -> 行为描述（供 /v1/models 输出与文档参考）。
 *
 * 命名统一带 `ns-` 前缀（non-stream）：与流式 mock 的场景名不撞，
 * 这样两个 mock 供应商可以同时在 COSP 里启用 —— ProviderRouteResolver 对不带前缀的
 * 模型名要求**唯一匹配**，重名会导致两边都路由不到。
 */
const MODELS = [
  { id: 'ns-normal', desc: '正常非流式响应，完整 message.content + 真实 usage' },
  { id: 'ns-empty-content', desc: '200 + 合法 JSON，但 message.content 为空串（应空响应兜底重发）' },
  { id: 'ns-empty-usage-zero', desc: '空正文 + 全 0 usage（应空响应兜底重发）' },
  { id: 'ns-empty-choices', desc: '200 + choices 为空数组（应空响应兜底重发）' },
  { id: 'ns-empty-body', desc: '200 + 0 字节 body（应空响应兜底重发，耗尽后放行空 body）' },
  { id: 'ns-tool-call', desc: '纯工具调用，无正文（对照组：不应判定为空）' },
  { id: 'ns-reasoning-only', desc: '只有 reasoning_content 无 content（对照组：思考链不算空）' },
  { id: 'ns-malformed-json', desc: '200 + 残缺 JSON 文本（判定应保守放行，不判空）' },
  { id: 'ns-truncated', desc: '声明 Content-Length 后只写一半就断开 socket' },
  { id: 'ns-sse-despite-nonstream', desc: '无视 stream=false，返回 text/event-stream' },
  { id: 'ns-error-500', desc: '返回 500（COSP 应重试）' },
  { id: 'ns-error-401', desc: '返回 401（COSP 应快速失败不重试）' },
  { id: 'ns-retry-then-succeed', desc: '前 2 次请求返回 500，第 3 次正常回复（验证与流式共用同一份重试预算）' },
  { id: 'ns-hang-response', desc: '收到请求后长时间不返回（默认 10 分钟，验证取消路径）' },
  { id: 'ns-slow-response', desc: '延迟 5s 后返回完整响应' },
];

/** 简单时间戳日志。 */
function log(...args) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}]`, ...args);
}

/** 正常回复的正文（非流式一次性给全，不需要切 token）。 */
const NORMAL_CONTENT = '这是一条来自 mock-nonstream 的完整非流式回复，用于验证 COSP 的 stream=false 路径。';

/**
 * 构造一个 OpenAI 风格的非流式响应对象。
 *
 * 与流式 chunk 的三处结构性差异，也正是 COSP 侧判定器必须另开入口的原因：
 *  - object 是 `chat.completion` 而非 `chat.completion.chunk`；
 *  - 载荷在 `choices[].message` 而非 `choices[].delta`；
 *  - usage 是**顶层字段**而非尾帧夹带。
 *
 * @param message 放进 choices[0].message 的对象；传 null 表示构造空 choices
 */
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

/** 写 JSON 响应并结束。 */
function sendJson(res, status, payload, label, model) {
  const raw = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
  log(`✓ ${label}  model=${model}  bytes=${Buffer.byteLength(raw)}`);
}

/**
 * 处理 /v1/chat/completions，按模型名分派行为。
 *
 * <p>调用方已校验过 stream 不为 true，此处无需再关心传输模式。
 */
function handleChat(req, res, model) {
  const id = `chatcmpl-mock-ns-${Date.now()}`;

  // 客户端断开时打印，便于观察 COSP 取消 / 断连行为。
  req.on('aborted', () => log(`⨯ 客户端断开  model=${model}`));
  res.on('close', () => log(`■ 连接关闭  model=${model}`));

  switch (model) {
    case 'ns-normal':
      return nsNormal(res, id, model);
    case 'ns-empty-content':
      return nsEmptyContent(res, id, model);
    case 'ns-empty-usage-zero':
      return nsEmptyUsageZero(res, id, model);
    case 'ns-empty-choices':
      return nsEmptyChoices(res, id, model);
    case 'ns-empty-body':
      return nsEmptyBody(res, model);
    case 'ns-tool-call':
      return nsToolCall(res, id, model);
    case 'ns-reasoning-only':
      return nsReasoningOnly(res, id, model);
    case 'ns-malformed-json':
      return nsMalformedJson(res, id, model);
    case 'ns-truncated':
      return nsTruncated(res, id, model);
    case 'ns-sse-despite-nonstream':
      return nsSseDespiteNonstream(res, id, model);
    case 'ns-error-500':
      return errorResponse(res, 500, 'mock nonstream error (500)', model);
    case 'ns-error-401':
      return errorResponse(res, 401, 'mock nonstream unauthorized (401)', model);
    case 'ns-retry-then-succeed':
      return nsRetryThenSucceed(res, id, model);
    case 'ns-hang-response':
      return nsHangResponse(res, model);
    case 'ns-slow-response':
      return nsSlowResponse(res, id, model);
    default:
      // 未知模型：当作 ns-normal 处理，方便随手测试。
      log(`? 未知模型 ${model}，按 ns-normal 处理`);
      return nsNormal(res, id, model);
  }
}

/** ns-normal：正常非流式响应，完整正文 + 真实 usage。 */
function nsNormal(res, id, model) {
  const body = completionBody(
    id, model,
    { role: 'assistant', content: NORMAL_CONTENT },
    'stop',
    { prompt_tokens: 26, completion_tokens: 41, total_tokens: 67 },
  );
  sendJson(res, 200, body, 'ns-normal 已返回完整响应', model);
}

// ── 空响应场景（验证 COSP 非流式空响应兜底）───────────────────────────────
/**
 * ns-empty-content：200 + 结构完整的 JSON，但 message.content 是空串。
 *
 * <p>对应流式的 empty-stream —— 同一个上游行为（中转站抽风返回空回复）在两种传输模式下的
 * 不同呈现。判定口径必须一致：切一下 stream 开关结论就不同，那才是真的怪。
 */
function nsEmptyContent(res, id, model) {
  const body = completionBody(
    id, model,
    { role: 'assistant', content: '' },
    'stop',
    { prompt_tokens: 26, completion_tokens: 0, total_tokens: 26 },
  );
  sendJson(res, 200, body, 'ns-empty-content 已返回空正文', model);
}

/**
 * ns-empty-usage-zero：空正文 + 全 0 usage。
 *
 * <p>全 0 usage <strong>不是判据</strong>，只是伴随现象：该场景被判空纯粹因为正文本就是空的。
 * 不少中转站正常回复也不吐 usage 或吐全 0，把它当条件会误伤这些正常响应。
 */
function nsEmptyUsageZero(res, id, model) {
  const body = completionBody(
    id, model,
    { role: 'assistant', content: '' },
    'stop',
    { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
  );
  sendJson(res, 200, body, 'ns-empty-usage-zero 已返回空正文 + 全 0 usage', model);
}

/**
 * ns-empty-choices：200 + choices 为空数组。
 *
 * <p>非流式独有的一档：流式的 choices 逐帧到达，空数组只是某一帧的形态；
 * 非流式的空 choices 意味着整个响应没有任何候选回复。
 */
function nsEmptyChoices(res, id, model) {
  const body = completionBody(id, model, null, null, null);
  sendJson(res, 200, body, 'ns-empty-choices 已返回空 choices 数组', model);
}

/**
 * ns-empty-body：200 + <strong>0 字节</strong> body。
 *
 * <p>应触发空响应兜底重发，耗尽后放行空 body（透传上游真实返回）。
 *
 * <p>此场景曾是一个真实缺陷的入口：`chatCompletion` 末尾的
 * `.map(entity -> entity.getBody())` 拿到 null，Reactor 不允许 null 会抛 NPE；
 * 而 NPE 不在可重试判定范围内、且已错过 retryWhen 的位置，最终落到控制器的
 * 「无法连接到上游服务」502 分支 —— 上游明明连上了。判定前置到 `.map` 之前后已修复，
 * 保留此场景作为回归哨兵：若它又开始立刻返回 502，说明判定位置被挪动了。
 */
function nsEmptyBody(res, model) {
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': 0,
  });
  res.end();
  log(`✓ ns-empty-body 已返回 200 + 0 字节 body  model=${model}`);
}

/**
 * ns-tool-call：纯工具调用，无正文。
 *
 * <p><strong>对照组</strong>：工具调用是实质载荷，不该触发兜底。
 * 若这个场景也被重发，说明判定把「无正文」误当成了「空」。
 */
function nsToolCall(res, id, model) {
  const body = completionBody(
    id, model,
    {
      role: 'assistant',
      content: null,
      tool_calls: [{
        id: 'call_mock_ns_1',
        type: 'function',
        function: { name: 'get_weather', arguments: '{"city":"Hangzhou"}' },
      }],
    },
    'tool_calls',
    { prompt_tokens: 31, completion_tokens: 18, total_tokens: 49 },
  );
  sendJson(res, 200, body, 'ns-tool-call 已返回纯工具调用', model);
}

/**
 * ns-reasoning-only：只有 reasoning_content，没有 content。
 *
 * <p><strong>对照组</strong>：思考链也是实质载荷，不该判空。同时这是流式 mock 的既有空缺 ——
 * mock-upstream 全文没有任何 reasoning 帧构造，思考链场景一直没法物理复现。
 * 此场景还能顺带验证非流式缺失的 reasoning fallback（只有思考链无正文时应转为正文）。
 */
function nsReasoningOnly(res, id, model) {
  const body = completionBody(
    id, model,
    {
      role: 'assistant',
      content: '',
      reasoning_content: '让我想想……用户想验证非流式路径，那么应该检查 message 而不是 delta。',
    },
    'stop',
    { prompt_tokens: 26, completion_tokens: 33, total_tokens: 59 },
  );
  sendJson(res, 200, body, 'ns-reasoning-only 已返回纯思考链', model);
}

/**
 * ns-malformed-json：200 + 残缺 JSON 文本。
 *
 * <p>检验判定器「解析失败一律保守放行」这条口径：宁可放行一个没见过的格式，
 * 也不要因为结构陌生就判空重试。此场景**不应**触发兜底。
 */
function nsMalformedJson(res, id, model) {
  const raw = `{"id":"${id}","object":"chat.completion","choices":[{"index":0,"message":{"role":"assist`;
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
  log(`✓ ns-malformed-json 已返回残缺 JSON（${Buffer.byteLength(raw)} 字节）  model=${model}`);
}

/**
 * ns-truncated：声明完整 Content-Length，却只写一半就销毁 socket。
 *
 * <p>非流式独有的传输层病态：流式断流走的是 SSE 帧序列中断，与此完全不同的代码路径。
 * 下游应看到连接异常（可重试的网络类失败），而非一个内容为空的成功响应。
 */
function nsTruncated(res, id, model) {
  const full = JSON.stringify(completionBody(
    id, model,
    { role: 'assistant', content: NORMAL_CONTENT },
    'stop',
    { prompt_tokens: 26, completion_tokens: 41, total_tokens: 67 },
  ));
  const half = full.slice(0, Math.floor(full.length / 2));
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(full),
  });
  res.write(half);
  // 不 end：直接销毁底层 socket，制造「声明了长度却没写完」的截断。
  res.socket.destroy();
  log(`✗ ns-truncated 已写 ${Buffer.byteLength(half)}/${Buffer.byteLength(full)} 字节后断开 socket  model=${model}`);
}

/**
 * ns-sse-despite-nonstream：无视 stream=false，仍返回 text/event-stream。
 *
 * <p>最现实的一档：不认 stream 参数的中转站相当常见。COSP 的非流式路径用
 * `.toEntity(String.class)` 收响应体，会把整段 SSE 文本当成 body 原样透传给下游，
 * 下游拿到的是一堆 `data: {...}` 而不是一个 JSON 对象。
 */
function nsSseDespiteNonstream(res, id, model) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });
  const chunk = {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: { role: 'assistant', content: '我无视了 stream=false。' }, finish_reason: null }],
  };
  const finish = {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
  };
  res.write(`data: ${JSON.stringify(chunk)}\n\n`);
  res.write(`data: ${JSON.stringify(finish)}\n\n`);
  res.write('data: [DONE]\n\n');
  res.end();
  log(`✓ ns-sse-despite-nonstream 已用 SSE 回应非流式请求  model=${model}`);
}

/** 返回 HTTP 错误响应。 */
function errorResponse(res, status, message, model) {
  const raw = JSON.stringify({ error: { message, type: 'mock_error', code: status } });
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
  log(`✗ 返回错误 ${status}  model=${model}`);
}

/**
 * ns-retry-then-succeed：前 N-1 次请求返回 500，第 N 次正常回复。
 *
 * <p>验证「非流式与流式共用同一份重试预算」的唯一手段：如果非流式另起了一套重试配置，
 * 改动 retry_max_attempts 时两边表现会不一致。
 *
 * <p>计数器是模块级共享的，成功后或空闲超过 RETRY_RESET_MS 自动清零，
 * 方便反复触发而不必重启服务。
 */
let retryAttempt = 0;
let retryLastAt = 0;
function nsRetryThenSucceed(res, id, model) {
  const now = Date.now();
  if (retryLastAt > 0 && now - retryLastAt > RETRY_RESET_MS) {
    log(`↺ retry 计数器空闲 ${Math.round((now - retryLastAt) / 1000)}s，已清零`);
    retryAttempt = 0;
  }
  retryLastAt = now;
  retryAttempt += 1;

  if (retryAttempt < RETRY_SUCCESS_ATTEMPT) {
    log(`✗ ns-retry-then-succeed 第 ${retryAttempt} 次请求，返回 500  model=${model}`);
    return errorResponse(res, 500, `mock nonstream retry (attempt ${retryAttempt})`, model);
  }
  log(`↻ ns-retry-then-succeed 第 ${retryAttempt} 次请求，正常回复  model=${model}`);
  retryAttempt = 0;
  return nsNormal(res, id, model);
}

/**
 * ns-hang-response：收到请求后长时间不返回任何内容。
 *
 * <p>非流式没有「首字」概念，只有「整个响应迟到」，故与流式的 hang-first-byte 语义不同：
 * 这里连响应头都不写，客户端处于纯等待状态。用于验证取消路径
 * （`Mono.firstWithSignal` + `CallCanceledException`，右键 Toast 断连 → ABORTED）。
 */
function nsHangResponse(res, model) {
  log(`… ns-hang-response 已收到请求，挂起 ${HANG_RESPONSE_MS / 1000}s 不返回  model=${model}`);
  const timer = setTimeout(() => {
    if (res.writableEnded) return;
    const body = completionBody(
      `chatcmpl-mock-ns-${Date.now()}`, model,
      { role: 'assistant', content: '（终于来了）' },
      'stop',
      { prompt_tokens: 26, completion_tokens: 6, total_tokens: 32 },
    );
    sendJson(res, 200, body, 'ns-hang-response 挂起结束，已返回', model);
  }, HANG_RESPONSE_MS);
  res.on('close', () => clearTimeout(timer));
}

/** ns-slow-response：延迟若干秒后返回完整响应。 */
function nsSlowResponse(res, id, model) {
  log(`… ns-slow-response 延迟 ${SLOW_RESPONSE_MS / 1000}s 后返回  model=${model}`);
  const timer = setTimeout(() => {
    if (res.writableEnded) return;
    nsNormal(res, id, model);
  }, SLOW_RESPONSE_MS);
  res.on('close', () => clearTimeout(timer));
}

/** /v1/models：列出所有场景模型。 */
function handleModels(res) {
  const data = MODELS.map((m) => ({
    id: m.id,
    object: 'model',
    created: Math.floor(Date.now() / 1000),
    owned_by: 'mock-nonstream',
  }));
  const raw = JSON.stringify({ object: 'list', data });
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
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
    const model = typeof body.model === 'string' ? body.model : 'ns-normal';

    // 严格校验 stream：本 mock 专测非流式，收到流式请求响亮地失败而不是静默照做。
    // 这一道断言的价值在于反向验证 COSP —— 若 COSP 本该发非流式却发了 stream=true，
    // 静默忽略会让整轮验证看起来通过其实无效。
    if (body.stream === true) {
      log(`✗ 拒绝流式请求  model=${model}  stream=true —— 本 mock 仅接受 stream=false`);
      log('  ↳ 流式场景请改用 mock-upstream（npm run mock:stream）');
      const raw = JSON.stringify({
        error: {
          message: 'mock-nonstream 仅接受 stream=false 的请求；流式请改用 mock-upstream（npm run mock:stream）',
          type: 'mock_error',
          code: 400,
        },
      });
      res.writeHead(400, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(raw),
      });
      return res.end(raw);
    }

    log(`▶ chat 开始  model=${model}  stream=${body.stream === undefined ? '(缺省)' : body.stream}`);
    return handleChat(req, res, model);
  }

  // 其它路径：404
  const raw = JSON.stringify({ error: { message: `not found: ${req.method} ${url}`, type: 'mock_error' } });
  res.writeHead(404, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(raw),
  });
  res.end(raw);
  log(`404  ${req.method} ${url}`);
});

server.listen(PORT, () => {
  log(`COSP 模拟上游（非流式）已启动: http://localhost:${PORT}`);
  log(`  GET  /v1/models`);
  log(`  POST /v1/chat/completions  （仅接受 stream=false，stream=true 返回 400）`);
  log('可用模型：');
  for (const m of MODELS) {
    log(`  - ${m.id.padEnd(26)} ${m.desc}`);
  }
  log('在 COSP 管理后台新增供应商，Base URL 指向 http://localhost:' + PORT + ' 即可。');
});
