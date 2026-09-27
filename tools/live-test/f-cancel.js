#!/usr/bin/env node
'use strict';

/**
 * F 组（中断类）：停滞期的取消与静默重试。
 *
 * <h2>为什么用打端点而不是 UI 右键</h2>
 * UI 右键菜单最终也是调 `POST /config/api/calls/{id}/cancel|retry`。
 * 直接打端点更快、更精确，且能拿到 `{canceled:true/false}` 的确定性返回；
 * UI 只用来**确认菜单项的可见性**（F6 那条「非流式不显示静默重试」才是 UI 专属断言）。
 *
 * <h2>requestId 从哪来</h2>
 * `GET /config/api/calls/stream` 在连接建立时补发在途调用快照，随后推实时事件。
 * 因此「先连 SSE → 再打挂起请求 → 从事件里取 requestId → 打取消端点」是完整闭环。
 *
 * 用法：node f-cancel.js [f3|f4|f5|f6|all]
 */

const http = require('http');
const { MOCK_PROVIDER, httpRequest, request, h, ok, bad, info } = require('./lib');

/* ── SSE 订阅（用 lib 的 httpRequest，地址与认证统一配置） ── */

function openLifecycleStream() {
  const events = [];
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
            try { events.push(JSON.parse(line.slice(5).trim())); } catch { /* 心跳 */ }
          }
        }
      });
    },
  });
  req.on('error', () => {});
  return { events, stop: () => req.destroy() };
}

/** 启动一条挂起请求（不阻塞），返回控制句柄。 */
function startHanging(model) {
  const frames = [];
  let settled = false;
  const result = { frames, status: 0, note: '', raw: '' };
  let resolveDone;
  const done = new Promise((r) => { resolveDone = r; });

  const req = httpRequest({
    path: '/v1/chat/completions',
    body: {
      model: `[${MOCK_PROVIDER}] ${model}`, stream: true,
      messages: [{ role: 'user', content: 'F 组：中断测试' }],
    },
    onResponse: (res) => {
      result.status = res.statusCode;
      res.setEncoding('utf8');
      res.on('data', (chunk) => {
        result.raw += chunk;
        for (const line of chunk.split('\n')) {
          if (line.startsWith('data:')) frames.push(line.slice(5).trim());
        }
      });
      res.on('end', () => { if (!settled) { settled = true; result.note = '响应结束'; resolveDone(); } });
      res.on('error', (e) => { if (!settled) { settled = true; result.note = `响应错误 ${e.code || e.message}`; resolveDone(); } });
    },
  });
  req.on('error', (e) => { if (!settled) { settled = true; result.note = `连接错误 ${e.code || e.message}`; resolveDone(); } });

  return {
    result,
    done,
    abort: () => req.destroy(),
    /** 等下游流结束，超时返回 'timeout'。 */
    settle: (ms) => Promise.race([
      done.then(() => result.note),
      new Promise((r) => setTimeout(() => r('timeout'), ms)),
    ]),
  };
}

