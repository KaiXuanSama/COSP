#!/usr/bin/env node
'use strict';

/**
 * B 组：重试与放行（`UpstreamRetryPolicy` + `UpstreamAutoRetry`）。
 *
 * 判据是**上游 mock 被调次数**。注意两条按传输模式不同的错误传递方式
 * （见 `README.md` 的判据表）：流式恒 HTTP 200（错误在 `event: error` 帧里），
 * 非流式才透传状态码。
 *
 * 用法：node b-retry.js
 */

const { probe, gaps, request, h, ok, bad, info } = require('./lib');

const EXPECTED = {
  'fail-401': 1,        // 401 是确定性错误，绝不重试
  'retry-recover': 3,   // 前 2 次 500，第 3 次成功
};

async function main() {
  h('B 组：重试与放行');

  let fail = 0;
  const check = (cond, good, worse) => {
    if (cond) ok(good); else { fail += 1; bad(worse); }
  };

  /* ── B2 · fail-401：快速失败 ──────────────────────────── */
  console.log('\n【B2 · fail-401：快速失败，绝不重试】');
  {
    const r = await probe('fail-401', { timeoutMs: 40000 });
    info(`上游被调 ${r.count} 次  下游 HTTP=${r.resp.status} / ${r.wallMs}ms`);
    info(`响应: ${r.resp.raw.slice(0, 160).replace(/\s+/g, ' ')}`);
    check(r.count === 1, '  零重试 ✓（401 是确定性错误）', `  被调 ${r.count} 次 —— 401 不该重试！`);
    // 流式恒 200：错误经 event:error 帧传递，不是 HTTP 状态码。
    check(r.resp.status === 200 && r.resp.raw.includes('event:error'),
      '  流式：HTTP 200 + event:error 帧 ✓（SSE 已开始，状态码无法再改）',
      `  期望 HTTP 200 + event:error，实际 ${r.resp.status}`);
    check(r.resp.raw.includes('"code":401'), '  错误帧里带 code=401 ✓', '  错误帧里未找到 code=401');
  }

  /* ── B2b · fail-401 非流式：状态码原样透传 ────────────── */
  console.log('\n【B2b · fail-401 非流式：HTTP 状态码原样透传】');
  {
    const r = await probe('fail-401', { stream: false, timeoutMs: 40000 });
    info(`上游被调 ${r.count} 次  下游 HTTP=${r.resp.status} / ${r.wallMs}ms`);
    check(r.count === 1, '  零重试 ✓', `  被调 ${r.count} 次`);
    check(r.resp.status === 401, '  下游 HTTP = 401 ✓（非流式透传上游状态码）',
      `  下游 HTTP=${r.resp.status}（期望 401）`);
  }

  /* ── B1b · retry-recover：第 3 次成功 ─────────────────── */
  console.log('\n【B1b · retry-recover：前 2 次 500、第 3 次正常】');
  {
    const r = await probe('retry-recover', { timeoutMs: 90000 });
    const g = gaps(r.secs);
    info(`上游被调 ${r.count} 次  下游 HTTP=${r.resp.status} / ${r.wallMs}ms  收到 ${r.resp.frames.length} 帧`);
    info(`退避间隔(秒) = ${g.join(', ')}`);
    check(r.count === EXPECTED['retry-recover'], `  恰好 ${EXPECTED['retry-recover']} 次 ✓（成功后停止）`,
      `  被调 ${r.count} 次（期望 ${EXPECTED['retry-recover']}）`);
    check(r.resp.status === 200, '  下游拿到 200 ✓（重试中途成功）', `  下游 HTTP=${r.resp.status}`);
    // 退避带 ±50% 抖动，且 mock 时间戳只有秒级精度 —— 只断言量级。
    check(g.length >= 1 && g[0] >= 1 && g[0] <= 8, `  首退约 ${g[0]}s ✓（生产配置 2s + 抖动）`,
      `  首退 ${g[0]}s（期望 1–8s）`);
  }

  /* ── B1 · retry-5xx：跑满预算 ─────────────────────────── */
  console.log('\n【B1 · retry-5xx：重试到预算耗尽】');
  {
    const r = await probe('retry-5xx', { timeoutMs: 200000 });
    const g = gaps(r.secs);
    info(`上游被调 ${r.count} 次  下游 HTTP=${r.resp.status} / ${(r.wallMs / 1000).toFixed(1)}s`);
    info(`退避间隔(秒) = ${g.join(', ')}`);
    // retry_max_attempts=7 的语义是「重试 7 次」→ 总请求 8 次。
    check(r.count === 8, '  共 8 次 ✓（1 首次 + 7 重试 = retry_max_attempts 的语义）',
      `  被调 ${r.count} 次（期望 8）`);
    check(g.length > 0 && g[g.length - 1] <= 45, `  末次退避 ${g[g.length - 1]}s ✓（上限 30s + 抖动）`,
      `  末次退避 ${g[g.length - 1]}s 超出上限太多`);
  }

  /* ── B3 · retry-429 ───────────────────────────────────── */
  console.log('\n【B3 · retry-429：429 在白名单内，应重试】');
  {
    // 预算跑满的用例：mock 侧实测跨度 ~102s，必须给足。
    const r = await probe('retry-429', { timeoutMs: 150000 });
    info(`上游被调 ${r.count} 次  下游 HTTP=${r.resp.status} / ${(r.wallMs / 1000).toFixed(1)}s`);
    check(r.count > 1, `  重试生效（${r.count} 次）✓`, `  被调 ${r.count} 次 —— 429 未触发重试！`);
  }

  /* ── B7 · retry-truncated ─────────────────────────────── */
  console.log('\n【B7 · retry-truncated：传输截断应视为可重试的网络失败】');
  {
    // 同样是跑满预算的用例（实测 ~85s）。
    const r = await probe('retry-truncated', { timeoutMs: 150000 });
    info(`上游被调 ${r.count} 次  下游 HTTP=${r.resp.status} / ${(r.wallMs / 1000).toFixed(1)}s  ${r.resp.note}`);
    check(r.count > 1, `  重试生效（${r.count} 次）✓`, `  被调 ${r.count} 次 —— 截断未触发重试！`);
  }

  console.log(`\n${fail === 0 ? '✓ B 组全部通过' : `✗ B 组有 ${fail} 项失败`}`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
