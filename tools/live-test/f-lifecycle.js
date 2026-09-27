#!/usr/bin/env node
'use strict';

/**
 * F 组（相位类）：连接生命周期 —— 完成判定与计数推进。
 *
 * <h2>两类用例，判据不同</h2>
 * <b>相位类（本文件 F1/F2/F7/F8）</b>：脚本可断言 —— 看**下游收到的帧**与**生命周期事件序列**。
 * <b>中断类（f-cancel.js F3–F6）</b>：需要一条持续在途的调用，断言取消后下游收到什么。
 *
 * <h2>生命周期事件怎么取</h2>
 * `GET /config/api/calls/stream` 在连接建立时**先补发在途调用快照**，随后推实时事件。
 * 因此「**先连 SSE、再打请求**」能完整捕获该请求的全部相位 —— 顺序不能反。
 *
 * 用法：node f-lifecycle.js [f1|f2|f7|f8]
 */

const http = require('http');
const { MOCK_PROVIDER, httpRequest, request, h, ok, bad, info } = require('./lib');

/** 开一条 SSE 订阅，返回 { events, stop }。必须在打请求**之前**调用。 */
function openLifecycleStream() {
  const events = [];
  // 用 httpRequest 建连 —— 地址与认证统一由 lib 配置（COSP_BASE_URL / token）
  const req = httpRequest({
    path: '/config/api/calls/stream',
    method: 'GET',
    auth: true,
    headers: { Accept: 'text/event-stream' },
    onResponse: (res) => {
      let buf = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => {
        buf += chunk;
        const parts = buf.split('\n\n');
        buf = parts.pop();
        for (const part of parts) {
          for (const line of part.split('\n')) {
            if (!line.startsWith('data:')) continue;
            try { events.push(JSON.parse(line.slice(5).trim())); } catch { /* 心跳等非 JSON */ }
          }
        }
      });
    },
  });
  req.on('error', () => {});
  return { events, stop: () => req.destroy() };
}

/** 某 requestId 的相位序列。 */
function phasesOf(events, requestId) {
  return events.filter((e) => e.requestId === requestId).map((e) => e.phase);
}

/** 按模型名找最近的 requestId（用无前缀名匹配 —— 事件里的 model 是下游原文，含前缀）。 */
function requestIdFor(events, modelFragment) {
  const hit = [...events].reverse().find((e) => e.model && e.model.includes(modelFragment));
  return hit ? hit.requestId : null;
}

/** 通用：连 SSE → 打请求 → 收相位。 */
async function capture(model, { timeoutMs = 30000, settleMs = 1200 } = {}) {
  const stream = openLifecycleStream();
  await new Promise((r) => setTimeout(r, 600));
  const t0 = Date.now();
  const resp = await request({
    path: '/v1/chat/completions',
    body: {
      model: `[${MOCK_PROVIDER}] ${model}`, stream: true,
      messages: [{ role: 'user', content: 'hi' }],
    },
    timeoutMs,
  });
  const wallMs = Date.now() - t0;
  await new Promise((r) => setTimeout(r, settleMs));
  stream.stop();
  const rid = requestIdFor(stream.events, model);
  return { resp, wallMs, rid, phases: rid ? phasesOf(stream.events, rid) : [], events: stream.events };
}

/* ── F1 · 发完 [DONE] 但保持 TCP 不关 ──────────────────── */
async function f1() {
  console.log('\n【F1 · phase-done-early：发完 [DONE] 但保持 TCP 不关】');
  info('期望：Layer 1 在收到结束标记的**那一刻**收尾（COMPLETED），不等 TCP 关闭。');
  info('注：本场景 mock 永不 res.end()，因此**下游 HTTP 连接会一直挂着** —— 那是预期。');
  info('    判据是「相位到达 COMPLETED 的时机」，不是「下游连接是否结束」。');

  // 用短超时：只关心「COMPLETED 是否在下游连接仍在时就已经发生」。
  const { resp, wallMs, phases } = await capture('phase-done-early', { timeoutMs: 8000, settleMs: 1000 });
  const hasFinish = resp.raw.includes('"finish_reason":"stop"');
  const hasDone = resp.raw.includes('[DONE]');
  info(`下游：HTTP=${resp.status} / ${wallMs}ms / ${resp.frames.length} 帧  finish=${hasFinish} [DONE]=${hasDone}  note=${resp.note}`);
  info(`相位：${phases.slice(0, 3).join(' → ')} … ${phases.slice(-2).join(' → ')}（共 ${phases.length} 个）`);

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  check(resp.status === 200, '  下游 200 ✓', `  下游 HTTP=${resp.status}`);
  check(hasFinish && hasDone, '  收到 finish_reason=stop 与 [DONE] ✓', '  缺少收尾标记');
  check(resp.note === '超时',
    '  下游连接确实**没有结束** ✓（mock 从不 end —— 这是本场景的前提）',
    `  下游连接结束了（note=${resp.note}）—— mock 侧行为不符预期`);
  check(phases.includes('COMPLETED'),
    '  相位出现 COMPLETED ✓ —— **在下游连接仍挂着时就已收尾**（Layer 1 生效）',
    `  无 COMPLETED（${phases.join('→') || '空'}）`);
  // 关键：COMPLETED 必须出现在最后一帧之后，而不是等连接关闭才有。
  const lastChunkIdx = phases.lastIndexOf('CHUNK');
  const completedIdx = phases.indexOf('COMPLETED');
  check(completedIdx > lastChunkIdx,
    '  COMPLETED 在最后一个 CHUNK 之后 ✓（由终止标记触发，非超时兜底）',
    '  COMPLETED 位置异常');
  return fail;
}

