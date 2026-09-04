import { describe, expect, it } from 'vitest'

import {
  buildAlignedRows,
  hasComparisonView,
  parseChunkViews,
  upstreamProtocolOf,
  type ChunkViews,
} from './chunkViews'

describe('parseChunkViews', () => {
  /** 直连：裸数组，与历史数据同形。 */
  it('reads a bare array as the downstream-only view', () => {
    const views = parseChunkViews(JSON.stringify(['{"a":1}', '[DONE]']))

    expect(views.downstream).toEqual(['{"a":1}', '[DONE]'])
    expect(views.upstream).toBeNull()
    expect(hasComparisonView(views)).toBe(false)
  })

  /** 跨协议：两份都在，可做对照。 */
  it('reads the tagged object as a comparison view', () => {
    const views = parseChunkViews(JSON.stringify({
      translated: ['{"object":"chat.completion.chunk"}', '[DONE]'],
      upstream: ['{"type":"message_start"}', '{"type":"ping"}', '{"type":"message_stop"}'],
      frameCounts: [1, 0, 0],
    }))

    expect(views.downstream).toHaveLength(2)
    expect(views.upstream).toHaveLength(3)
    expect(views.frameCounts).toEqual([1, 0, 0])
    expect(hasComparisonView(views)).toBe(true)
  })

  /** 直连没有对齐信息，`frameCounts` 为 null。 */
  it('leaves frameCounts null for the bare-array shape', () => {
    expect(parseChunkViews(JSON.stringify(['a'])).frameCounts).toBeNull()
  })

  /** 非数组的 frameCounts 归为 null，而不是让整条日志解析失败。 */
  it('ignores a malformed frameCounts field', () => {
    const views = parseChunkViews(JSON.stringify({
      translated: ['a'],
      upstream: ['b'],
      frameCounts: 'oops',
    }))

    expect(views.frameCounts).toBeNull()
    expect(views.downstream).toEqual(['a'])
  })

  /**
   * 帧数不对等是翻译路线的常态，解析器不做任何长度校验。
   *
   * 实测 26 个上游事件对应 20 个下游 chunk —— `content_block_start` / `stop`、
   * `signature_delta`、`ping`、`message_stop` 都不产出下游帧。
   */
  it('does not require the two views to have equal length', () => {
    const views = parseChunkViews(JSON.stringify({
      translated: ['a'],
      upstream: ['b', 'c', 'd', 'e'],
    }))

    expect(views.downstream).toHaveLength(1)
    expect(views.upstream).toHaveLength(4)
  })

  /** 只有 upstream 而没有 translated 时，下游侧为空但对照视图仍成立。 */
  it('keeps the comparison view when only upstream is present', () => {
    const views = parseChunkViews(JSON.stringify({ upstream: ['x'] }))

    expect(views.downstream).toEqual([])
    expect(views.upstream).toEqual(['x'])
    expect(hasComparisonView(views)).toBe(true)
  })

  /**
   * 历史数据与异常路径下这一列可能是裸字符串（如上游错误页）。
   * 包成单元素数组让查看器仍能展示原文，而不是空白。
   */
  it('falls back to raw text when the column is not JSON', () => {
    const views = parseChunkViews('<html>502 Bad Gateway</html>')

    expect(views.downstream).toEqual(['<html>502 Bad Gateway</html>'])
    expect(views.upstream).toBeNull()
  })

  /** 认不出的对象形状退回原文，而不是给出空视图误导「没数据」。 */
  it('falls back to raw text for an unrecognised object shape', () => {
    const raw = JSON.stringify({ somethingElse: 1 })
    const views = parseChunkViews(raw)

    expect(views.downstream).toEqual([raw])
    expect(views.upstream).toBeNull()
  })

  it('treats null and empty input as no data', () => {
    expect(parseChunkViews(null).downstream).toEqual([])
    expect(parseChunkViews('').downstream).toEqual([])
  })
})

describe('upstreamProtocolOf', () => {
  it('derives the opposite protocol', () => {
    expect(upstreamProtocolOf('OPENAI')).toBe('ANTHROPIC')
    expect(upstreamProtocolOf('ANTHROPIC')).toBe('OPENAI')
  })
})

