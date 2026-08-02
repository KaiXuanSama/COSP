import { describe, expect, it } from 'vitest'
import {
  collectHourlySeriesResponse,
  dateAtDayOffset,
  hourlyWindowStartOf,
  liveAnchorDate,
  liveAnchorOffset,
  resolveHourlyDayTarget,
  selectHourlyTotals,
  type HourlyHistoryCache,
  type UsageHourlySeriesResponse,
} from './hourlyDay'
import type { TokenTotals } from './types'

/**
 * 验证与锁定：「今日时段」按日期回看的双栈模型。
 *
 * 三条主线：
 * <ul>
 *   <li><strong>实时栈优先且不可绕过</strong> —— 读错的表现是「今天的数字停在
 *       某个时刻不再涨」，不会报错。</li>
 *   <li><strong>5 点分界让实时锚定日可能是昨天</strong> —— 凌晨 3 点选「今天」
 *       只会得到一张全零的图，故硬墙要跟着退一天。</li>
 *   <li><strong>响应以自带的 windowStart 为准</strong> —— 后端收敛日期后，
 *       调用方手上那个「请求时算的窗口起点」是错的。</li>
 * </ul>
 */

/** 2026-07-31 14:30，落在当天窗口内（≥ 5 点）。 */
const AFTERNOON = new Date(2026, 6, 31, 14, 30)

/** 2026-07-31 03:00，凌晨 5 点前 —— 仍属 07-30 那一轮。 */
const PREDAWN = new Date(2026, 6, 31, 3, 0)

function totals(input: number, output: number): TokenTotals {
  return { inputTokens: input, outputTokens: output }
}

describe('liveAnchorDate / liveAnchorOffset', () => {
  it('5 点后锚定日就是今天', () => {
    expect(liveAnchorDate(AFTERNOON)).toBe('2026-07-31')
    expect(liveAnchorOffset(AFTERNOON)).toBe(0)
  })

  /**
   * 凌晨 5 点前锚定在昨天。
   *
   * <p>此时「今天」对应的窗口 `[07-31 05:00, 08-01 05:00)` 整段都在未来，
   * 选它只会得到一张全零的图，故硬墙要退到 −1。
   */
  it('5 点前锚定日是昨天', () => {
    expect(liveAnchorDate(PREDAWN)).toBe('2026-07-30')
    expect(liveAnchorOffset(PREDAWN)).toBe(-1)
  })

  /** 恰好 05:00 时已属新的一轮。 */
  it('恰好 5 点时锚定日为今天', () => {
    expect(liveAnchorOffset(new Date(2026, 6, 31, 5, 0))).toBe(0)
  })

  /** 04:59:59 仍属前一轮。 */
  it('4:59:59 仍锚定昨天', () => {
    expect(liveAnchorOffset(new Date(2026, 6, 31, 4, 59, 59))).toBe(-1)
  })

  /** 跨月边界正确 —— 8 月 1 日凌晨属于 7 月 31 日那一轮。 */
  it('跨月边界正确', () => {
    expect(liveAnchorDate(new Date(2026, 7, 1, 2, 0))).toBe('2026-07-31')
  })
})

describe('dateAtDayOffset', () => {
  it('0 为今天，负值往前', () => {
    expect(dateAtDayOffset(AFTERNOON, 0)).toBe('2026-07-31')
    expect(dateAtDayOffset(AFTERNOON, -1)).toBe('2026-07-30')
    expect(dateAtDayOffset(AFTERNOON, -14)).toBe('2026-07-17')
  })

  it('跨月往前正确', () => {
    expect(dateAtDayOffset(new Date(2026, 7, 2, 10, 0), -3)).toBe('2026-07-30')
  })
})

describe('hourlyWindowStartOf', () => {
  it('窗口起点是当天 05:00', () => {
    const start = hourlyWindowStartOf('2026-07-28')
    expect(start).not.toBeNull()
    expect(start!.getFullYear()).toBe(2026)
    expect(start!.getMonth()).toBe(6)
    expect(start!.getDate()).toBe(28)
    expect(start!.getHours()).toBe(5)
    expect(start!.getMinutes()).toBe(0)
  })

  it('畸形日期串返回 null', () => {
    expect(hourlyWindowStartOf('not-a-date')).toBeNull()
    expect(hourlyWindowStartOf('2026-02-31')).toBeNull()
  })
})

describe('resolveHourlyDayTarget', () => {
  /** 偏移 0 且在 5 点后 —— 就是实时窗口。 */
  it('今天在午后是实时窗口', () => {
    const target = resolveHourlyDayTarget(0, AFTERNOON)
    expect(target).toMatchObject({ date: '2026-07-31', live: true })
    expect(target.windowStart.getHours()).toBe(5)
    expect(target.windowStart.getDate()).toBe(31)
  })

  it('历史日期不是实时窗口', () => {
    expect(resolveHourlyDayTarget(-3, AFTERNOON)).toMatchObject({
      date: '2026-07-28',
      live: false,
    })
  })

  /**
   * 凌晨 5 点前，偏移 0（「今天」）<strong>不是</strong>实时窗口。
   *
   * <p>那一轮锚在昨天。若误判成实时，用户会看到一张全零的图却不知道为什么。
   */
  it('5 点前偏移 0 不是实时窗口', () => {
    expect(resolveHourlyDayTarget(0, PREDAWN)).toMatchObject({
      date: '2026-07-31',
      live: false,
    })
  })

  /** 凌晨 5 点前，偏移 −1 才是实时窗口。 */
  it('5 点前偏移 -1 是实时窗口', () => {
    expect(resolveHourlyDayTarget(-1, PREDAWN)).toMatchObject({
      date: '2026-07-30',
      live: true,
    })
  })
})

