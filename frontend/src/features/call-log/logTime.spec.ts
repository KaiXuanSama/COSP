import { describe, expect, it } from 'vitest'
import { formatLogTime } from './logTime'

const NOW = new Date(2026, 10, 5, 15, 0, 0)

describe('formatLogTime', () => {
  it('当天只显示时分秒', () => {
    expect(formatLogTime('2026-11-05T09:03:07', NOW)).toBe('09:03:07')
  })

  it('当年非当天显示月日', () => {
    expect(formatLogTime('2026-11-04T23:59:59', NOW)).toBe('11-04 23:59:59')
    expect(formatLogTime('2026-01-05T00:00:00', NOW)).toBe('01-05 00:00:00')
  })

  it('跨年显示完整日期', () => {
    expect(formatLogTime('2025-11-05T09:03:07', NOW)).toBe('2025-11-05 09:03:07')
  })

  it('无法识别的值原样返回', () => {
    expect(formatLogTime('', NOW)).toBe('')
    expect(formatLogTime('09:03:07', NOW)).toBe('09:03:07')
  })
})
