import { describe, expect, it } from 'vitest'
import {
  BREAKDOWN_DAYS,
  buildDailyPoints,
  mergeBreakdownDelta,
  windowDates,
} from './daily'
import type { UsageBreakdownRow, UsageRecordDelta } from './types'

/**
 * 按日归约的验证与锁定 —— 柱状图与「近 7 日」折线共用这一份。
 *
 * 重点是窗口语义：这里的「今天」是自然日，与今日时段折线的 5 点分界<strong>不同</strong>，
 * 同一条增量帧在两处可能得到相反的判定，两者都对。
 */

function row(
  date: string,
  providerKey: string,
  modelName: string,
  callCount: number,
  inputTokens = callCount * 100,
  outputTokens = callCount * 10,
): UsageBreakdownRow {
  return { date, providerKey, modelName, callCount, inputTokens, outputTokens }
}

function delta(createdAt: string, providerKey = 'p', modelName = 'm'): UsageRecordDelta {
  return { createdAt, providerKey, modelName, inputTokens: 100, outputTokens: 10 }
}

describe('窗口日期序列', () => {
  it('含今天共 7 天，升序排列', () => {
    const dates = windowDates(new Date(2026, 6, 27, 14, 0), 7)

    expect(dates).toHaveLength(7)
    expect(dates[0]).toBe('2026-07-21')
    expect(dates[6]).toBe('2026-07-27')
  })

  it('默认天数与常量一致', () => {
    expect(windowDates(new Date(2026, 6, 27))).toHaveLength(BREAKDOWN_DAYS)
  })

  it('跨月边界正确回溯', () => {
    const dates = windowDates(new Date(2026, 7, 2, 10, 0), 7)

    expect(dates[0]).toBe('2026-07-27')
    expect(dates[6]).toBe('2026-08-02')
  })

  /**
   * 自然日口径，不受 5 点分界影响。
   *
   * 凌晨 2 点看柱状图，「今天」就是当天日历日 —— 与今日时段折线回退到前一日不同。
   */
  it('凌晨时段仍按自然日，不回退', () => {
    const dates = windowDates(new Date(2026, 6, 27, 2, 0), 7)

    expect(dates[6]).toBe('2026-07-27')
  })
})

describe('按日归约成折线点位', () => {
  const dates = ['2026-07-26', '2026-07-27']

  it('同一天的多行求和', () => {
    const points = buildDailyPoints(
      [row('2026-07-27', 'a', 'm', 2, 200, 20), row('2026-07-27', 'b', 'n', 1, 100, 10)],
      dates,
    )

    expect(points).toHaveLength(2)
    expect(points[1]).toEqual({ bucket: '2026-07-27', inputTokens: 300, outputTokens: 30 })
  })

  /**
   * 缺失日期补零。
   *
   * 折线按点位等距绘制，若跳过无数据的日期，横轴就不再是等距时间轴，
   * 「隔了几天」这个信息会丢失。
   */
  it('无数据的日期补零而非跳过', () => {
    const points = buildDailyPoints([row('2026-07-27', 'a', 'm', 1)], dates)

    expect(points[0]).toEqual({ bucket: '2026-07-26', inputTokens: 0, outputTokens: 0 })
  })

  it('点位顺序与窗口一致', () => {
    const points = buildDailyPoints([], dates)

    expect(points.map((point) => point.bucket)).toEqual(dates)
  })

  it('窗口外的行不产生点位', () => {
    const points = buildDailyPoints([row('2026-07-01', 'a', 'm', 99)], dates)

    expect(points).toHaveLength(2)
    expect(points.every((point) => point.inputTokens === 0)).toBe(true)
  })
})

describe('增量合并', () => {
  const dates = ['2026-07-26', '2026-07-27']

  it('命中已有组合时累加次数与 token', () => {
    const rows = [row('2026-07-27', 'p', 'm', 2, 200, 20)]

    expect(mergeBreakdownDelta(rows, delta('2026-07-27T14:00:00'), dates)).toBe(true)
    expect(rows[0].callCount).toBe(3)
    expect(rows[0].inputTokens).toBe(300)
    expect(rows[0].outputTokens).toBe(30)
  })

  /**
   * 判定依据是「这一天是否已在窗口里」而非「组合是否已存在」。
   *
   * 某供应商今天首次被调用时，它的行本来就不存在，那种情况要插入而非丢弃。
   */
  it('窗口内的新组合被插入', () => {
    const rows = [row('2026-07-27', 'p', 'm', 1)]

    expect(mergeBreakdownDelta(rows, delta('2026-07-27T14:00:00', 'newbie', 'x'), dates)).toBe(true)
    expect(rows).toHaveLength(2)
    expect(rows[1]).toEqual({
      date: '2026-07-27',
      providerKey: 'newbie',
      modelName: 'x',
      callCount: 1,
      inputTokens: 100,
      outputTokens: 10,
    })
  })

  it('窗口内尚无任何数据的一天也能插入', () => {
    const rows: UsageBreakdownRow[] = []

    expect(mergeBreakdownDelta(rows, delta('2026-07-26T09:00:00'), dates)).toBe(true)
    expect(rows).toHaveLength(1)
  })

  /**
   * 窗口语义严格优先：跨过午夜后的新数据属于「明天」，直接丢弃。
   *
   * 擅自加一根柱子会让横轴凭空变长。刷新后窗口自然重算，新的一天才出现。
   */
  it('窗口外的日期被丢弃', () => {
    const rows = [row('2026-07-27', 'p', 'm', 1)]

    expect(mergeBreakdownDelta(rows, delta('2026-07-28T00:10:00'), dates)).toBe(false)
    expect(rows).toHaveLength(1)
    expect(rows[0].callCount).toBe(1)
  })

  it('早于窗口的日期也被丢弃', () => {
    const rows: UsageBreakdownRow[] = []

    expect(mergeBreakdownDelta(rows, delta('2026-07-01T12:00:00'), dates)).toBe(false)
    expect(rows).toHaveLength(0)
  })

  /**
   * 日期取自 createdAt 前 10 位 —— 与后端 `substr(created_at, 1, 10)` 同口径。
   *
   * 口径不一致会让增量落到错误的柱子上。
   */
  it('日期取 createdAt 前 10 位', () => {
    const rows: UsageBreakdownRow[] = []
    mergeBreakdownDelta(rows, delta('2026-07-27T23:59:59'), dates)

    expect(rows[0].date).toBe('2026-07-27')
  })

  /** 凌晨的调用按自然日归到当天，与今日时段折线的判定相反 —— 两者都对。 */
  it('凌晨 2 点的调用按自然日归属当天', () => {
    const rows: UsageBreakdownRow[] = []

    expect(mergeBreakdownDelta(rows, delta('2026-07-27T02:00:00'), dates)).toBe(true)
    expect(rows[0].date).toBe('2026-07-27')
  })
})
