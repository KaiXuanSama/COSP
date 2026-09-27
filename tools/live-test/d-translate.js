#!/usr/bin/env node
'use strict';

/**
 * D 组：C2M 跳协议翻译（下游 OpenAI → 上游 Anthropic）。
 *
 * <h2>前置：必须有只勾 MESSAGES 的供应商</h2>
 * `ProtocolDispatchManager` **规则 1 是「同名协议优先直连」** —— 供应商三协议全开时
 * 永远走直连、翻译支线不会被触发。因此需要第二个指向同一 mock 的供应商：
 *
 * <pre>
 * # 1) 新建（providerKey 由 displayName 推导，表单是 form-urlencoded）
 * curl -X POST http://localhost:11434/config/api/providers \
 *   -H "Authorization: Bearer &lt;token&gt;" -H "Content-Type: application/x-www-form-urlencoded" \
 *   --data "displayName=TranslateMock&baseUrl=http%3A%2F%2Flocalhost%3A8081&supportedProtocolsJson=%5B%22MESSAGES%22%5D"
 *
 * # 2) 加模型（多值表单，字段是 models[N].name / .enabled / .contextSize / .capsTools）
 * curl -X POST http://localhost:11434/config/api/providers/&lt;key&gt;/config ... \
 *   --data "baseUrl=...&models%5B0%5D.name=baseline-normal&models%5B0%5D.enabled=on&..."
 * </pre>
 *
 * <h2>模型名必须带前缀</h2>
 * 同一 mock 被配成两个供应商后，模型名**必然重名**（这是本用例的前提，不是缺陷）。
 * 无前缀名要求全库唯一匹配 → 路由失败，且报错是「没有可用的上游服务来处理模型」，
 * **不会提示「你有个重名」**。因此本脚本一律用 `[providerKey] model` 显式路由。
 *
 * 用法：node d-translate.js
 *      供应商 key 由环境变量 `COSP_TRANSLATE_PROVIDER` 指定（默认 `translatemock`）。
 */

const { probe, TRANSLATE_PROVIDER, printConfig, h, ok, bad, info } = require('./lib');

const PROVIDER = TRANSLATE_PROVIDER;

/** 解析 SSE 帧为对象数组（跳过 [DONE] 与不可解析的）。 */
function parseFrames(frames) {
  return frames
    .filter((f) => f !== '[DONE]')
    .map((f) => { try { return JSON.parse(f); } catch { return null; } })
    .filter(Boolean);
}

/** 收集所有 tool_calls 的 index 集合与按 index 分组的 arguments。 */
function collectToolCalls(frames) {
  const indices = new Set();
  const byIndex = new Map();
  for (const f of frames) {
    for (const c of (f.choices || [])) {
      for (const tc of ((c.delta && c.delta.tool_calls) || [])) {
        if (tc.index !== undefined) {
          indices.add(tc.index);
          const prev = byIndex.get(tc.index) || '';
          byIndex.set(tc.index, prev + ((tc.function && tc.function.arguments) || ''));
        }
      }
    }
  }
  return { indices: [...indices].sort((a, b) => a - b), byIndex };
}

/** 取最后一个非空 finish_reason。 */
function lastFinishReason(frames) {
  let finish = null;
  for (const f of frames) {
    for (const c of (f.choices || [])) {
      if (c.finish_reason) finish = c.finish_reason;
    }
  }
  return finish;
}

