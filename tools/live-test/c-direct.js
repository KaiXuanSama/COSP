#!/usr/bin/env node
'use strict';

/**
 * C 组：三条协议 × 两态的**直连主路径**。
 *
 * <h2>为什么这组值得单独存在</h2>
 * A/B/D/F 组测的都是**病态路径**（空响应、错误码、翻译、停滞）。C 组是**正常路径** ——
 * 若它坏了，上面所有"异常处理正确"的结论都没有意义。
 *
 * <h2>与 D 组的区别</h2>
 * D 组用只勾 MESSAGES 的供应商**强制走翻译**；C 组用三协议全开的 `mock` 供应商，
 * 因此走的是**同协议直连**（`ProtocolDispatchManager` 规则 1）。断言也相应不同：
 * 直连时**下游形态应等于上游形态**（无翻译损耗）。
 *
 * 用法：node c-direct.js [c1|c3|c5|c6]
 */

const { probe, h, ok, bad, info } = require('./lib');

/** 解析 SSE 帧。 */
function parseFrames(frames) {
  return frames
    .filter((f) => f !== '[DONE]')
    .map((f) => { try { return JSON.parse(f); } catch { return null; } })
    .filter(Boolean);
}

/** 收集 tool_calls（index 集合 + 按 index 拼接的 arguments + 是否见到 name）。 */
function collectToolCalls(frames) {
  const indices = new Set();
  const byIndex = new Map();
  const names = new Map();
  for (const f of frames) {
    for (const c of (f.choices || [])) {
      for (const tc of ((c.delta && c.delta.tool_calls) || [])) {
        if (tc.index !== undefined) {
          indices.add(tc.index);
          if (tc.function && tc.function.name) names.set(tc.index, tc.function.name);
          const prev = byIndex.get(tc.index) || '';
          byIndex.set(tc.index, prev + ((tc.function && tc.function.arguments) || ''));
        }
      }
    }
  }
  return { indices: [...indices].sort((a, b) => a - b), byIndex, names };
}

/** 取最后一个 finish_reason。 */
function lastFinish(frames) {
  let f = null;
  for (const fr of frames) {
    for (const c of (fr.choices || [])) if (c.finish_reason) f = c.finish_reason;
  }
  return f;
}

