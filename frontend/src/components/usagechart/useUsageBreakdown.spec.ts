import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { useUsageBreakdown } from './useUsageBreakdown'
import type { BreakdownDimension, BreakdownMetric, UsageBreakdownRow } from './usagechart'

/** 第一版配置：主维度为供应商，次维度为模型。 */
const providerFirst: BreakdownDimension = {
  key: 'provider-model',
  primaryOf: row => row.providerKey,
  secondaryOf: row => row.modelName,
  primaryTerm: '供应商',
  secondaryTerm: '模型',
}

/** 主次互换：主维度为模型，次维度为供应商（将来的视图）。 */
const modelFirst: BreakdownDimension = {
  key: 'model-provider',
  primaryOf: row => row.modelName,
  secondaryOf: row => row.providerKey,
  primaryTerm: '模型',
  secondaryTerm: '供应商',
}

const callCountMetric: BreakdownMetric = {
  key: 'calls',
  display: '调用次数',
  unit: '次',
  valueOf: row => row.callCount,
}

function row(date: string, providerKey: string, modelName: string, callCount: number): UsageBreakdownRow {
  return { date, providerKey, modelName, callCount }
}

function setup(rows: UsageBreakdownRow[], dimension: BreakdownDimension = providerFirst) {
  return useUsageBreakdown(ref(rows), ref(dimension), ref(callCountMetric))
}

describe('一级：按天堆叠', () => {
  it('按日期升序分列，并汇总每列总量', () => {
    const { columns } = setup([
      row('2026-07-25', 'deepseek', 'v4', 10),
      row('2026-07-24', 'deepseek', 'v4', 5),
      row('2026-07-26', 'deepseek', 'v4', 7),
    ])

    // key 即柱子身份：一级为日期（层级切换时靠它复用 DOM 节点）
    expect(columns.value.map(c => c.key)).toEqual(['2026-07-24', '2026-07-25', '2026-07-26'])
    expect(columns.value.map(c => c.total)).toEqual([5, 10, 7])
  })

  it('同一主维度的多个次维度合并为一段', () => {
    const { columns } = setup([
      row('2026-07-26', 'deepseek', 'v4-flash', 30),
      row('2026-07-26', 'deepseek', 'v4-pro', 20),
    ])

    const segments = columns.value[0].segments
    expect(segments).toHaveLength(1)
    expect(segments[0].primary).toBe('deepseek')
    expect(segments[0].value).toBe(50)
    expect(segments[0].ratio).toBe(1)
  })

  it('每列各自按值降序排列', () => {
    const { columns } = setup([
      row('2026-07-26', 'small', 'm', 20),
      row('2026-07-26', 'big', 'm', 70),
      row('2026-07-26', 'mid', 'm', 10),
    ])

    expect(columns.value[0].segments.map(s => s.primary)).toEqual(['big', 'small', 'mid'])
  })

  it('相邻列可以有不同的排列顺序（每列独立排序）', () => {
    const { columns } = setup([
      row('2026-07-25', 'a', 'm', 90),
      row('2026-07-25', 'b', 'm', 10),
      row('2026-07-26', 'a', 'm', 10),
      row('2026-07-26', 'b', 'm', 90),
    ])

    expect(columns.value[0].segments.map(s => s.primary)).toEqual(['a', 'b'])
    expect(columns.value[1].segments.map(s => s.primary)).toEqual(['b', 'a'])
  })

  it('等值时按名称升序，保证渲染稳定', () => {
    const { columns } = setup([
      row('2026-07-26', 'zebra', 'm', 50),
      row('2026-07-26', 'alpha', 'm', 50),
    ])

    expect(columns.value[0].segments.map(s => s.primary)).toEqual(['alpha', 'zebra'])
  })

  it('maxColumnTotal 取各列总量的最大值，供柱高归一化', () => {
    const { maxColumnTotal } = setup([
      row('2026-07-25', 'p', 'm', 8),
      row('2026-07-26', 'p', 'm', 23),
    ])

    expect(maxColumnTotal.value).toBe(23)
  })

  it('空数据不产生列，maxColumnTotal 为 0', () => {
    const { columns, maxColumnTotal } = setup([])

    expect(columns.value).toEqual([])
    expect(maxColumnTotal.value).toBe(0)
  })
})

