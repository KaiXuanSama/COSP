import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import {
  buildDateSegments,
  isLabelVisible,
  shortDate,
  toPolylinePoints,
  useTimelineSeries,
  xRatioOf,
  yRatioOf,
} from './useTimelineSeries'
import {
  DAY_START_HOUR,
  SERIES,
  type TimelineRange,
  type UsageTimelinePoint,
} from './usageline'

/**
 * 折线换算逻辑的验证与锁定。
 *
 * 这些函数把「数值序列」翻成「坐标序列」，中间藏着几处只在特定时刻才暴露的边界：
 * 单点居中、跨夜日期归属、午夜是否落在桶边界。它们在页面上很难复现，
 * 因此全部在这里锁死。
 */

/** 造一批点位，只关心数量与数值。 */
function points(...values: Array<[number, number]>): UsageTimelinePoint[] {
  return values.map(([input, output], index) => ({
    bucket: `b${index}`,
    inputTokens: input,
    outputTokens: output,
  }))
}

/** 造一批完整的今日点位：前 elapsed 个已发生，其余标记为未来。 */
function dayPoints(total: number, elapsed: number): UsageTimelinePoint[] {
  return Array.from({ length: total }, (_unused, index) => ({
    bucket: `b${index}`,
    inputTokens: index < elapsed ? 100 : 0,
    outputTokens: index < elapsed ? 10 : 0,
    future: index >= elapsed,
  }))
}

describe('横向位置换算', () => {
  it('首尾点贴住绘图区两端，折线因此铺满整个宽度', () => {
    expect(xRatioOf(0, 5)).toBe(0)
    expect(xRatioOf(4, 5)).toBe(1)
  })

  it('中间点等距分布', () => {
    expect(xRatioOf(1, 5)).toBeCloseTo(0.25)
    expect(xRatioOf(2, 5)).toBeCloseTo(0.5)
  })

  it('只有一个点时居中而非贴左', () => {
    // 贴在左边缘会让孤立点看起来像图被截断了
    expect(xRatioOf(0, 1)).toBe(0.5)
  })

  it('无点位时不产生除零', () => {
    expect(xRatioOf(0, 0)).toBe(0.5)
  })
})

describe('纵向位置换算', () => {
  it('按值占轴上限的比例定位', () => {
    expect(yRatioOf(50, 200)).toBeCloseTo(0.25)
    expect(yRatioOf(200, 200)).toBe(1)
  })

  it('轴上限为 0 时返回 0 而非 NaN', () => {
    // NaN 流进 SVG 坐标会让整条折线消失，且控制台不报错，极难排查
    expect(yRatioOf(10, 0)).toBe(0)
  })

  it('负值与零值都落在基线上', () => {
    expect(yRatioOf(0, 200)).toBe(0)
    expect(yRatioOf(-5, 200)).toBe(0)
  })

  it('超出上限的值被钳在顶端', () => {
    expect(yRatioOf(300, 200)).toBe(1)
  })
})

describe('三条线的取值', () => {
  it('总量恒等于输入加输出', () => {
    // 总量不占传输字段，由此处现算 —— 这条断言锁住「不会与后端不一致」
    const point: UsageTimelinePoint = { bucket: 'x', inputTokens: 1200, outputTokens: 34 }
    const total = SERIES.find((series) => series.key === 'total')!
    expect(total.valueOf(point)).toBe(1234)
  })

  it('输入与输出各取自己的字段', () => {
    const point: UsageTimelinePoint = { bucket: 'x', inputTokens: 1200, outputTokens: 34 }
    expect(SERIES.find((series) => series.key === 'input')!.valueOf(point)).toBe(1200)
    expect(SERIES.find((series) => series.key === 'output')!.valueOf(point)).toBe(34)
  })

  it('输出线用虚线，其余为实线', () => {
    // 线型在浮框的行首标记里仍会用到，故即便不绘制也保留配置
    expect(SERIES.find((series) => series.key === 'output')!.dash).toBeDefined()
    expect(SERIES.find((series) => series.key === 'total')!.dash).toBeUndefined()
    expect(SERIES.find((series) => series.key === 'input')!.dash).toBeUndefined()
  })

  it('只有总量画成折线', () => {
    // 输入占总量绝大部分、输出贴着横轴，三条线画出来读不出独立走势，
    // 只会让图变脏；它们的绝对值改由悬停浮框给出
    expect(SERIES.filter((series) => series.drawn)).toHaveLength(1)
    expect(SERIES.find((series) => series.drawn)!.key).toBe('total')
  })
})

describe('横轴标签稀疏度', () => {
  it('点数少时全部显示', () => {
    expect([0, 1, 2, 3, 4, 5, 6].every((index) => isLabelVisible(index, 7))).toBe(true)
  })

  it('首尾标签永不隐藏', () => {
    // 它们标出时间轴的两端，是最不能省的两个
    expect(isLabelVisible(0, 24)).toBe(true)
    expect(isLabelVisible(23, 24)).toBe(true)
  })

  it('点数多时隔位显示', () => {
    // 24 个点在卡片宽度下必然挤压，隔一位显示后间距大致恒定
    const visible = Array.from({ length: 24 }, (_unused, index) => isLabelVisible(index, 24))
    expect(visible.filter(Boolean).length).toBeLessThan(24)
    expect(visible[1]).toBe(false)
    expect(visible[2]).toBe(true)
  })

  it('单点时显示', () => {
    expect(isLabelVisible(0, 1)).toBe(true)
  })
})

