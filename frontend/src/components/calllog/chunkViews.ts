import type { WireProtocol } from '@/types/protocol'

/**
 * 落库在 `api_call_log.chunks` 里的两种 JSON 形状。
 *
 * 后端用「一列两形」避免为跨协议单独加列（见 Java 侧 `ChunkLogPayload`）：
 *
 * ```jsonc
 * 直连：  ["{...}", "[DONE]"]                        裸数组
 * 翻译：  {"translated": [...], "upstream": [...]}    带标记的对象
 * ```
 *
 * 前端按形状分派即可，不需要额外字段告知「这条是不是翻译过的」。
 */
export const CHUNK_KEY_TRANSLATED = 'translated'
export const CHUNK_KEY_UPSTREAM = 'upstream'

/** 一条日志的 chunk 视图集合。 */
export interface ChunkViews {
  /**
   * 下游实际收到的 chunk。
   *
   * 直连时它就是上游原文（两侧协议相同）；翻译时它是翻译后的形态。
   * 规整显示与单栏块显示都用这一份 —— 它才是「客户端看到了什么」。
   */
  downstream: string[]
  /**
   * 上游原始事件。
   *
   * 仅跨协议翻译时存在。`null` 表示直连，此时不需要对照视图。
   */
  upstream: string[] | null
}

/**
 * 解析落库的 chunks 字段。
 *
 * <h2>为何容忍非 JSON</h2>
 * 历史数据与异常路径下这一列可能存的是裸字符串（如上游错误页）。
 * 那种情况包成单元素数组，让查看器仍能展示原文，而不是空白。
 */
export function parseChunkViews(rawChunks: string | null | undefined): ChunkViews {
  if (!rawChunks) {
    return { downstream: [], upstream: null }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(rawChunks)
  } catch {
    return { downstream: [rawChunks], upstream: null }
  }

  // 直连形态：裸数组。
  if (Array.isArray(parsed)) {
    return { downstream: parsed.map(String), upstream: null }
  }

  // 翻译形态：带标记的对象。
  if (isRecord(parsed)) {
    const translated = readStringArray(parsed[CHUNK_KEY_TRANSLATED])
    const upstream = readStringArray(parsed[CHUNK_KEY_UPSTREAM])
    // 两个键都缺失说明这是个我们不认识的对象形状，退回展示原文，
    // 而不是给出一个空视图让人以为没数据。
    if (translated === null && upstream === null) {
      return { downstream: [rawChunks], upstream: null }
    }
    return {
      downstream: translated ?? [],
      upstream,
    }
  }

  // 数字 / 布尔 / null 等标量：按原文展示。
  return { downstream: [rawChunks], upstream: null }
}

/** 是否存在可对照的双份视图。 */
export function hasComparisonView(views: ChunkViews): boolean {
  return views.upstream !== null
}

/**
 * 对照视图里「上游那一侧」的解析协议。
 *
 * 下游协议已由调用方给出；上游只可能是另一个 —— 当前只有两种协议，
 * 因此直接取反。将来加第三种协议时这里要改成由后端明确给出。
 */
export function upstreamProtocolOf(downstreamProtocol: WireProtocol): WireProtocol {
  return downstreamProtocol === 'OPENAI' ? 'ANTHROPIC' : 'OPENAI'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 读一个字符串数组字段；键缺失返回 null，与「存在但为空数组」区分开。 */
function readStringArray(value: unknown): string[] | null {
  if (!Array.isArray(value)) {
    return null
  }
  return value.map(String)
}