describe('other 合并：阈值与边界', () => {
  it('占比低于阈值的主维度合并为 other 并固定末尾', () => {
    // 总 100：big 96%（保留），tiny1 3%、tiny2 1%（均低于 5%，合并）
    const { columns } = setup([
      row('2026-07-26', 'big', 'm', 96),
      row('2026-07-26', 'tiny1', 'm', 3),
      row('2026-07-26', 'tiny2', 'm', 1),
    ])

    const segments = columns.value[0].segments
    expect(segments.map(s => s.primary)).toEqual(['big', null])
    expect(segments[1].isOther).toBe(true)
    expect(segments[1].value).toBe(4)
  })

  it('恰好等于阈值的主维度被保留（>= 判定）', () => {
    // tiny 正好 5%
    const { columns } = setup([
      row('2026-07-26', 'big', 'm', 95),
      row('2026-07-26', 'tiny', 'm', 5),
    ])

    const segments = columns.value[0].segments
    expect(segments.map(s => s.primary)).toEqual(['big', 'tiny'])
    expect(segments.some(s => s.isOther)).toBe(false)
  })

  it('无需合并时不产生 other 段', () => {
    const { columns } = setup([
      row('2026-07-26', 'a', 'm', 50),
      row('2026-07-26', 'b', 'm', 50),
    ])

    expect(columns.value[0].segments.some(s => s.isOther)).toBe(false)
  })

  it('所有主维度都低于阈值时全部并入 other', () => {
    // 21 个各约 4.76%，全部低于 5%
    const rows = Array.from({ length: 21 }, (_, i) => row('2026-07-26', `p${i}`, 'm', 1))
    const { columns } = setup(rows)

    const segments = columns.value[0].segments
    expect(segments).toHaveLength(1)
    expect(segments[0].isOther).toBe(true)
    expect(segments[0].value).toBe(21)
    expect(segments[0].ratio).toBe(1)
  })

  it('段占比之和为 1', () => {
    const { columns } = setup([
      row('2026-07-26', 'big', 'm', 80),
      row('2026-07-26', 'mid', 'm', 17),
      row('2026-07-26', 'tiny', 'm', 3),
    ])

    const sum = columns.value[0].segments.reduce((acc, s) => acc + s.ratio, 0)
    expect(sum).toBeCloseTo(1)
  })
})

describe('hover 明细：普通段单层', () => {
  it('列出次维度占所属主维度的比例', () => {
    const { columns } = setup([
      row('2026-07-26', 'deepseek', 'v4-flash', 75),
      row('2026-07-26', 'deepseek', 'v4-pro', 25),
    ])

    const detail = columns.value[0].segments[0].detail
    expect(detail.map(d => d.label)).toEqual(['v4-flash', 'v4-pro'])
    expect(detail[0].ratio).toBeCloseTo(0.75)
    expect(detail[1].ratio).toBeCloseTo(0.25)
    // 单层明细不带 children
    expect(detail[0].children).toBeUndefined()
  })

  it('单一次维度时占比为 100%', () => {
    const { columns } = setup([row('2026-07-26', 'deepseek', 'v4-flash', 42)])

    const detail = columns.value[0].segments[0].detail
    expect(detail).toHaveLength(1)
    expect(detail[0].ratio).toBe(1)
  })
})

describe('hover 明细：other 段双层', () => {
  it('第一层为主维度占 other 的比例，第二层为其内部次维度占比', () => {
    const { columns } = setup([
      row('2026-07-26', 'big', 'm', 96),
      // other 共 4：deepseek 3（75%）、zhipu 1（25%）
      row('2026-07-26', 'deepseek', 'v4-flash', 2),
      row('2026-07-26', 'deepseek', 'v4-pro', 1),
      row('2026-07-26', 'zhipu', 'glm-5.2', 1),
    ])

    const otherDetail = columns.value[0].segments.find(s => s.isOther)!.detail
    expect(otherDetail.map(d => d.label)).toEqual(['deepseek', 'zhipu'])
    expect(otherDetail[0].ratio).toBeCloseTo(0.75)
    expect(otherDetail[1].ratio).toBeCloseTo(0.25)

    // 第二层：deepseek 内部 v4-flash 2/3、v4-pro 1/3
    const children = otherDetail[0].children!
    expect(children.map(c => c.label)).toEqual(['v4-flash', 'v4-pro'])
    expect(children[0].ratio).toBeCloseTo(2 / 3)
    expect(children[1].ratio).toBeCloseTo(1 / 3)
  })
})