async function main() {
  h(`D 组：C2M 跳协议翻译（供应商 ${PROVIDER} 只勾 MESSAGES）`);
  printConfig();

  let fail = 0;
  const check = (cond, good, worse) => {
    if (cond) ok(good); else { fail += 1; bad(worse); }
  };
  const opts = (extra = {}) => ({ ...extra, providerKey: PROVIDER });

  /* ── D1/D2 · 基础翻译（下游形态必须是 OpenAI chunk） ──── */
  for (const stream of [true, false]) {
    console.log(`\n【D${stream ? '1' : '2'} · baseline-normal 走 C2M（${stream ? '流式' : '非流式'}）】`);
    const r = await probe('baseline-normal', opts({ stream, timeoutMs: 60000 }));
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms  收到 ${r.resp.frames.length} 帧`);
    check(r.resp.status === 200, '  下游 200 ✓', `  下游 HTTP=${r.resp.status}`);
    if (stream) {
      const isChunk = r.resp.frames.some((f) => {
        try { const j = JSON.parse(f); return j.object === 'chat.completion.chunk' && Array.isArray(j.choices); } catch { return false; }
      });
      check(isChunk, '  下游收到 OpenAI chunk 形态 ✓（object=chat.completion.chunk）',
        '  下游帧不是 OpenAI chunk 形态 —— 翻译未生效？');
      check(!r.resp.raw.includes('event: message_start') && !r.resp.raw.includes('content_block_delta'),
        '  下游未看到 Anthropic 事件 ✓（已转换）',
        '  下游收到 Anthropic 事件 —— 翻译未生效！');
      check(!r.resp.raw.includes('event:error'), '  无错误帧 ✓', `  收到错误帧: ${r.resp.raw.slice(0, 200)}`);
    }
  }

  /* ── D3 · 单工具参数 30+ 片拼接 ───────────────────────── */
  console.log('\n【D3 · translate-tool-split-args：30+ 片拼接为合法 JSON】');
  {
    const r = await probe('translate-tool-split-args', opts({ timeoutMs: 90000 }));
    const frames = parseFrames(r.resp.frames);
    const { indices, byIndex } = collectToolCalls(frames);
    const joined = byIndex.get(0) || '';
    let parsed = false;
    try { JSON.parse(joined); parsed = true; } catch { /* 非法 */ }
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms  解析出 ${frames.length} 个 JSON 帧`);
    info(`tool index = {${indices.join(',')}}  参数拼接后 ${joined.length} 字符  合法 JSON = ${parsed}`);
    check(indices.length === 1 && indices[0] === 0, '  tool index 恒为 0 ✓',
      `  tool index = {${indices.join(',')}}（期望仅 0）`);
    check(parsed, '  拼接后为合法 JSON ✓（转义序列跨片边界未损坏）',
      `  拼接结果不是合法 JSON: ${joined.slice(0, 120)}`);
  }

  /* ── D4 · 多工具 index 稠密重映射 ─────────────────────── */
  console.log('\n【D4 · translate-tool-multi-split：index 稠密重映射为 0/1/2】');
  {
    const r = await probe('translate-tool-multi-split', opts({ timeoutMs: 90000 }));
    const frames = parseFrames(r.resp.frames);
    const { indices, byIndex } = collectToolCalls(frames);
    const allValid = [...byIndex.values()].every((s) => { try { JSON.parse(s); return true; } catch { return false; } });
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms`);
    info(`tool index = {${indices.join(',')}}（上游 block index 是 1/2/3，0 给了 thinking）`);
    info(`工具数 = ${byIndex.size}  各自参数均合法 = ${allValid}`);
    check(indices.length === 3 && indices[0] === 0 && indices[1] === 1 && indices[2] === 2,
      '  index 稠密重映射为 0/1/2 ✓（未直接透传 block index）',
      `  index = {${indices.join(',')}}（期望 0,1,2）`);
    check(allValid, '  三个工具的参数各自为合法 JSON ✓', '  有工具参数不是合法 JSON');
  }

  /* ── D6 · 无参工具不补 {} ─────────────────────────────── */
  console.log('\n【D6 · translate-tool-no-args：零分片，不凭空补 {}】');
  {
    const r = await probe('translate-tool-no-args', opts({ timeoutMs: 60000 }));
    const frames = parseFrames(r.resp.frames);
    const { indices, byIndex } = collectToolCalls(frames);
    const args = [...byIndex.values()];
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms  tool index = {${indices.join(',')}}`);
    info(`arguments = ${JSON.stringify(args)}`);
    check(indices.length === 1, '  有且仅一个 tool index ✓（工具未被丢掉）',
      `  tool index = {${indices.join(',')}}（期望 1 个）`);
    check(args.length === 1 && args[0] === '',
      '  arguments 为空串 ✓（正确 —— 不能凭空补 {}）',
      `  arguments = ${JSON.stringify(args)}（期望空串）`);
  }

  /* ── D7 · finish_reason 覆盖 ──────────────────────────── */
  console.log('\n【D7 · translate-finish-reason：finish_reason 必须是 tool_calls】');
  console.log('   上游给的是 stop_reason=max_tokens + 完整工具调用 → 下游必须报 tool_calls');
  {
    const r = await probe('translate-finish-reason', opts({ timeoutMs: 60000 }));
    const frames = parseFrames(r.resp.frames);
    const { byIndex } = collectToolCalls(frames);
    const finish = lastFinishReason(frames);
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms  工具数 = ${byIndex.size}  finish_reason = ${JSON.stringify(finish)}`);
    check(byIndex.size > 0, '  下游收到完整工具调用 ✓', '  下游未收到工具调用');
    check(finish === 'tool_calls',
      '  finish_reason = tool_calls ✓（上游 max_tokens 被覆盖）',
      `  finish_reason = ${JSON.stringify(finish)} —— 若是 length，Copilot 会放弃执行已拿到的工具且不报错！`);
  }

  /* ── D7b · 非标 stop_reason 同样覆盖 ──────────────────── */
  console.log('\n【D7b · translate-finish-reason-nonstandard：非标 stop_reason 也要覆盖】');
  {
    const r = await probe('translate-finish-reason-nonstandard', opts({ timeoutMs: 60000 }));
    const frames = parseFrames(r.resp.frames);
    const finish = lastFinishReason(frames);
    info(`下游 HTTP=${r.resp.status} / ${r.wallMs}ms  finish_reason = ${JSON.stringify(finish)}`);
    check(finish === 'tool_calls', '  finish_reason = tool_calls ✓（非标值不是漏洞）',
      `  finish_reason = ${JSON.stringify(finish)}`);
  }

  console.log(`\n${fail === 0 ? '✓ D 组全部通过' : `✗ D 组有 ${fail} 项失败`}`);
  process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