async function main() {
  const which = process.argv[2];
  h('C 组：三协议 × 两态的直连主路径');

  let fail = 0;
  const check = (c, g, w) => { if (c) ok(g); else { fail += 1; bad(w); } };
  const want = (name) => !which || which === name;

  /* ── C1 · Chat 流式 ───────────────────────────────────── */
  if (want('c1')) {
    console.log('\n【C1 · Chat 流式：RECEIVED → CONNECTED → CHUNK → COMPLETED 全链路】');
    const r = await probe('baseline-normal', { timeoutMs: 30000 });
    const frames = parseFrames(r.resp.frames);
    const contentFrames = frames.filter((f) => f.choices && f.choices[0].delta && f.choices[0].delta.content).length;
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms / ${r.resp.frames.length} 帧（其中 content 帧 ${contentFrames}）`);
    info(`finish_reason = ${JSON.stringify(lastFinish(frames))}  含 [DONE] = ${r.resp.raw.includes('[DONE]')}`);
    check(r.resp.status === 200, '  下游 200 ✓', `  下游 HTTP=${r.resp.status}`);
    check(contentFrames >= 10, `  收到 ${contentFrames} 个 content 帧 ✓（完整推流）`, `  content 帧仅 ${contentFrames} 个`);
    check(lastFinish(frames) === 'stop', '  finish_reason = stop ✓', `  finish_reason = ${JSON.stringify(lastFinish(frames))}`);
    check(r.resp.raw.includes('[DONE]'), '  含结束标记 [DONE] ✓', '  缺 [DONE]');
    check(r.resp.note === '响应结束', '  连接正常关闭 ✓', `  note=${r.resp.note}`);
  }

  /* ── C2 · Chat 非流式 ─────────────────────────────────── */
  if (want('c2')) {
    console.log('\n【C2 · Chat 非流式：200 透传 + 用量入库】');
    const r = await probe('baseline-normal', { stream: false, timeoutMs: 30000 });
    let body = null;
    try { body = JSON.parse(r.resp.raw); } catch { /* 非 JSON */ }
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms`);
    info(`object=${body && body.object}  content 长度=${body && body.choices && body.choices[0] && (body.choices[0].message.content || '').length}`);
    info(`usage=${JSON.stringify(body && body.usage)}`);
    check(r.resp.status === 200, '  下游 200 ✓', `  下游 HTTP=${r.resp.status}`);
    check(body && body.object === 'chat.completion', '  形态为 chat.completion ✓（非 chunk）',
      `  object=${body && body.object}`);
    check(body && body.choices && body.choices.length === 1 && body.choices[0].message, '  有 choices[0].message ✓', '  结构不符');
    check(body && body.usage && body.usage.prompt_tokens > 0, '  带真实 usage ✓（可入库）',
      `  usage=${JSON.stringify(body && body.usage)}`);
  }

  /* ── C3/C4 · Messages 两态 ───────────────────────────── */
  for (const stream of [true, false]) {
    const tag = stream ? 'c3' : 'c4';
    if (!want(tag)) continue;
    console.log(`\n【C${stream ? '3' : '4'} · Messages ${stream ? '流式' : '非流式'}：事件序列原样透传】`);
    const r = await probe('baseline-normal', { stream, apiPath: '/v1/messages', timeoutMs: 30000 });
    const raw = r.resp.raw;
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms / ${r.resp.frames.length} 帧`);
    check(r.resp.status === 200, '  下游 200 ✓', `  下游 HTTP=${r.resp.status}`);
    if (stream) {
      // 直连时应看到 Anthropic 原生事件序列（未被翻译）。
      // 注意：判据读 data 帧里的 "type" 而不是 raw 里的 "event:" ——
      // lib.request() 只收集 `data:` 行（`frames`），`event:` 行不进 `raw` 也不进 frames。
      // 两者在 Anthropic 协议里必然一致，读哪个都对。
      const types = parseFrames(r.resp.frames).map((f) => f.type);
      const wanted = ['message_start', 'content_block_start', 'content_block_delta',
        'content_block_stop', 'message_delta', 'message_stop'];
      const missing = wanted.filter((w) => !types.includes(w));
      info(`事件类型（按出现顺序）：${[...new Set(types)].join(', ')}`);
      check(missing.length === 0, '  Anthropic 原生事件序列完整 ✓（直连未翻译）',
        `  缺事件：${missing.join(', ')}`);
      check(!types.includes('chat.completion.chunk'), '  **未**混入 OpenAI chunk 形态 ✓（确认是直连）',
        '  出现了 OpenAI chunk —— 竟然走了翻译？');
    } else {
      let body = null;
      try { body = JSON.parse(raw); } catch { /* 非 JSON */ }
      info(`type=${body && body.type}  content 块数=${body && body.content && body.content.length}`);
      check(body && body.type === 'message', '  形态为 Anthropic message ✓', `  type=${body && body.type}`);
      check(body && Array.isArray(body.content) && body.content.length > 0, '  有 content 块 ✓', '  content 为空');
      check(body && body.usage && body.usage.input_tokens > 0, '  带 Anthropic usage ✓', `  usage=${JSON.stringify(body && body.usage)}`);
    }
  }

  /* ── C5 · Messages 思考 + 正文 ────────────────────────── */
  if (want('c5')) {
    console.log('\n【C5 · baseline-thinking-text：思考与正文交替，两段都到达下游】');
    for (const stream of [true, false]) {
      const r = await probe('baseline-thinking-text', { stream, apiPath: '/v1/messages', timeoutMs: 30000 });
      const raw = r.resp.raw;
      info(`  ${stream ? '流式' : '非流式'}：HTTP=${r.resp.status} / ${r.wallMs}ms`);
      check(r.resp.status === 200, `  ${stream ? '流式' : '非流式'} 200 ✓`, `  HTTP=${r.resp.status}`);
      if (stream) {
        const hasThinking = raw.includes('thinking_delta') || raw.includes('"type":"thinking"');
        const hasText = raw.includes('text_delta');
        info(`    thinking 事件=${hasThinking}   text 事件=${hasText}`);
        check(hasThinking && hasText, '    思考与正文两段都到达下游 ✓', `    thinking=${hasThinking} text=${hasText}`);
      } else {
        let body = null;
        try { body = JSON.parse(raw); } catch { /* 非 JSON */ }
        const types = (body && body.content || []).map((b) => b.type);
        info(`    content 块类型 = [${types.join(', ')}]`);
        check(types.includes('thinking') && types.includes('text'),
          '    thinking 与 text 两种块都在 ✓', `    块类型 = [${types.join(', ')}]`);
      }
    }
  }

  /* ── C6 · 工具调用直连（两协议） ──────────────────────── */
  if (want('c6')) {
    console.log('\n【C6 · baseline-tool-call：工具调用原样透传（两协议）】');
    for (const apiPath of ['/v1/chat/completions', '/v1/messages']) {
      const proto = apiPath.includes('messages') ? 'MESSAGES' : 'CHAT';
      const r = await probe('baseline-tool-call', { apiPath, timeoutMs: 30000 });
      info(`  ${proto}：HTTP=${r.resp.status} / ${r.wallMs}ms`);
      check(r.resp.status === 200, `  ${proto} 200 ✓`, `  HTTP=${r.resp.status}`);
      if (proto === 'CHAT') {
        const frames = parseFrames(r.resp.frames);
        const { indices, byIndex, names } = collectToolCalls(frames);
        const finish = lastFinish(frames);
        info(`    tool index = {${indices.join(',')}}  name = ${JSON.stringify([...names.values()])}`);
        info(`    arguments = ${JSON.stringify([...byIndex.values()])}  finish_reason = ${JSON.stringify(finish)}`);
        check(indices.length > 0 && names.size > 0, '    tool_calls 带 name ✓', '    未收到工具调用');
        check(byIndex.size > 0 && [...byIndex.values()].every((a) => { try { JSON.parse(a); return true; } catch { return false; } }),
          '    arguments 为合法 JSON ✓', '    arguments 不是合法 JSON');
        check(finish === 'tool_calls', '    finish_reason = tool_calls ✓', `    finish_reason = ${JSON.stringify(finish)}`);
      } else {
        const raw = r.resp.raw;
        const hasToolUse = raw.includes('"type":"tool_use"') || raw.includes('tool_use');
        info(`    含 tool_use = ${hasToolUse}`);
        check(hasToolUse, '    含 tool_use 块 ✓', '    未收到 tool_use');
        // 直连形态（非翻译）：input_json_delta 分片也应原样
        check(raw.includes('input_json_delta'), '    参数以 input_json_delta 分片 ✓（Anthropic 原生形态）',
          '    未见 input_json_delta —— 形态不符（直连不应翻译）');
      }
    }
  }

  console.log(`\n${fail === 0 ? '✓ C 组全部通过' : `✗ C 组有 ${fail} 项失败`}`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
