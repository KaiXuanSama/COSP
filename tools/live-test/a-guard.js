#!/usr/bin/env node
'use strict';

/**
 * A 组：空响应兜底（`EmptyResponseGate`）—— 判定 + 扣放 + 耗尽放行。
 *
 * 判据是**上游 mock 真实被调了几次**（`lib.js` 的 `probe`）。
 * 空白组应 >1 次（判空重试），对照组必须 ===1（零重试）。
 *
 * 用法：node a-guard.js
 */

const { probe, h, ok, bad, info } = require('./lib');

/** 期望判空 → 兜底重试（次数 > 1）。 */
const SHOULD_RETRY = [
  ['blank-empty-content', true, '/v1/chat/completions'],
  ['blank-empty-body', true, '/v1/chat/completions'],
  ['blank-zero-usage', true, '/v1/chat/completions'],
  ['blank-empty-content', true, '/v1/messages'],
  ['blank-empty-content', false, '/v1/messages'],
];

/** 期望放行 → 零重试（次数 === 1）。 */
const SHOULD_PASS = [
  ['pass-tool-call', true, '/v1/chat/completions'],
  ['pass-tool-call', false, '/v1/chat/completions'],
  ['pass-reasoning-only', true, '/v1/chat/completions'],
  ['pass-reasoning-only', false, '/v1/chat/completions'],
  ['pass-malformed', false, '/v1/chat/completions'],
  ['pass-sse-body', false, '/v1/chat/completions'],
  ['pass-thinking-only', true, '/v1/messages'],
];

async function main() {
  h('A 组：空响应兜底（判据 = 上游 mock 被调次数）');

  let fail = 0;
  const label = (m, s, p) => `${m}${s ? '' : '(ns)'}${p.includes('messages') ? '[msgs]' : ''}`;

  console.log('\n【空白组：应判空 → 兜底重试（次数 > 1）】');
  console.log('   注：每项要等重试预算跑完或超时，5 项合计约 6 分钟');
  for (const [model, stream, apiPath] of SHOULD_RETRY) {
    const r = await probe(model, { stream, apiPath, timeoutMs: 70000 });
    const l = label(model, stream, apiPath);
    if (r.count > 1) {
      ok(`${l.padEnd(40)} 上游被调 ${r.count} 次（重试生效）  下游 ${r.resp.status} / ${r.wallMs}ms`);
    } else {
      fail += 1;
      bad(`${l.padEnd(40)} 上游只被调 ${r.count} 次 —— 空响应未触发重试！`);
    }
  }

  console.log('\n【对照组：不判空 → 零重试（次数 === 1）】');
  for (const [model, stream, apiPath] of SHOULD_PASS) {
    const r = await probe(model, { stream, apiPath, timeoutMs: 25000 });
    const l = label(model, stream, apiPath);
    if (r.count === 1) {
      ok(`${l.padEnd(40)} 上游被调 1 次（零重试）  下游 ${r.resp.status} / ${r.wallMs}ms`);
    } else {
      fail += 1;
      bad(`${l.padEnd(40)} 上游被调 ${r.count} 次 —— 对照组不该重试（判定把「无正文」误当成了「空」）`);
    }
  }

  console.log('\n【耗尽放行：预算跑满后应放行最后一轮的空帧】');
  {
    const r = await probe('blank-empty-content', { timeoutMs: 200000 });
    info(`上游被调 ${r.count} 次  下游 ${r.resp.status} / ${(r.wallMs / 1000).toFixed(1)}s  ${r.resp.note}`);
    info(`下游收到 ${r.resp.frames.length} 个数据帧；尾部: ${r.resp.raw.slice(-120).replace(/\s+/g, ' ')}`);
    if (r.resp.status === 200 && r.resp.frames.length > 0) {
      ok('  耗尽后放行（下游拿到上游真实返回的空帧，正常收尾）');
    } else {
      fail += 1;
      bad(`  耗尽后未正常放行（HTTP ${r.resp.status}，${r.resp.frames.length} 帧）`);
    }
  }

  console.log(`\n${fail === 0 ? '✓ A 组全部通过' : `✗ A 组有 ${fail} 项失败`}`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
