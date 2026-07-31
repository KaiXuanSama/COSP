import { describe, expect, it } from 'vitest'
import {
  DAY_START_HOUR,
  HOURLY_POINT_COUNT,
  bucketKeyOf,
  bucketOf,
  buildHourlyPoints,
  collectHourlySnapshot,
  isFuture,
  mergeHourlyDelta,
  nextFutureBoundary,
  resolveWindowStart,
} from './hourly'
import { formatLocalTimestamp } from './localTime'
import type { TokenTotals, UsageHourlyPoint, UsageRecordDelta } from './types'

/**
 * 「今日时段」归桶策略的验证与锁定。
 *
 * 这套规则是从后端搬来的，搬迁本身就是风险点：分界线错位一格不会报错，
 * 只会让数据静静地落到相邻的点上。因此把每条边界都锁死。
 */

/** 本地时刻的便捷构造，避免各处重复 `new Date(y, m - 1, d, ...)`。 */
function at(year: number, month: number, day: number, hour: number, minute = 0, second = 0): Date {
  return new Date(year, month - 1, day, hour, minute, second)
}

/** 造一条增量帧，只关心时刻与 token。 */
function delta(createdAt: string, inputTokens = 100, outputTokens = 10): UsageRecordDelta {
  return { createdAt, providerKey: 'p', modelName: 'm', inputTokens, outputTokens }
}

describe('窗口起点', () => {
  it('5 点之后锚定当天', () => {
    expect(resolveWindowStart(at(2026, 7, 28, 14, 30))).toEqual(at(2026, 7, 28, 5))
  })

  it('恰好 5 点锚定当天', () => {
    expect(resolveWindowStart(at(2026, 7, 28, 5, 0))).toEqual(at(2026, 7, 28, 5))
  })

  /**
   * 凌晨 5 点前回退一天 —— 这正是 5 点起算要解决的问题。
   *
   * 凌晨 3 点打开页面，看到的应是昨晚以来的连续曲线，而不是刚开始 3 小时的空图。
   */
  it('凌晨 5 点前回退到前一天', () => {
    expect(resolveWindowStart(at(2026, 7, 28, 3, 0))).toEqual(at(2026, 7, 27, 5))
  })

  it('4:59:59 仍属前一轮', () => {
    expect(resolveWindowStart(at(2026, 7, 28, 4, 59, 59))).toEqual(at(2026, 7, 27, 5))
  })

  it('午夜属前一轮', () => {
    expect(resolveWindowStart(at(2026, 7, 28, 0, 0))).toEqual(at(2026, 7, 27, 5))
  })

  it('跨月边界正确回退', () => {
    expect(resolveWindowStart(at(2026, 8, 1, 2, 0))).toEqual(at(2026, 7, 31, 5))
  })
})

describe('整点归桶', () => {
  /**
   * 整点前半小时归入下一个整点 —— 这是居中聚合与「整点起始」聚合的分野。
   *
   * 13:45 属于 14:00，因为 14:00 覆盖 [13:30, 14:30)。
   */
  it('整点前半小时归入下一个整点', () => {
    expect(bucketOf(at(2026, 7, 27, 13, 45))).toEqual(at(2026, 7, 27, 14))
  })

  it('整点后半小时归入该整点', () => {
    expect(bucketOf(at(2026, 7, 27, 14, 15))).toEqual(at(2026, 7, 27, 14))
  })

  it('整点本身归入自己', () => {
    expect(bucketOf(at(2026, 7, 27, 14, 0))).toEqual(at(2026, 7, 27, 14))
  })

  /** 分界线在 HH:30，该时刻归入下一个整点（区间右开左闭）。 */
  it('HH:30 是分界线，归入下一个整点', () => {
    expect(bucketOf(at(2026, 7, 27, 14, 29, 59))).toEqual(at(2026, 7, 27, 14))
    expect(bucketOf(at(2026, 7, 27, 14, 30, 0))).toEqual(at(2026, 7, 27, 15))
  })

  it('跨午夜归桶带上次日日期', () => {
    expect(bucketOf(at(2026, 7, 27, 23, 45))).toEqual(at(2026, 7, 28, 0))
  })

  /** 与后端 SQL `strftime(..., '+30 minutes')` 同一个式子，桶键格式也须一致。 */
  it('桶键格式与后端一致', () => {
    expect(bucketKeyOf('2026-07-27T13:45:09')).toBe('2026-07-27T14:00:00')
  })
})