/* ── F2 · 无结束标记直接关连接 ─────────────────────────── */
async function f2() {
  console.log('\n【F2 · phase-eof-fallback：发完内容直接关连接、无 [DONE]】');
  info('期望：Layer 2（TCP 关闭）兜底完成。');
  const { resp, wallMs, phases } = await capture('phase-eof-fallback');
  const hasDone = resp.raw.includes('[DONE]');
  info(`下游：HTTP=${resp.status} / ${wallMs}ms / ${resp.frames.length} 帧  [DONE]=${hasDone}  note=${resp.note}`);
  info(`相位：${phases.join(' → ') || '(未捕获)'}`);

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  check(!hasDone, '  上游确实未发 [DONE] ✓（本场景的前提）', '  竟然收到了 [DONE]');
  check(resp.note === '响应结束', '  下游流正常结束 ✓（Layer 2 兜底）', `  流未正常结束（note=${resp.note}）`);
  check(phases.includes('COMPLETED'), '  生命周期出现 COMPLETED ✓', `  无 COMPLETED（${phases.join('→') || '空'}）`);
  return fail;
}

/* ── F7 · 慢速持续推流，计数持续推进 ──────────────────── */
async function f7() {
  console.log('\n【F7 · phase-slow-steady：每 3s 一个 chunk】');
  info('期望：CHUNK 事件的 chunkCount 持续增长（Toast 计数更新）。');
  const { resp, phases, rid, events } = await capture('phase-slow-steady', { timeoutMs: 60000, settleMs: 1500 });
  const chunkCounts = events.filter((e) => e.requestId === rid && e.phase === 'CHUNK').map((e) => e.chunkCount);
  info(`下游：HTTP=${resp.status} / ${resp.frames.length} 帧`);
  info(`CHUNK 事件 ${chunkCounts.length} 个，计数序列 = ${chunkCounts.join(',')}`);
  info(`相位：${phases.join(' → ')}`);

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  check(chunkCounts.length >= 2, `  有多个 CHUNK 事件（${chunkCounts.length}）✓`, `  只有 ${chunkCounts.length} 个 CHUNK 事件`);
  const increasing = chunkCounts.every((v, i) => i === 0 || v >= chunkCounts[i - 1]);
  check(increasing, `  chunkCount 单调不减 ✓（${chunkCounts[0]} → ${chunkCounts[chunkCounts.length - 1]}）`,
    `  chunkCount 非单调：${chunkCounts.join(',')}`);
  check(phases.includes('COMPLETED'), '  最终 COMPLETED ✓', '  无 COMPLETED');
  return fail;
}

/* ── F8 · 延迟后正常返回（不该被判为停滞） ──────────────── */
async function f8() {
  console.log('\n【F8 · phase-slow：延迟 5s 后正常返回】');
  info('期望：不被误判为停滞，相位正常走到 COMPLETED。');
  const { resp, wallMs, phases } = await capture('phase-slow');
  info(`下游：HTTP=${resp.status} / ${wallMs}ms / ${resp.frames.length} 帧`);
  info(`相位：${phases.join(' → ') || '(未捕获)'}`);

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  check(wallMs >= 5000, `  确实延迟约 ${(wallMs / 1000).toFixed(1)}s ✓`, `  只用了 ${wallMs}ms —— 不像延迟 5s`);
  check(resp.status === 200 && resp.frames.length > 0, '  延迟后正常返回 ✓', '  未正常返回');
  check(phases.includes('COMPLETED') && !phases.includes('FAILED'), '  相位正常走完（无 FAILED）✓', `  相位异常：${phases.join('→')}`);
  return fail;
}

async function main() {
  const which = process.argv[2];
  h('F 组（相位类）：完成判定与计数推进');

  let fail = 0;
  if (!which || which === 'f1') fail += await f1();
  if (!which || which === 'f2') fail += await f2();
  if (!which || which === 'f7') fail += await f7();
  if (!which || which === 'f8') fail += await f8();

  console.log(`\n${fail === 0 ? '✓ 相位类全部通过' : `✗ 有 ${fail} 项失败`}`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
