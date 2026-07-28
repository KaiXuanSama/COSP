import { computed, type Ref } from 'vue'
import type {
  BreakdownDimension,
  BreakdownMetric,
  SegmentDetail,
  StackBar,
  StackSegment,
  UsageBreakdownRow,
} from './usagechart'

/**
 * 低于该占比的主维度合并进「其余」段。
 *
 * 合并的信息损失由下钻与 hover 双层明细补回：点进二级图，被合并的成员会各自成为
 * 一根独立柱子；不点也能在 other 的 hover 里看到构成。
 */
const OTHER_THRESHOLD = 0.05

/** 分组求和的小工具：按 keyOf 聚合 valueOf 之和，保持首次出现顺序。 */
function sumBy<T>(rows: T[], keyOf: (row: T) => string, valueOf: (row: T) => number): Map<string, number> {
  const acc = new Map<string, number>()
  for (const row of rows) {
    const key = keyOf(row)
    acc.set(key, (acc.get(key) ?? 0) + valueOf(row))
  }
  return acc
}

/** 值降序排列，等值时按名称升序以保证结果稳定（避免相同数据两次渲染顺序不同）。 */
function sortedByValueDesc(entries: Map<string, number>): Array<[string, number]> {
  return [...entries.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
}

/** 安全除法：分母为 0 时返回 0，避免 NaN 流入样式计算。 */
function ratioOf(value: number, total: number): number {
  return total > 0 ? value / total : 0
}

/** 横轴日期标签只保留「M/D」，避免 7 根柱子的标签挤在一起。 */
function shortDate(date: string): string {
  const parts = date.split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : date
}

/**
 * 概览下钻柱状图的数据层。
 *
 * 后端只返回一份最细粒度明细（日期 × 主维度 × 次维度），三级视图与 hover 明细
 * 全部在此 pivot 得出，因此下钻与悬浮都不产生额外请求。
 *
 * 所有分组都经 dimension 的 primaryOf / secondaryOf 取值，不直接读业务字段，
 * 主次维度互换即得另一种视图。
 */
export function useUsageBreakdown(
  rows: Ref<UsageBreakdownRow[]>,
  dimension: Ref<BreakdownDimension>,
  metric: Ref<BreakdownMetric>,
) {
  const primaryLabelOf = (value: string) => dimension.value.primaryLabel?.(value) ?? value
  const secondaryLabelOf = (value: string) => dimension.value.secondaryLabel?.(value) ?? value

  /** 按日期分桶，日期升序（后端已排序，这里再排一次以免依赖后端顺序）。 */
  const rowsByDate = computed(() => {
    const buckets = new Map<string, UsageBreakdownRow[]>()
    for (const row of rows.value) {
      const bucket = buckets.get(row.date)
      if (bucket) bucket.push(row)
      else buckets.set(row.date, [row])
    }
    return new Map([...buckets.entries()].sort((a, b) => a[0].localeCompare(b[0])))
  })

  /** 次维度在某主维度内部的占比明细（单层，供普通段 hover）。 */
  function buildSecondaryDetail(scoped: UsageBreakdownRow[], scopeTotal: number): SegmentDetail[] {
    const bySecondary = sumBy(scoped, dimension.value.secondaryOf, metric.value.valueOf)
    return sortedByValueDesc(bySecondary).map(([key, value]) => ({
      label: secondaryLabelOf(key),
      value,
      ratio: ratioOf(value, scopeTotal),
    }))
  }

  /**
   * other 段的双层明细：被合并的每个主维度占 other 总量的比例，
   * 其下再列该主维度内部的次维度占比。
   */
  function buildOtherDetail(
    merged: string[],
    columnRows: UsageBreakdownRow[],
    otherTotal: number,
  ): SegmentDetail[] {
    const mergedSet = new Set(merged)
    const scoped = columnRows.filter(row => mergedSet.has(dimension.value.primaryOf(row)))
    const byPrimary = sumBy(scoped, dimension.value.primaryOf, metric.value.valueOf)

    return sortedByValueDesc(byPrimary).map(([primary, value]) => {
      const own = scoped.filter(row => dimension.value.primaryOf(row) === primary)
      return {
        label: primaryLabelOf(primary),
        value,
        ratio: ratioOf(value, otherTotal),
        children: buildSecondaryDetail(own, value),
      }
    })
  }

  /**
   * 一级图：按天堆叠。
   *
   * 每列独立按值降序排列（下方更粗），占比低于阈值的主维度合并为 other 并固定在末尾。
   */
  const columns = computed<StackBar[]>(() =>
    [...rowsByDate.value.entries()].map(([date, columnRows]) => {
      const byPrimary = sumBy(columnRows, dimension.value.primaryOf, metric.value.valueOf)
      const total = [...byPrimary.values()].reduce((sum, value) => sum + value, 0)
      const ranked = sortedByValueDesc(byPrimary)

      const kept = ranked.filter(([, value]) => ratioOf(value, total) >= OTHER_THRESHOLD)
      const merged = ranked.filter(([, value]) => ratioOf(value, total) < OTHER_THRESHOLD)

      const segments: StackSegment[] = kept.map(([primary, value]) => {
        const own = columnRows.filter(row => dimension.value.primaryOf(row) === primary)
        return {
          primary,
          label: primaryLabelOf(primary),
          value,
          ratio: ratioOf(value, total),
          isOther: false,
          detail: buildSecondaryDetail(own, value),
        }
      })

      if (merged.length > 0) {
        const otherTotal = merged.reduce((sum, [, value]) => sum + value, 0)
        segments.push({
          primary: null,
          label: '其余',
          value: otherTotal,
          ratio: ratioOf(otherTotal, total),
          isOther: true,
          detail: buildOtherDetail(merged.map(([key]) => key), columnRows, otherTotal),
        })
      }

      return { key: date, label: shortDate(date), fullLabel: date, total, segments }
    }),
  )

  /** 所有列的最大总量，供柱高归一化。 */
  const maxColumnTotal = computed(() =>
    columns.value.reduce((max, column) => Math.max(max, column.total), 0),
  )

  /**
   * 把「一个分类」包装成单段柱子 —— 仅三级使用。
   *
   * 三级已是最细粒度（次维度自身），无从再往下拆，故柱内只有一段、段占满整柱。
   * 一级与二级都是真堆叠：一级按主维度分段，二级按次维度分段。
   *
   * 段的 ratio 在这里取「占本视图总量」而非段内占比 —— 三级段既然等于整柱，
   * 段内占比恒为 1 便毫无信息量，tooltip 显示它在本视图中的分量才有意义。
   */
  function toSingleSegmentBar(
    key: string,
    label: string,
    value: number,
    viewRatio: number,
    detail: SegmentDetail[],
  ): StackBar {
    return {
      key,
      label,
      fullLabel: label,
      total: value,
      segments: [{ primary: key, label, value, ratio: viewRatio, isOther: false, detail }],
    }
  }

  /**
   * 二级图：某天各主维度一柱，柱内按<strong>次维度堆叠</strong>。
   *
   * 一级的段是主维度，而二级的横轴正是主维度，因此二级柱内自然该由「该主维度下的
   * 各次维度」堆叠而成 —— 层级递进因此是「同一份构成被逐层展开」，
   * 而不是换一种图形语言重新表达。
   *
   * 这里不做 other 合并：二级已经限定到单日单主维度，成员数量有限、颗粒度不算细，
   * 合并只会掩盖下钻本就想让人看清的小成员。三级要用的次维度身份也因此保持完整，
   * 段与三级柱子共用同一 key，下钻时可继续按身份复用 DOM。
   */
  function barsForDate(date: string): StackBar[] {
    const scoped = rowsByDate.value.get(date) ?? []
    const byPrimary = sumBy(scoped, dimension.value.primaryOf, metric.value.valueOf)

    return sortedByValueDesc(byPrimary).map(([primary, primaryTotal]) => {
      const own = scoped.filter(row => dimension.value.primaryOf(row) === primary)
      const bySecondary = sumBy(own, dimension.value.secondaryOf, metric.value.valueOf)

      // 段的 ratio 取「占本柱总量」，与一级段语义一致（段占所属柱的比例）
      const segments: StackSegment[] = sortedByValueDesc(bySecondary).map(([secondary, value]) => ({
        primary: secondary,
        label: secondaryLabelOf(secondary),
        value,
        ratio: ratioOf(value, primaryTotal),
        isOther: false,
        detail: [],
      }))

      return {
        key: primary,
        label: primaryLabelOf(primary),
        fullLabel: primaryLabelOf(primary),
        total: primaryTotal,
        segments,
      }
    })
  }

  /** 三级图：某天某主维度下各次维度平铺，已是最细粒度故无下级明细。 */
  function barsForPrimary(date: string, primary: string): StackBar[] {
    const scoped = (rowsByDate.value.get(date) ?? []).filter(
      row => dimension.value.primaryOf(row) === primary,
    )
    const bySecondary = sumBy(scoped, dimension.value.secondaryOf, metric.value.valueOf)
    const total = [...bySecondary.values()].reduce((sum, value) => sum + value, 0)

    return sortedByValueDesc(bySecondary).map(([secondary, value]) =>
      toSingleSegmentBar(
        secondary,
        secondaryLabelOf(secondary),
        value,
        ratioOf(value, total),
        [],
      ),
    )
  }

  return {
    columns,
    maxColumnTotal,
    barsForDate,
    barsForPrimary,
    otherThreshold: OTHER_THRESHOLD,
  }
}