describe('尚未到来的判定', () => {
  /**
   * 判定口径必须与聚合口径一致。
   *
   * 13:50 时 14:00 那个点已经在收数据（它覆盖 13:30 起），若按「整点是否已过」
   * 判断会把它标成未来，刚发生的调用随即从折线上被剪掉，直到 14:00 整才突然出现。
   */
  it('点位在整点前半小时即视为已开始', () => {
    expect(isFuture(at(2026, 7, 27, 14), at(2026, 7, 27, 13, 50))).toBe(false)
  })

  it('距整点超过半小时仍是未来', () => {
    expect(isFuture(at(2026, 7, 27, 14), at(2026, 7, 27, 13, 20))).toBe(true)
  })

  it('恰好在覆盖起点时已开始', () => {
    expect(isFuture(at(2026, 7, 27, 14), at(2026, 7, 27, 13, 30))).toBe(false)
  })

  it('已过整点自然不是未来', () => {
    expect(isFuture(at(2026, 7, 27, 14), at(2026, 7, 27, 15, 0))).toBe(false)
  })
})

describe('下一次推进时刻', () => {
  /**
   * 只在 HH:30 变化 —— 不是把周期放宽的近似，而是精确命中唯一会让图变化的时刻。
   *
   * 中间任何时刻重绘都是纯浪费；原先 30 秒兜底一小时里有 119 次是白跑的。
   */
  it('半点前指向本小时的半点', () => {
    expect(nextFutureBoundary(at(2026, 7, 27, 14, 10))).toEqual(at(2026, 7, 27, 14, 30))
  })

  it('半点后指向下一小时的半点', () => {
    expect(nextFutureBoundary(at(2026, 7, 27, 14, 40))).toEqual(at(2026, 7, 27, 15, 30))
  })

  it('恰好在半点时指向下一小时，不会算出 0 延迟', () => {
    // 0 延迟会让定时器立刻回调、再次算出 0，形成忙循环
    expect(nextFutureBoundary(at(2026, 7, 27, 14, 30, 0))).toEqual(at(2026, 7, 27, 15, 30))
  })

  it('跨午夜正确进位', () => {
    expect(nextFutureBoundary(at(2026, 7, 27, 23, 45))).toEqual(at(2026, 7, 28, 0, 30))
  })
})

describe('点位构造', () => {
  const windowStart = at(2026, 7, 27, 5)

  it('恒为 25 个点，首尾都是 05:00 使一圈闭合', () => {
    const points = buildHourlyPoints(new Map(), windowStart, at(2026, 7, 28, 5))

    expect(points).toHaveLength(HOURLY_POINT_COUNT)
    expect(points[0].label).toBe('05:00')
    expect(points[HOURLY_POINT_COUNT - 1].label).toBe('05:00')
  })

  /** 首尾两个 05:00 分属不同日期，故桶键必须带日期才能区分。 */
  it('首尾两点的桶键日期不同', () => {
    const points = buildHourlyPoints(new Map(), windowStart, at(2026, 7, 28, 5))

    expect(points[0].bucket).toBe('2026-07-27T05:00:00')
    expect(points[HOURLY_POINT_COUNT - 1].bucket).toBe('2026-07-28T05:00:00')
  })

  it('无数据的桶补零', () => {
    const points = buildHourlyPoints(new Map(), windowStart, at(2026, 7, 28, 5))

    expect(points.every((point) => point.inputTokens === 0 && point.outputTokens === 0)).toBe(true)
  })

  it('桶表中的值填入对应点位', () => {
    const totals = new Map<string, TokenTotals>([
      ['2026-07-27T14:00:00', { inputTokens: 350, outputTokens: 40 }],
    ])
    const points = buildHourlyPoints(totals, windowStart, at(2026, 7, 28, 5))

    const hit = points.find((point) => point.label === '14:00')
    expect(hit?.inputTokens).toBe(350)
    expect(hit?.outputTokens).toBe(40)
  })

  /**
   * 返回完整一天而非截断到当前 —— 横轴因此不随时间伸缩。
   *
   * 使用者能一眼看出「今天还剩多少时间」，各时段的横向位置也不会在刷新时移动。
   */
  it('未来时段标记 future，但仍占位', () => {
    const points = buildHourlyPoints(new Map(), windowStart, at(2026, 7, 27, 10, 0))

    expect(points).toHaveLength(HOURLY_POINT_COUNT)
    // 10:00 已发生（覆盖 09:30 起），11:00 尚未开始（覆盖 10:30 起）
    expect(points.find((point) => point.label === '10:00')?.future).toBe(false)
    expect(points.find((point) => point.label === '11:00')?.future).toBe(true)
  })

  it('窗口起始小时与常量一致', () => {
    const points = buildHourlyPoints(new Map(), windowStart, at(2026, 7, 28, 5))

    expect(points[0].label).toBe(`${String(DAY_START_HOUR).padStart(2, '0')}:00`)
  })
})