describe('二级：某天各主维度，柱内按次维度堆叠', () => {
  it('筛定日期后按主维度汇总并降序', () => {
    const { barsForDate } = setup([
      row('2026-07-25', 'other-day', 'm', 999),
      row('2026-07-26', 'small', 'm', 10),
      row('2026-07-26', 'big', 'm', 90),
    ])

    const bars = barsForDate('2026-07-26')
    expect(bars.map(b => b.key)).toEqual(['big', 'small'])
    expect(bars.map(b => b.total)).toEqual([90, 10])
  })

  it('二级图不做 other 合并，小成员各自成柱', () => {
    const { barsForDate } = setup([
      row('2026-07-26', 'big', 'm', 96),
      row('2026-07-26', 'tiny1', 'm', 3),
      row('2026-07-26', 'tiny2', 'm', 1),
    ])

    expect(barsForDate('2026-07-26').map(b => b.key)).toEqual(['big', 'tiny1', 'tiny2'])
  })

  it('柱内按次维度分段，段占比为占本柱的比例', () => {
    const { barsForDate } = setup([
      row('2026-07-26', 'deepseek', 'v4-flash', 30),
      row('2026-07-26', 'deepseek', 'v4-pro', 10),
    ])

    const segments = barsForDate('2026-07-26')[0].segments
    // 段身份即次维度值，与三级柱子的 key 同源，下钻时可按身份复用 DOM
    expect(segments.map(s => s.primary)).toEqual(['v4-flash', 'v4-pro'])
    expect(segments.map(s => s.label)).toEqual(['v4-flash', 'v4-pro'])
    expect(segments[0].ratio).toBeCloseTo(0.75)
    expect(segments[1].ratio).toBeCloseTo(0.25)
  })

  it('二级不做 other 合并：占比极小的次维度也各自成段', () => {
    // v4-pro 仅占 1%，低于一级的 5% 阈值，但二级仍应保留为独立段
    const { barsForDate } = setup([
      row('2026-07-26', 'deepseek', 'v4-flash', 99),
      row('2026-07-26', 'deepseek', 'v4-pro', 1),
    ])

    const segments = barsForDate('2026-07-26')[0].segments
    expect(segments.map(s => s.primary)).toEqual(['v4-flash', 'v4-pro'])
    expect(segments.every(s => s.isOther === false)).toBe(true)
  })

  it('段占比之和为 1', () => {
    const { barsForDate } = setup([
      row('2026-07-26', 'deepseek', 'v4-flash', 30),
      row('2026-07-26', 'deepseek', 'v4-pro', 10),
      row('2026-07-26', 'deepseek', 'v4-lite', 60),
    ])

    const sum = barsForDate('2026-07-26')[0].segments.reduce((acc, s) => acc + s.ratio, 0)
    expect(sum).toBeCloseTo(1)
  })

  it('日期不存在时返回空数组', () => {
    const { barsForDate } = setup([row('2026-07-26', 'p', 'm', 1)])
    expect(barsForDate('2026-01-01')).toEqual([])
  })
})

describe('三级：某天某主维度下各次维度', () => {
  it('筛定日期与主维度后按次维度汇总', () => {
    const { barsForPrimary } = setup([
      row('2026-07-26', 'deepseek', 'v4-flash', 80),
      row('2026-07-26', 'deepseek', 'v4-pro', 20),
      row('2026-07-26', 'zhipu', 'glm-5.2', 500),
    ])

    const bars = barsForPrimary('2026-07-26', 'deepseek')
    expect(bars.map(b => b.key)).toEqual(['v4-flash', 'v4-pro'])
    expect(bars[0].segments[0].ratio).toBeCloseTo(0.8)
    // 已是最细粒度，无更深明细
    expect(bars[0].segments[0].detail).toEqual([])
  })

  it('主维度不存在时返回空数组', () => {
    const { barsForPrimary } = setup([row('2026-07-26', 'p', 'm', 1)])
    expect(barsForPrimary('2026-07-26', 'nope')).toEqual([])
  })
})