describe('日期格式化', () => {
  it('去掉年份与前导零', () => {
    expect(shortDate('2026-07-06')).toBe('7/6')
    expect(shortDate('2026-12-31')).toBe('12/31')
  })

  it('格式不符时原样返回', () => {
    expect(shortDate('05:00')).toBe('05:00')
  })
})

describe('横轴日期分段', () => {
  /** 今日范围固定 25 个点（05:00 … 04:00 05:00），午夜是第 19 个。 */
  const DAY_POINTS = 25
  const MIDNIGHT_INDEX = 24 - DAY_START_HOUR

  it('午夜落在第 19 个点上', () => {
    // 每小时一个点，从 05:00 数到 00:00 正好 19 步
    expect(MIDNIGHT_INDEX).toBe(19)
  })

  it('点数不足以跨过午夜时只有一段', () => {
    const segments = buildDateSegments(points([1, 1], [2, 2], [3, 3]))

    expect(segments).toHaveLength(1)
    expect(segments[0].start).toBe(0)
    expect(segments[0].width).toBe(1)
  })

  it('完整一天分成两段且首尾相接', () => {
    const segments = buildDateSegments(
      points(...Array.from({ length: DAY_POINTS }, () => [1, 1] as [number, number])),
    )

    expect(segments).toHaveLength(2)
    expect(segments[0].start).toBe(0)
    // 两段首尾相接，中间不留缝也不重叠
    expect(segments[0].width).toBeCloseTo(segments[1].start)
    expect(segments[0].width + segments[1].width).toBeCloseTo(1)
  })

  it('分界线落在午夜对应的比例位置', () => {
    const segments = buildDateSegments(
      points(...Array.from({ length: DAY_POINTS }, () => [1, 1] as [number, number])),
    )

    expect(segments[0].width).toBeCloseTo(xRatioOf(MIDNIGHT_INDEX, DAY_POINTS))
  })

  it('两段标签为相邻的两天', () => {
    const segments = buildDateSegments(
      points(...Array.from({ length: DAY_POINTS }, () => [1, 1] as [number, number])),
    )

    expect(segments).toHaveLength(2)
    expect(segments[0].label).not.toBe(segments[1].label)
  })

  it('无点位时不产出分段', () => {
    expect(buildDateSegments([])).toEqual([])
  })
})

describe('SVG 点串生成', () => {
  it('纵向翻转，因为 SVG 的 y 轴向下增长', () => {
    // 数据的 y 是「距底部的比例」，直接用会让折线上下颠倒
    const result = toPolylinePoints([{ x: 0, y: 1 }], 100, 200)

    expect(result).toBe('0.00,0.00')
  })

  it('基线上的点落在 viewBox 底部', () => {
    expect(toPolylinePoints([{ x: 1, y: 0 }], 100, 200)).toBe('100.00,200.00')
  })

  it('多点以空格分隔', () => {
    const result = toPolylinePoints([{ x: 0, y: 0 }, { x: 0.5, y: 0.5 }], 100, 100)

    expect(result.split(' ')).toHaveLength(2)
  })

  it('无点位时返回空串', () => {
    expect(toPolylinePoints([], 100, 100)).toBe('')
  })
})

describe('今日范围的完整一天与未来段', () => {
  const range = ref<TimelineRange>('1d')

  it('折线只画到最后一个已发生的点', () => {
    // 未来段的 0 是「还没发生」而非「用量为零」，连过去会让折线贴底延伸到轴末，
    // 读起来像用量已归零
    const data = ref(dayPoints(25, 5))
    const { series, drawableCount } = useTimelineSeries(data, range)

    expect(drawableCount.value).toBe(5)
    expect(series.value[0].points).toHaveLength(5)
  })

  it('横向位置仍按完整一天换算，横轴因此不随时间伸缩', () => {
    // 这是「显示完整一天」的关键：已发生的第 5 个点应落在 4/24 处，
    // 而非被拉伸到占满整个宽度
    const data = ref(dayPoints(25, 5))
    const { series } = useTimelineSeries(data, range)

    expect(series.value[0].points[4].x).toBeCloseTo(xRatioOf(4, 25))
  })

  it('横轴标签覆盖完整一天并标出未来段', () => {
    // 未来段的标签要保留：它们标出「今天还剩多少时间」
    const data = ref(dayPoints(25, 5))
    const { axisLabels } = useTimelineSeries(data, range)

    expect(axisLabels.value).toHaveLength(25)
    expect(axisLabels.value.filter((label) => label.future)).toHaveLength(20)
    expect(axisLabels.value[4].future).toBe(false)
    expect(axisLabels.value[5].future).toBe(true)
  })

  it('轴上限只看已发生的点', () => {
    const data = ref(dayPoints(25, 3))
    const { ceiling } = useTimelineSeries(data, range)

    // 三个点各 100 输入 + 10 输出，总量线最大 110
    expect(ceiling.value).toBeGreaterThanOrEqual(110)
  })

  it('全天都已发生时所有点都可绘制', () => {
    const data = ref(dayPoints(25, 25))
    const { series, drawableCount } = useTimelineSeries(data, range)

    expect(drawableCount.value).toBe(25)
    expect(series.value[0].points).toHaveLength(25)
  })

  it('近 7 日范围不带未来标记，全部可绘制', () => {
    const data = ref(points([1, 1], [2, 2], [3, 3]))
    const weekRange = ref<TimelineRange>('7d')
    const { series, drawableCount, dateSegments } = useTimelineSeries(data, weekRange)

    expect(drawableCount.value).toBe(3)
    expect(series.value[0].points).toHaveLength(3)
    // 日期分段只属于今日范围
    expect(dateSegments.value).toEqual([])
  })
})
