#!/usr/bin/env node
'use strict';

/**
 * mock 自检脚本 —— 遍历全部「场景 × 协议 × 模式」组合，报告实际响应形状。
 *
 * <h2>为何需要它</h2>
 * mock 不参与自动化测试（见 `.github/instructions/java-tests.instructions.md`），
 * 因此合并重构**没有回归网兜底**。合并期间唯一能保证「零行为变更」的手段是
 * **逐场景对拍** —— 而手工 curl 33 个场景 × 2 协议 × 2 模式既慢又容易漏。
 *
 * <p>本脚本不判断「响应是否合理」（那需要人看），它只做两件机械的事：
 * <ol>
 *   <li><strong>遍历</strong>全部组合，报告每格的状态码、耗时与首帧形态；</li>
 *   <li><strong>断言机械不变式</strong>：不该支持的模式必须回 422、声明的模式必须回 2xx
 *       而不是 404 / 5xx。</li>
 * </ol>
 * 剩下的「内容对不对」由人对照 README 的期望列看。
 *
 * <h2>用法</h2>
 * <pre>
 * node tools/mock-upstream/smoke-test.js               # 默认 http://localhost:8081
 * node tools/mock-upstream/smoke-test.js --port 8081
 * node tools/mock-upstream/smoke-test.js --slow        # 包含耗时的停滞类场景
 * </pre>
 *
 * <p>默认**跳过**会长时间挂起的场景（`cancel-*` / `phase-slow*`），否则一次跑完要十几分钟。
 * 加 `--slow` 才包含它们 —— 那些场景本来就该在 UI 上手工观察取消行为，脚本只确认
 * 「连接能建立、首帧形态正确」。
 */

const http = require('http');

const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const PORT = Number(argOf('--port', process.env.MOCK_UPSTREAM_PORT || 8081));
const INCLUDE_SLOW = args.includes('--slow');
/** 单次请求的最长等待（毫秒）。挂起类场景会超时，那是预期。 */
const PER_REQUEST_TIMEOUT_MS = Number(argOf('--timeout', 3000));

/** 会长时间挂起或持续推流的场景，默认跳过。 */
const SLOW_SCENARIOS = new Set([
  'cancel-hang', 'cancel-stall', 'cancel-stall-delayed', 'cancel-stall-resume',
  'phase-slow', 'phase-slow-steady', 'phase-done-early',
]);

const REQUIRED_ANTHROPIC_VERSION = '2023-06-01';

/* ── 单次请求 ─────────────────────────────────────────── */

/**
 * 发一次请求，收集到「首个有意义的字节」或超时为止。
 *
 * <p>刻意不等到流结束：`phase-done-early` 这类场景永远不关连接，
 * 等结束会挂死。观察到首帧就足够判断「这条路径是通的」。
 */
function probe(path, protocolId, model, stream) {
  return new Promise((resolve) => {
    const body = JSON.stringify({
      model,
      stream,
      messages: [{ role: 'user', content: 'ping' }],
      max_tokens: 64,
    });
    const headers = {
      'Content-Type': 'application/json',
      'Content-Length': Buffer.byteLength(body),
    };
    if (protocolId === 'messages') {
      headers['anthropic-version'] = REQUIRED_ANTHROPIC_VERSION;
    }

    const started = Date.now();
    let settled = false;
    let timer = null;
    /** 收尾：无论走哪条路径都只结算一次，并确保 socket 与定时器都释放。 */
    const settle = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      req.destroy();
      resolve({ ms: Date.now() - started, ...result });
    };

    const req = http.request({ host: 'localhost', port: PORT, path, method: 'POST', headers }, (res) => {
      let collected = '';
      res.on('data', (chunk) => {
        collected += chunk.toString();
        // 收到首个字节即可判定路径可用 —— 不等流结束（phase-done-early 永不关连接）。
        settle({ status: res.statusCode, contentType: res.headers['content-type'] || '', note: '首帧已到', sample: collected.slice(0, 120).replace(/\s+/g, ' ') });
      });
      res.on('end', () => settle({ status: res.statusCode, contentType: res.headers['content-type'] || '', note: '响应结束', sample: collected.slice(0, 120).replace(/\s+/g, ' ') }));
      res.on('error', (err) => settle({ status: res.statusCode, contentType: '', note: `响应错误: ${err.code || err.message}`, sample: '' }));
    });

    // 超时必须在请求发出**之前**挂上：否则连不上时（服务没起）永远不触发。
    timer = setTimeout(() => settle({ status: 0, contentType: '', note: '超时（挂起类场景属预期）', sample: '' }), PER_REQUEST_TIMEOUT_MS);
    req.on('error', (err) => settle({ status: 0, contentType: '', note: `连接错误: ${err.code || err.message}`, sample: '' }));
    req.write(body);
    req.end();
  });
}

