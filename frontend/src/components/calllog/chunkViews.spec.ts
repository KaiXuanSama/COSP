import { describe, expect, it } from 'vitest'

import {
  hasComparisonView,
  parseChunkViews,
  upstreamProtocolOf,
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
    }))

    expect(views.downstream).toHaveLength(2)
    expect(views.upstream).toHaveLength(3)
    expect(hasComparisonView(views)).toBe(true)
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