describe('buildAlignedRows', () => {
  function viewsOf(partial: Partial<ChunkViews>): ChunkViews {
    return { downstream: [], upstream: null, frameCounts: null, ...partial }
  }

  /**
   * 每个上游事件一行，帧按 `frameCounts` 顺序分配。
   *
   * 零帧事件仍占一行（`frames` 为空）—— 跳过它会让后面所有行的编号错位。
   */
  it('gives each upstream event one row and slices frames in order', () => {
    const aligned = buildAlignedRows(viewsOf({
      upstream: ['message_start', 'content_block_start', 'ping', 'text_delta'],
      downstream: ['role-frame', 'content-frame'],
      frameCounts: [1, 0, 0, 1],
    }))

    expect(aligned.rows).toHaveLength(4)
    expect(aligned.rows[0].frames.map(f => f.chunk)).toEqual(['role-frame'])
    expect(aligned.rows[1].frames).toEqual([])
    expect(aligned.rows[2].frames).toEqual([])
    expect(aligned.rows[3].frames.map(f => f.chunk)).toEqual(['content-frame'])
    expect(aligned.trailing).toEqual([])
  })

  /** 帧上的 `index` 是它在 `downstream` 中的全局下标，卡片编号直接用它。 */
  it('keeps the global downstream index on each frame', () => {
    const aligned = buildAlignedRows(viewsOf({
      upstream: ['e0', 'e1'],
      downstream: ['f0', 'f1', 'f2'],
      frameCounts: [1, 2],
    }))

    expect(aligned.rows[0].frames.map(f => f.index)).toEqual([0])
    expect(aligned.rows[1].frames.map(f => f.index)).toEqual([1, 2])
  })

  /**
   * 收尾帧（finish + usage + `[DONE]`）不属于任何上游事件，单独成段。
   *
   * 挂到最后一个事件下面会谎称它们是那个事件译出来的。
   */
  it('collects the translator-appended tail separately', () => {
    const aligned = buildAlignedRows(viewsOf({
      upstream: ['e0'],
      downstream: ['f0', 'finish', 'usage', '[DONE]'],
      frameCounts: [1],
    }))

    expect(aligned.rows[0].frames.map(f => f.chunk)).toEqual(['f0'])
    expect(aligned.trailing.map(f => f.chunk)).toEqual(['finish', 'usage', '[DONE]'])
    expect(aligned.trailing.map(f => f.index)).toEqual([1, 2, 3])
  })

  /**
   * `frameCounts` 缺失时不按下标硬配。
   *
   * 那些行本来就没有映射信息，按下标配会给出一个看起来对、实际错位的结果。
   */
  it('puts every frame in the tail when frameCounts is absent', () => {
    const aligned = buildAlignedRows(viewsOf({
      upstream: ['e0', 'e1'],
      downstream: ['f0', 'f1'],
    }))

    expect(aligned.rows.map(r => r.frames)).toEqual([[], []])
    expect(aligned.trailing.map(f => f.chunk)).toEqual(['f0', 'f1'])
  })

  /** 总和超出下游长度时多出的部分拿不到帧，不抛异常、不产生 undefined 卡片。 */
  it('tolerates frameCounts overshooting the downstream length', () => {
    const aligned = buildAlignedRows(viewsOf({
      upstream: ['e0', 'e1'],
      downstream: ['f0'],
      frameCounts: [1, 5],
    }))

    expect(aligned.rows[0].frames.map(f => f.chunk)).toEqual(['f0'])
    expect(aligned.rows[1].frames).toEqual([])
    expect(aligned.trailing).toEqual([])
  })

  /** 负数与 `NaN` 当成不产帧，而不是让游标倒退或卡住。 */
  it('treats negative and non-numeric counts as zero frames', () => {
    const aligned = buildAlignedRows(viewsOf({
      upstream: ['e0', 'e1', 'e2'],
      downstream: ['f0'],
      frameCounts: [-3, Number.NaN, 1],
    }))

    expect(aligned.rows[0].frames).toEqual([])
    expect(aligned.rows[1].frames).toEqual([])
    expect(aligned.rows[2].frames.map(f => f.chunk)).toEqual(['f0'])
  })

  /** 直连（`upstream` 为 null）时没有行，块显示会走单栗分支。 */
  it('produces no rows for the direct-connection shape', () => {
    const aligned = buildAlignedRows(viewsOf({ downstream: ['f0', 'f1'] }))

    expect(aligned.rows).toEqual([])
    expect(aligned.trailing).toHaveLength(2)
  })
})