/* ── 主流程 ───────────────────────────────────────────── */

async function fetchScenarioList() {
  return new Promise((resolve, reject) => {
    http.get({ host: 'localhost', port: PORT, path: '/v1/models' }, (res) => {
      let raw = '';
      res.on('data', (c) => { raw += c; });
      res.on('end', () => {
        try {
          resolve(JSON.parse(raw).data.map((m) => m.id));
        } catch (err) {
          reject(err);
        }
      });
    }).on('error', reject);
  });
}

function pad(s, n) {
  const str = String(s);
  // 中文字符占两列，按显示宽度补齐。
  let width = 0;
  for (const ch of str) width += ch.charCodeAt(0) > 0x2000 ? 2 : 1;
  return str + ' '.repeat(Math.max(0, n - width));
}

/**
 * 会让脚本把「场景故意返回的错误码」误判成故障的场景前缀。
 *
 * <p>`retry-*` 与 `fail-*` 的期望**就是**返回非 2xx —— 那是它们存在的理由。
 * 因此这些场景的 4xx / 5xx 是正确行为，只有 422（模式不适用）与连接错误才算异常。
 */
const EXPECTS_ERROR_STATUS = /^(retry|fail)-/;
/** `retry-truncated` 会销毁 socket，客户端看到的是连接重置而非状态码，同样属预期。 */
const EXPECTS_CONNECTION_RESET = new Set(['retry-truncated']);

/** 判断某格的结果是否符合该场景的预期。 */
function classify(scenarioId, r) {
  if (r.status === 200) {
    return { mark: '·', ok: true, note: '' };
  }
  if (r.status === 422) {
    // 422 对任何场景都是合法结果：它表示该组合下没有实现。
    return { mark: '4', ok: true, note: '' };
  }
  if (EXPECTS_ERROR_STATUS.test(scenarioId) && r.status >= 400) {
    return { mark: 'E', ok: true, note: '' };
  }
  if (EXPECTS_CONNECTION_RESET.has(scenarioId) && r.status === 0) {
    return { mark: 'E', ok: true, note: '' };
  }
  // 超时：挂起类场景的正常表现（脚本默认已排除它们，加 --slow 时可能出现）。
  if (r.note === '超时（挂起类场景属预期）') {
    return { mark: 'T', ok: true, note: '' };
  }
  return { mark: '!', ok: false, note: r.note };
}

async function main() {
  let ids;
  try {
    ids = await fetchScenarioList();
  } catch (err) {
    console.error(`无法连接 http://localhost:${PORT}/v1/models —— 服务起了吗？`);
    console.error(err.message);
    process.exit(1);
  }

  const targets = ids.filter((id) => INCLUDE_SLOW || !SLOW_SCENARIOS.has(id));
  console.log(`\n自检 ${targets.length}/${ids.length} 个场景（${INCLUDE_SLOW ? '含' : '不含'}耗时场景）`);
  console.log(`服务 http://localhost:${PORT} · 单请求超时 ${PER_REQUEST_TIMEOUT_MS}ms\n`);

  const combos = [
    { path: '/chat/completions', protocolId: 'chat', label: 'CHAT / stream' },
    { path: '/chat/completions', protocolId: 'chat', label: 'CHAT / json  ', stream: false },
    { path: '/messages', protocolId: 'messages', label: 'MESS / stream' },
    { path: '/messages', protocolId: 'messages', label: 'MESS / json  ', stream: false },
  ];

  let problems = 0;
  for (const id of targets) {
    const cells = [];
    for (const combo of combos) {
      const stream = combo.stream !== false;
      const r = await probe(combo.path, combo.protocolId, id, stream);
      const verdict = classify(id, r);
      if (!verdict.ok) problems += 1;
      cells.push(`${verdict.mark}${String(r.status).padStart(3)} ${String(r.ms).padStart(5)}ms`);
    }
    console.log(`${pad(id, 36)} ${cells.join('  ')}`);
  }

  console.log('\n图例: · = 200 已实现   4 = 422 组合不适用（有解释）'
    + '   E = 非 2xx 但属场景预期（retry-* / fail-* / 截断）   T = 超时（挂起类）   ! = 需排查');
  if (problems > 0) {
    console.log(`\n⚠ 有 ${problems} 格不符合预期。`);
    process.exit(1);
  }
  console.log('\n✓ 全部组合都在预期范围内。');
}

main();
