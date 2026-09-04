import type { WireProtocol } from '@/types/protocol'

/**
 * 落库在 `api_call_log.chunks` 里的两种 JSON 形状。
 *
 * 后端用「一列两形」避免为跨协议单独加列（见 Java 侧 `ChunkLogPayload`）：
 *
 * ```jsonc
 * 直连：  ["{...}", "[DONE]"]                        裸数组
 * 翻译：  {"translated": [...], "upstream": [...],
 *          "frameCounts": [1, 0, 0, 2]}                带标记的对象
 * ```
 *
 * 前端按形状分派即可，不需要额外字段告知「这条是不是翻译过的」。
 */
export const CHUNK_KEY_TRANSLATED = 'translated'
export const CHUNK_KEY_UPSTREAM = 'upstream'
export const CHUNK_KEY_FRAME_COUNTS = 'frameCounts'

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
  /**
   * 逐事件产帧数：`frameCounts[i]` 是第 i 个上游事件译出的下游帧数。
   *
   * 这是两栗对齐的**唯一依据**。帧数不对等是常态（零帧/一帧/多帧），
   * 事后从两个数组反推不出映射关系 —— 只有后端翻译当时的循环知道。
   *
   * 收尾帧（finish + usage + `[DONE]`）不计入其中，它们不属于任何上游事件；
   * 数量等于 `downstream.length - sum(frameCounts)`。
   */
  frameCounts: number[] | null
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
    return { downstream: [], upstream: null, frameCounts: null }
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(rawChunks)
  } catch {
    return { downstream: [rawChunks], upstream: null, frameCounts: null }
  }

  // 直连形态：裸数组。
  if (Array.isArray(parsed)) {
    return { downstream: parsed.map(String), upstream: null, frameCounts: null }
  }

  // 翻译形态：带标记的对象。
  if (isRecord(parsed)) {
    const translated = readStringArray(parsed[CHUNK_KEY_TRANSLATED])
    const upstream = readStringArray(parsed[CHUNK_KEY_UPSTREAM])
    // 两个键都缺失说明这是个我们不认识的对象形状，退回展示原文，
    // 而不是给出一个空视图让人以为没数据。
    if (translated === null && upstream === null) {
      return { downstream: [rawChunks], upstream: null, frameCounts: null }
    }
    return {
      downstream: translated ?? [],
      upstream,
      frameCounts: readNumberArray(parsed[CHUNK_KEY_FRAME_COUNTS]),
    }
  }

  // 数字 / 布尔 / null 等标量：按原文展示。
  return { downstream: [rawChunks], upstream: null, frameCounts: null }
}

/** 对齐行里的一个下游帧。`index` 是它在 `downstream` 中的全局下标。 */
export interface AlignedFrame {
  index: number
  chunk: string
}

/** 一个上游事件及它译出的下游帧。`frames` 为空表示这个事件不产帧。 */
export interface AlignedRow {
  upstreamIndex: number
  upstreamChunk: string
  frames: AlignedFrame[]
}

/** 对齐后的对照视图。 */
export interface AlignedChunkView {
  /** 逐上游事件的行，与 `upstream` 等长。 */
  rows: AlignedRow[]
  /**
   * 翻译层收尾帧（finish + usage + `[DONE]`）。
   *
   * 它们不对应任何上游事件 —— 是翻译层在流结束后自己补的，
   * 所以单独成段、左侧留空，而不是挂到最后一个事件下面假装有对应关系。
   */
  trailing: AlignedFrame[]
}

/**
 * 按 `frameCounts` 把两份视图编成对齐行。
 *
 * <h2>为何不按下标配对</h2>
 * 帧数不对等是常态：`content_block_start` / `ping` / `signature_delta` 不产帧，
 * 而 `message_start` 可能产多帧。按下标配对从第一个零帧事件开始就全错位了。
 *
 * <h2>`frameCounts` 缺失时的行为</h2>
 * 视为全 0，所有下游帧落到收尾段。不为旧数据写兼容分支 —— 那些行本来就
 * 没有映射信息，按下标硬配只会给出一个看起来对、实际错位的结果。
 *
 * <h2>为何容忍 `frameCounts` 与下游长度不一致</h2>
 * 总和超出 `downstream.length` 时多出的部分自然拿不到帧（游标已到尾）；
 * 不足时剩下的归入收尾段。这让脏数据不会丢掉任何一帧，也不会抛异常。
 */
export function buildAlignedRows(views: ChunkViews): AlignedChunkView {
  const upstream = views.upstream ?? []
  const counts = views.frameCounts ?? []
  const rows: AlignedRow[] = []
  let cursor = 0

  for (let i = 0; i < upstream.length; i += 1) {
    const wanted = normaliseFrameCount(counts[i])
    const frames: AlignedFrame[] = []
    for (let taken = 0; taken < wanted && cursor < views.downstream.length; taken += 1) {
      frames.push({ index: cursor, chunk: views.downstream[cursor] })
      cursor += 1
    }
    rows.push({ upstreamIndex: i, upstreamChunk: upstream[i], frames })
  }

  const trailing: AlignedFrame[] = []
  for (; cursor < views.downstream.length; cursor += 1) {
    trailing.push({ index: cursor, chunk: views.downstream[cursor] })
  }

  return { rows, trailing }
}

/** 该事件产出了几帧；缺失、负数与非数字均视为不产帧。 */
function normaliseFrameCount(value: number | undefined): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return 0
  }
  return Math.floor(value)
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

/**
 * 读逐事件产帧数。
 *
 * 非数字元素归为 `NaN`，由 `normaliseFrameCount` 当成「不产帧」处理 ——
 * 不在这里抛异常，否则一个脏元素会让整条日志变成空白。
 */
function readNumberArray(value: unknown): number[] | null {
  if (!Array.isArray(value)) {
    return null
  }
  return value.map(item => (typeof item === 'number' ? item : Number(item)))
}