/** 从事件流里找指定模型的 requestId（取最新的）。 */
function findRequestId(events, model, { nonTerminal = true } = {}) {
  const TERMINAL = new Set(['COMPLETED', 'FAILED', 'CANCELED', 'ABORTED']);
  const hits = events.filter((e) => e.model && e.model.includes(model));
  for (let i = hits.length - 1; i >= 0; i -= 1) {
    const id = hits[i].requestId;
    const phases = events.filter((e) => e.requestId === id).map((e) => e.phase);
    const hasTerminal = phases.some((p) => TERMINAL.has(p));
    if (!nonTerminal || !hasTerminal) return id;
  }
  return hits.length > 0 ? hits[hits.length - 1].requestId : null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ── F3 · 建立连接后不吐数据 → 取消 ───────────────────── */
async function f3() {
  console.log('\n【F3 · cancel-hang：建立连接后不吐任何数据 → 取消】');
  info('期望：Toast 停在「等待首字」；取消后下游静默断连（无错误帧）、相位 ABORTED。');
  const stream = openLifecycleStream();
  await sleep(600);

  const h1 = startHanging('cancel-hang');
  await sleep(2500);   // 让 mock 建连（它立即写 SSE 头）+ COSP 进 CONNECTED

  const rid = findRequestId(stream.events, 'cancel-hang');
  if (!rid) { bad('  未从事件流取到 requestId'); stream.stop(); h1.abort(); return 1; }
  const before = stream.events.filter((e) => e.requestId === rid).map((e) => e.phase);
  info(`  requestId = ${rid}`);
  info(`  取消前相位：${before.join(' → ')}`);

  const cr = await request({ path: `/config/api/calls/${rid}/cancel`, method: 'POST', auth: true });
  info(`  取消端点返回：HTTP ${cr.status} ${cr.raw.slice(0, 120)}`);
  const note = await h1.settle(8000);
  await sleep(800);
  const after = stream.events.filter((e) => e.requestId === rid).map((e) => e.phase);
  stream.stop();

  info(`  取消后相位：${after.join(' → ')}`);
  info(`  下游：HTTP=${h1.result.status} / ${h1.result.frames.length} 帧 / note=${note}`);

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  const canceled = JSON.parse(cr.raw || '{}').canceled === true;
  check(canceled, '  取消端点返回 canceled=true ✓', `  取消未生效：${cr.raw.slice(0, 120)}`);
  check(before.includes('CONNECTED'), `  取消前已进入 CONNECTED ✓（Toast 停在等待首字）`,
    `  取消前未进入 CONNECTED（${before.join('→')}）`);
  check(after.includes('ABORTED') || after.includes('CANCELED'),
    `  取消后出现终态 ABORTED ✓（${after.slice(-1)[0]}）`,
    `  取消后无 ABORTED/CANCELED（${after.join('→')}）`);
  check(note !== 'timeout', '  下游连接已被关闭 ✓（不挂死）', '  下游连接未关闭 —— 取消未透到下游');
  check(!h1.result.raw.includes('event:error'),
    '  下游未收到错误帧 ✓（静默断连，符合设计）',
    '  下游收到了错误帧');
  return fail;
}

/* ── F4 · 产出后永久停滞 → 取消 ───────────────────────── */
async function f4() {
  console.log('\n【F4 · cancel-stall：吐若干 chunk 后永久停滞 → 取消】');
  info('期望：已收到部分 chunk；取消后下游断连、相位 ABORTED。');
  const stream = openLifecycleStream();
  await sleep(600);

  const h1 = startHanging('cancel-stall');
  await sleep(6000);   // mock 先吐 5 个 chunk 再停滞（间隔 80ms）

  const chunks = h1.result.frames.filter((f) => f !== '[DONE]' && f.includes('"content"')).length;
  const rid = findRequestId(stream.events, 'cancel-stall');
  if (!rid) { bad('  未取到 requestId'); stream.stop(); h1.abort(); return 1; }
  const before = stream.events.filter((e) => e.requestId === rid).map((e) => e.phase);
  info(`  requestId = ${rid}  已收到 ${chunks} 个 content 帧`);
  info(`  取消前相位（尾 3）：${before.slice(-3).join(' → ')}`);

  const cr = await request({ path: `/config/api/calls/${rid}/cancel`, method: 'POST', auth: true });
  const note = await h1.settle(8000);
  await sleep(800);
  const after = stream.events.filter((e) => e.requestId === rid).map((e) => e.phase);
  stream.stop();

  info(`  取消端点：${cr.raw.slice(0, 80)}`);
  info(`  取消后相位（尾 3）：${after.slice(-3).join(' → ')}`);
  info(`  下游：HTTP=${h1.result.status} / ${h1.result.frames.length} 帧 / note=${note}`);

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  check(chunks >= 3, `  取消前已收到 ${chunks} 个 content 帧 ✓（证明「产出后停滞」）`,
    `  只收到 ${chunks} 个 content 帧 —— 未复现「产出后停滞」`);
  check(JSON.parse(cr.raw || '{}').canceled === true, '  取消端点返回 canceled=true ✓', `  取消未生效：${cr.raw.slice(0, 120)}`);
  check(after.includes('ABORTED') || after.includes('CANCELED'),
    `  取消后出现 ABORTED ✓`, `  取消后无终态（${after.slice(-3).join('→')}）`);
  check(note !== 'timeout', '  下游连接已关闭 ✓', '  下游未关闭');
  return fail;
}

/* ── F5 · 停滞 35s 后恢复 → 停滞期取消 ────────────────── */
async function f5() {
  console.log('\n【F5 · cancel-stall-resume：停滞 35s 后恢复 —— 停滞期取消】');
  info('期望：停滞期取消有效（不必等它恢复）；若取消端点返回 false 说明调用已不存在。');
  const stream = openLifecycleStream();
  await sleep(600);

  const h1 = startHanging('cancel-stall-resume');
  await sleep(6000);   // 已吐 5 个 chunk，进入 35s 停滞

  const rid = findRequestId(stream.events, 'cancel-stall-resume');
  if (!rid) { bad('  未取到 requestId'); stream.stop(); h1.abort(); return 1; }
  const phases = stream.events.filter((e) => e.requestId === rid).map((e) => e.phase);
  const chunkCount = phases.filter((p) => p === 'CHUNK').length;
  info(`  requestId = ${rid}  CHUNK 事件数 = ${chunkCount}（应处于停滞中）`);

  const cr = await request({ path: `/config/api/calls/${rid}/cancel`, method: 'POST', auth: true });
  const note = await h1.settle(8000);
  await sleep(800);
  const after = stream.events.filter((e) => e.requestId === rid).map((e) => e.phase);
  stream.stop();

  info(`  取消端点：${cr.raw.slice(0, 80)}`);
  info(`  下游：HTTP=${h1.result.status} / ${h1.result.frames.length} 帧 / note=${note}`);

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  check(chunkCount >= 3, `  停滞期已收到 ${chunkCount} 个 CHUNK ✓`, `  CHUNK 事件仅 ${chunkCount} 个`);
  check(JSON.parse(cr.raw || '{}').canceled === true,
    '  **停滞期取消立即生效** ✓（不必等 35s 恢复）', `  取消未生效：${cr.raw.slice(0, 120)}`);
  check(after.includes('ABORTED') || after.includes('CANCELED'), '  出现 ABORTED ✓', `  无终态（${after.slice(-3).join('→')}）`);
  check(note !== 'timeout', '  下游已关闭 ✓', '  下游未关闭');
  return fail;
}

/* ── F6 · 非流式：静默重试不可用（UI 专属断言） ───────── */
async function f6() {
  console.log('\n【F6 · 非流式调用没有「静默重试」入口】');
  info('这条是**后端行为**断言：非流式未注册重试项，因此 retry 端点应返回 retried=false。');
  info('（UI 侧同时隐藏菜单项 —— 那需人工在浏览器确认。）');

  const stream = openLifecycleStream();
  await sleep(600);
  // 非流式挂起场景：ns-hang 不存在，改用 cancel-hang 的非流式形态
  let settled = false;
  let resolveDone;
  const done = new Promise((r) => { resolveDone = r; });
  const req = httpRequest({
    path: '/v1/chat/completions',
    body: {
      model: `[${MOCK_PROVIDER}] cancel-hang`, stream: false,
      messages: [{ role: 'user', content: 'F6：非流式的挂起' }],
    },
    onResponse: (res) => {
      res.on('data', () => {});
      res.on('end', () => { if (!settled) { settled = true; resolveDone(); } });
      res.on('error', () => { if (!settled) { settled = true; resolveDone(); } });
    },
  });
  req.on('error', () => { if (!settled) { settled = true; resolveDone(); } });

  await sleep(2500);
  const rid = findRequestId(stream.events, 'cancel-hang');
  if (!rid) { bad('  未取到 requestId'); stream.stop(); req.destroy(); return 1; }
  const mine = stream.events.filter((e) => e.requestId === rid);
  info(`  requestId = ${rid}  stream 字段 = ${mine.length > 0 ? mine[0].stream : '?'}`);
  info(`  相位：${mine.map((e) => e.phase).join(' → ')}`);

  const rr = await request({ path: `/config/api/calls/${rid}/retry`, method: 'POST', auth: true });
  info(`  重试端点返回：${rr.raw.slice(0, 120)}`);
  const retried = JSON.parse(rr.raw || '{}').retried;

  // 清理
  await request({ path: `/config/api/calls/${rid}/cancel`, method: 'POST', auth: true });
  await Promise.race([done, sleep(5000)]);
  req.destroy();
  stream.stop();

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  check(mine.length > 0 && mine[0].stream === false,
    '  该调用的 stream=false ✓（非流式）', `  stream 字段 = ${mine.length > 0 ? mine[0].stream : '无事件'}`);
  check(retried === false,
    '  重试端点返回 retried=false ✓（非流式无重试注册项，与 UI 隐藏菜单项一致）',
    `  retried=${retried} —— 非流式竟然可以静默重试？`);
  return fail;
}

async function main() {
  const which = process.argv[2] || 'all';
  h('F 组（中断类）：停滞期的取消与静默重试');

  let fail = 0;
  if (which === 'all' || which === 'f3') fail += await f3();
  if (which === 'all' || which === 'f4') fail += await f4();
  if (which === 'all' || which === 'f5') fail += await f5();
  if (which === 'all' || which === 'f6') fail += await f6();

  console.log(`\n${fail === 0 ? '✓ 中断类全部通过' : `✗ 有 ${fail} 项失败`}`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