describe('解耦：主次维度互换', () => {
  const rows = [
    row('2026-07-26', 'zhipu', 'glm-5.2', 60),
    row('2026-07-26', 'huoshan', 'glm-5.2', 40),
    row('2026-07-26', 'deepseek', 'v4-flash', 100),
  ]

  it('互换后一级堆叠的是模型而非供应商', () => {
    const { columns } = setup(rows, modelFirst)

    // 按模型汇总：v4-flash 100、glm-5.2 100（等值按名称升序）
    expect(columns.value[0].segments.map(s => s.primary)).toEqual(['glm-5.2', 'v4-flash'])
  })

  it('互换后 hover 明细列出的是供应商', () => {
    const { columns } = setup(rows, modelFirst)

    const glmSegment = columns.value[0].segments.find(s => s.primary === 'glm-5.2')!
    expect(glmSegment.detail.map(d => d.label)).toEqual(['zhipu', 'huoshan'])
    expect(glmSegment.detail[0].ratio).toBeCloseTo(0.6)
  })

  it('互换后三级图列出的是供应商', () => {
    const { barsForPrimary } = setup(rows, modelFirst)
    expect(barsForPrimary('2026-07-26', 'glm-5.2').map(b => b.key)).toEqual(['zhipu', 'huoshan'])
  })

  it('两种配置的总量一致（互换不改变总和）', () => {
    const providerView = setup(rows, providerFirst)
    const modelView = setup(rows, modelFirst)

    expect(providerView.columns.value[0].total).toBe(modelView.columns.value[0].total)
    expect(providerView.maxColumnTotal.value).toBe(modelView.maxColumnTotal.value)
  })
})

/**
 * 三级共用同一种柱子结构，是「一个图表组件服务三级」的前提。
 *
 * 若哪天有人给某一级换回专用结构，图表就得重新分叉成两个组件、
 * 下钻动效也会退回整体淡入淡出，故在此锁定。
 */
describe('统一模型：三级同构', () => {
  const rows = [
    row('2026-07-26', 'deepseek', 'v4-flash', 60),
    row('2026-07-26', 'deepseek', 'v4-pro', 30),
    row('2026-07-26', 'zhipu', 'glm-5.2', 10),
  ]

  /** StackBar 的必备字段，三级缺一不可。 */
  function expectStackBarShape(bar: unknown) {
    expect(bar).toMatchObject({
      key: expect.any(String),
      label: expect.any(String),
      fullLabel: expect.any(String),
      total: expect.any(Number),
      segments: expect.any(Array),
    })
  }

  it('三级返回的柱子结构一致', () => {
    const { columns, barsForDate, barsForPrimary } = setup(rows)

    expectStackBarShape(columns.value[0])
    expectStackBarShape(barsForDate('2026-07-26')[0])
    expectStackBarShape(barsForPrimary('2026-07-26', 'deepseek')[0])
  })

  it('三级的柱子只有一段，且该段占满整柱', () => {
    const { barsForPrimary } = setup(rows)

    for (const bar of barsForPrimary('2026-07-26', 'deepseek')) {
      expect(bar.segments).toHaveLength(1)
      // 三级已是最细粒度，无从再拆：段值等于柱总量
      expect(bar.segments[0].value).toBe(bar.total)
      expect(bar.segments[0].isOther).toBe(false)
    }
  })

  it('二级是真堆叠：多次维度的柱子分成多段', () => {
    const { barsForDate } = setup(rows)
    const bars = barsForDate('2026-07-26')

    // deepseek 下有两个模型，故两段；zhipu 只有一个模型，故一段
    expect(bars.find(b => b.key === 'deepseek')?.segments).toHaveLength(2)
    expect(bars.find(b => b.key === 'zhipu')?.segments).toHaveLength(1)
  })

  it('每根柱的 total 等于各段之和', () => {
    const { columns, barsForDate } = setup(rows)

    for (const bar of [...columns.value, ...barsForDate('2026-07-26')]) {
      const sum = bar.segments.reduce((acc, s) => acc + s.value, 0)
      expect(sum).toBe(bar.total)
    }
  })

  it('一级的 key 是日期、label 是 M/D 短格式', () => {
    const { columns } = setup(rows)

    // key 用于 DOM 复用（动效身份），label 仅用于横轴显示
    expect(columns.value[0].key).toBe('2026-07-26')
    expect(columns.value[0].label).toBe('7/26')
    expect(columns.value[0].fullLabel).toBe('2026-07-26')
  })

  it('同层级内 key 唯一，保证按 key 复用 DOM 不会冲突', () => {
    const { columns, barsForDate } = setup([
      ...rows,
      row('2026-07-27', 'deepseek', 'v4-flash', 5),
    ])

    for (const bars of [columns.value, barsForDate('2026-07-26')]) {
      const keys = bars.map(b => b.key)
      expect(new Set(keys).size).toBe(keys.length)
    }
  })
})