describe('selectHourlyTotals', () => {
  const live = new Map([['2026-07-31T14:00:00', totals(100, 20)]])

  function history(): HourlyHistoryCache {
    return new Map([['2026-07-28', new Map([['2026-07-28T10:00:00', totals(50, 5)]])]])
  }

  it('选中实时日时读实时栈', () => {
    const result = selectHourlyTotals('2026-07-31', '2026-07-31', live, history())
    expect(result.live).toBe(true)
    expect(result.missing).toBe(false)
    expect(result.totals).toBe(live)
  })

  it('缓存命中时读缓存', () => {
    const cache = history()
    const result = selectHourlyTotals('2026-07-28', '2026-07-31', live, cache)
    expect(result.live).toBe(false)
    expect(result.missing).toBe(false)
    expect(result.totals).toBe(cache.get('2026-07-28'))
  })

  it('缓存未命中时 missing 为真且给空表', () => {
    const result = selectHourlyTotals('2026-07-25', '2026-07-31', live, history())
    expect(result.missing).toBe(true)
    expect(result.totals.size).toBe(0)
  })

  /**
   * 实时日即使在缓存里也读实时栈。
   *
   * <p>后端把越界日期收敛到当前窗口时会出现这种重叠。缓存那份是某一刻的快照，
   * 读错的表现是「今天的数字停在某个时刻不再涨」。
   */
  it('实时日在缓存里也优先读实时栈', () => {
    const cache = history()
    cache.set('2026-07-31', new Map([['2026-07-31T09:00:00', totals(1, 1)]]))

    const result = selectHourlyTotals('2026-07-31', '2026-07-31', live, cache)
    expect(result.live).toBe(true)
    expect(result.totals).toBe(live)
  })

  /**
   * 实时栈为空也不算 missing。
   *
   * <p>空表表示「这一天确实还没有调用」而非「还没加载」—— 若判成 missing，
   * 一个安静的早晨会不停地发请求。
   */
  it('实时栈为空不算 missing', () => {
    const result = selectHourlyTotals('2026-07-31', '2026-07-31', new Map(), history())
    expect(result.missing).toBe(false)
    expect(result.live).toBe(true)
  })
})

describe('collectHourlySeriesResponse', () => {
  function response(overrides: Partial<UsageHourlySeriesResponse> = {}): UsageHourlySeriesResponse {
    return {
      date: '2026-07-28',
      windowStart: '2026-07-28T05:00:00',
      windowEnd: '2026-07-29T05:00:00',
      isCurrentWindow: false,
      points: [
        { bucket: '2026-07-28T05:00:00', inputTokens: 10, outputTokens: 1 },
        { bucket: '2026-07-28T14:00:00', inputTokens: 200, outputTokens: 40 },
        { bucket: '2026-07-29T05:00:00', inputTokens: 30, outputTokens: 3 },
      ],
      ...overrides,
    }
  }

  it('点位原样进桶表', () => {
    const totalsMap = collectHourlySeriesResponse(response())
    expect(totalsMap.size).toBe(3)
    expect(totalsMap.get('2026-07-28T14:00:00')).toEqual(totals(200, 40))
  })

  /** 末点位（次日 05:00）在窗口内 —— 25 个点位的第 25 个。 */
  it('末点位次日 5 点被保留', () => {
    expect(collectHourlySeriesResponse(response()).has('2026-07-29T05:00:00')).toBe(true)
  })

  /** 窗口外的点被丢弃，即使后端给了。 */
  it('窗口外的点被丢弃', () => {
    const totalsMap = collectHourlySeriesResponse(
      response({
        points: [
          { bucket: '2026-07-28T04:00:00', inputTokens: 1, outputTokens: 1 },
          { bucket: '2026-07-29T06:00:00', inputTokens: 1, outputTokens: 1 },
          { bucket: '2026-07-28T10:00:00', inputTokens: 5, outputTokens: 2 },
        ],
      }),
    )
    expect(totalsMap.size).toBe(1)
    expect(totalsMap.has('2026-07-28T10:00:00')).toBe(true)
  })

  /**
   * 以响应自带的 `windowStart` 为准。
   *
   * <p>后端收敛日期后点位属于另一天。若按请求参数算窗口，全部点位都会被判成越界，
   * 得到一张空图 —— 而没有任何报错。
   */
  it('窗口边界取自响应而非 date 字段', () => {
    const totalsMap = collectHourlySeriesResponse(
      response({
        // date 与 windowStart 刻意不一致，以证明用的是后者
        date: '2026-01-01',
        windowStart: '2026-07-28T05:00:00',
      }),
    )
    expect(totalsMap.size).toBe(3)
  })

  it('windowStart 无法解析时返回空表', () => {
    expect(collectHourlySeriesResponse(response({ windowStart: 'garbage' })).size).toBe(0)
  })

  it('points 不是数组时返回空表', () => {
    expect(
      collectHourlySeriesResponse(
        response({ points: null as unknown as UsageHourlySeriesResponse['points'] }),
      ).size,
    ).toBe(0)
  })

  /** 同一桶重复出现时累加，而非后者覆盖前者。 */
  it('同桶重复时累加', () => {
    const totalsMap = collectHourlySeriesResponse(
      response({
        points: [
          { bucket: '2026-07-28T10:00:00', inputTokens: 5, outputTokens: 2 },
          { bucket: '2026-07-28T10:00:00', inputTokens: 3, outputTokens: 1 },
        ],
      }),
    )
    expect(totalsMap.get('2026-07-28T10:00:00')).toEqual(totals(8, 3))
  })
})