describe('快照收集', () => {
  const windowStart = at(2026, 7, 27, 5)

  it('窗口内的点被收进桶表', () => {
    const snapshot: UsageHourlyPoint[] = [
      { bucket: '2026-07-27T14:00:00', inputTokens: 100, outputTokens: 10 },
      { bucket: '2026-07-28T01:00:00', inputTokens: 200, outputTokens: 20 },
    ]

    const totals = collectHourlySnapshot(snapshot, windowStart)

    expect(totals.get('2026-07-27T14:00:00')).toEqual({ inputTokens: 100, outputTokens: 10 })
    expect(totals.get('2026-07-28T01:00:00')).toEqual({ inputTokens: 200, outputTokens: 20 })
  })

  /**
   * 窗口外的点被丢弃。
   *
   * 后端窗口与前端展示窗口理应一致，但两者各自计算，跨过 5 点的瞬间可能相差一格；
   * 以前端窗口为准可保证轴不会凭空多出一个点。
   */
  it('窗口外的点被丢弃', () => {
    const snapshot: UsageHourlyPoint[] = [
      { bucket: '2026-07-27T04:00:00', inputTokens: 1, outputTokens: 0 },
      { bucket: '2026-07-28T06:00:00', inputTokens: 2, outputTokens: 0 },
      { bucket: '2026-07-27T14:00:00', inputTokens: 3, outputTokens: 0 },
    ]

    const totals = collectHourlySnapshot(snapshot, windowStart)

    expect(totals.size).toBe(1)
    expect(totals.get('2026-07-27T14:00:00')?.inputTokens).toBe(3)
  })

  it('窗口两端的点都包含在内', () => {
    const snapshot: UsageHourlyPoint[] = [
      { bucket: '2026-07-27T05:00:00', inputTokens: 1, outputTokens: 0 },
      { bucket: '2026-07-28T05:00:00', inputTokens: 2, outputTokens: 0 },
    ]

    expect(collectHourlySnapshot(snapshot, windowStart).size).toBe(2)
  })
})

describe('增量合并', () => {
  const windowStart = at(2026, 7, 27, 5)

  it('落在已有桶时累加', () => {
    const totals = new Map<string, TokenTotals>([
      ['2026-07-27T14:00:00', { inputTokens: 100, outputTokens: 10 }],
    ])

    expect(mergeHourlyDelta(totals, delta('2026-07-27T13:45:00', 250, 30), windowStart)).toBe(true)
    expect(totals.get('2026-07-27T14:00:00')).toEqual({ inputTokens: 350, outputTokens: 40 })
  })

  it('落在空桶时新建', () => {
    const totals = new Map<string, TokenTotals>()

    expect(mergeHourlyDelta(totals, delta('2026-07-27T14:15:00', 100, 10), windowStart)).toBe(true)
    expect(totals.get('2026-07-27T14:00:00')).toEqual({ inputTokens: 100, outputTokens: 10 })
  })

  /**
   * 窗口冻结：跨过 5 点后的新调用属于下一轮，不该挤进当前这一天。
   *
   * 那会让轴凭空变长。刷新后窗口重算，新的一轮才出现。
   */
  it('超出窗口末端的帧被丢弃', () => {
    const totals = new Map<string, TokenTotals>()

    expect(mergeHourlyDelta(totals, delta('2026-07-28T06:00:00'), windowStart)).toBe(false)
    expect(totals.size).toBe(0)
  })

  it('早于窗口起点的帧被丢弃', () => {
    const totals = new Map<string, TokenTotals>()

    expect(mergeHourlyDelta(totals, delta('2026-07-27T03:00:00'), windowStart)).toBe(false)
    expect(totals.size).toBe(0)
  })

  /** 04:40 归入次日 05:00，正好是窗口最后一个点，应被接受。 */
  it('窗口末点覆盖的前半小时仍被接受', () => {
    const totals = new Map<string, TokenTotals>()

    expect(mergeHourlyDelta(totals, delta('2026-07-28T04:40:00'), windowStart)).toBe(true)
    expect(totals.get('2026-07-28T05:00:00')).toBeDefined()
  })

  /** 增量与快照走同一个归桶函数，故两者必然落在同一个桶键上。 */
  it('增量与快照的桶键口径一致', () => {
    const totals = collectHourlySnapshot(
      [{ bucket: bucketKeyOf('2026-07-27T13:45:00'), inputTokens: 100, outputTokens: 10 }],
      windowStart,
    )
    mergeHourlyDelta(totals, delta('2026-07-27T14:20:00', 50, 5), windowStart)

    expect(totals.size).toBe(1)
    expect(totals.get('2026-07-27T14:00:00')).toEqual({ inputTokens: 150, outputTokens: 15 })
  })

  it('桶键与 buildHourlyPoints 生成的键匹配', () => {
    const totals = new Map<string, TokenTotals>()
    mergeHourlyDelta(totals, delta('2026-07-27T14:20:00', 77, 7), windowStart)

    const points = buildHourlyPoints(totals, windowStart, at(2026, 7, 28, 5))
    expect(points.find((point) => point.bucket === formatLocalTimestamp(at(2026, 7, 27, 14)))?.inputTokens).toBe(77)
  })
})
