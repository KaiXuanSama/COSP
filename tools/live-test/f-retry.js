#!/usr/bin/env node
'use strict';

/**
 * 静默重试的**定向验证**（B4/B5）。
 *
 * <h2>为什么单独一个脚本</h2>
 * 静默重试的验证需要三件事同时成立，UI 上很难精确控制：
 * <ol>
 *   <li>下游连接**必须保持不断开**（重试靠 `takeUntilOther` 中断**上游**请求，
 *       下游 SSE 是同一根管子，一旦下游断了就没有重试的载体）；</li>
 *   <li>必须在调用**处于停滞期**时打重试端点；</li>
 *   <li>判据是 **mock 侧出现第二次 `▶` 请求** + **下游 SSE 不中断**。</li>
 * </ol>
 *
 * 用法：node f-retry.js
 */

const fs = require('fs');
const path = require('path');
const { MOCK_PROVIDER, httpRequest, request, h, ok, bad, info } = require('./lib');

/** mock 的运行日志 —— 请求次数的权威来源（COSP 侧每轮只落一条，看不出重启）。 */
const MOCK_LOG = path.join(__dirname, 'mock.log');

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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  h('静默重试定向验证（B4/B5）');

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };

  const stream = openLifecycleStream();
  await sleep(700);

  // 用 cancel-stall-resume：吐 5 个 chunk → 停滞 35s → 恢复。
  // 在停滞期打重试，便于观察「上游被重新请求」。
  const model = 'cancel-stall-resume';

  /** 下游收到的帧（持续累积，用于判断 SSE 是否中断）。 */
  const frames = [];
  let downstreamEnded = false;
  let resolveEnd;
  const ended = new Promise((r) => { resolveEnd = r; });

  const req = httpRequest({
    path: '/v1/chat/completions',
    body: {
      model: `[${MOCK_PROVIDER}] ${model}`, stream: true,
      messages: [{ role: 'user', content: '静默重试定向验证' }],
    },
    onResponse: (res) => {
      res.setEncoding('utf8');
      res.on('data', (chunk) => {
        for (const line of chunk.split('\n')) {
          if (line.startsWith('data:')) frames.push(line.slice(5).trim());
        }
      });
      res.on('end', () => { downstreamEnded = true; resolveEnd(); });
      res.on('error', () => { downstreamEnded = true; resolveEnd(); });
    },
  });
  req.on('error', () => { downstreamEnded = true; resolveEnd(); });

  // 等到进入停滞期（吐完 5 个 chunk 后）
  await sleep(7000);
  const framesBeforeRetry = frames.length;
  const rid = [...stream.events].reverse().find((e) => e.model && e.model.includes(model));
  if (!rid) { bad('未取到 requestId'); stream.stop(); req.destroy(); process.exit(1); }
  const id = rid.requestId;
  const phasesBefore = stream.events.filter((e) => e.requestId === id).map((e) => e.phase);
  info(`requestId = ${id}`);
  info(`重试前：下游 ${framesBeforeRetry} 帧；相位尾 3 = ${phasesBefore.slice(-3).join(' → ')}`);

  // 打静默重试
  const rr = await request({ path: `/config/api/calls/${id}/retry`, method: 'POST', auth: true });
  const retried = JSON.parse(rr.raw || '{}').retried;
  info(`重试端点：HTTP ${rr.status} ${rr.raw.slice(0, 120)}`);

  // 观察 8 秒：上游应被重新请求，下游 SSE 应继续
  await sleep(8000);
  const framesAfter = frames.length;
  // 注意：必须在 **destroy() 之前** 读这个标志 —— destroy 会触发 end 事件把它置位，
  // 那之后读到的永远是 true（这不是「下游被重试关掉」，而是我们自己关的）。
  const downstreamEndedDuringTest = downstreamEnded;
  const phasesAfter = stream.events.filter((e) => e.requestId === id).map((e) => e.phase);

  info(`重试后：下游 ${framesAfter} 帧（+${framesAfter - framesBeforeRetry}）`);
  info(`相位全序列：${phasesAfter.slice(0, 3).join(' → ')} … ${phasesAfter.slice(-3).join(' → ')}`);
  info(`重试后下游连接是否已结束：${downstreamEndedDuringTest}`);

  // 核对 mock 侧是否出现第二次请求 —— 这才是「上游真被重新请求」的直接证据。
  const mockLines = fs.readFileSync(MOCK_LOG, 'utf8').split('\n')
    .filter((l) => l.includes('\u25b6') && l.includes(`model=${model}`));
  // 只数本次运行产生的（文件里可能有上次运行的记录）—— 用最近的两次判定。
  const recent = mockLines.slice(-2);
  const secondRequest = recent.length >= 2;
  info(`mock 侧历史上共 ${mockLines.length} 次请求，最近两次：`);
  for (const l of recent) info(`    ${l.trim()}`);

  stream.stop();
  req.destroy();
  await Promise.race([ended, sleep(3000)]);

  check(retried === true, '  重试端点返回 retried=true ✓', `  retried=${retried} —— 重试未被受理`);
  check(!downstreamEndedDuringTest,
    '  重试期间下游 SSE **未中断** ✓（只中断上游，下游同一根管子继续）',
    '  重试期间下游 SSE 被关闭了 —— 静默重试不应影响下游连接');
  check(framesAfter > framesBeforeRetry, `  下游在重试后继续收到帧（+${framesAfter - framesBeforeRetry}）✓`,
    '  重试后下游未再收到帧');
  check(secondRequest, '  **mock 侧出现第二次请求** ✓（上游真被重新请求）',
    `  mock 侧最近只有 ${recent.length} 次请求 —— 上游未被重新请求`);

  console.log(`\n${fail === 0 ? '✓ 静默重试验证通过' : `✗ 有 ${fail} 项失败`}`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
