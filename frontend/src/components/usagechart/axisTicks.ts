/**
 * 纵轴刻度计算。
 *
 * 两级图表（堆叠图 / 分类图）的归一化基准不同 —— 堆叠图按全局最大列总量、
 * 分类图按本视图最大值 —— 但刻度的「取整规则」应当一致，故抽到这里共用。
 */

/**
 * 柱列的纵向内边距（px）—— hover 高亮的呼吸空间。
 *
 * 三处必须共享同一个值：柱列的 padding-top、纵轴的 margin-top、网格线层的 top。
 * 列的 padding-top 会把柱体整体下推，若轴与网格线不跟着下移同样距离，
 * 柱底就会比 0 刻度线低出这段内边距（视觉上柱子"悬空"在基线之下）。
 */
export const COLUMN_PAD_Y = 4

/** 单个刻度：值 + 它在绘图区内自下而上的高度占比。 */
export interface AxisTick {
  /** 刻度对应的数值。 */
  value: number
  /** 距绘图区底部的比例（0 ~ 1），供 CSS 定位。 */
  ratio: number
}

/**
 * 把原始最大值向上取整到"好看的刻度上限"。
 *
 * 规则是图表库的通用做法：取同数量级下的易读倍数，使刻度落在 40、80、250
 * 这类整数上，而不是 37、73 这种读数困难的值。
 *
 * 档位比常见的 1/2/5 更密，因为稀疏档位会把 297 抬到 500，柱子只占六成高、
 * 版面显得空旷。加密后上限最多超出真实最大值 50%。
 *
 * @param rawMax 数据的真实最大值
 * @returns 取整后的轴上限；rawMax <= 0 时返回 0
 */
export function niceCeiling(rawMax: number): number {
  if (rawMax <= 0) return 0
  // 小量程直接返回原值，避免把 3 拉到 10 让柱子显得过矮
  if (rawMax <= 5) return Math.ceil(rawMax)

  const magnitude = 10 ** Math.floor(Math.log10(rawMax))
  const normalized = rawMax / magnitude

  // 档位取得较密（步长 0.5），避免出现「297 被抬到 500、柱子只占六成高」这类空旷版面。
  // 仍全部是 1/1.5/2… 这类易读的倍数，不会产生 3.7 这种刻度。
  const step = [1, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]
    .find((candidate) => normalized <= candidate) ?? 10
  return step * magnitude
}

/**
 * 生成纵轴刻度序列（含 0 与上限）。
 *
 * 刻度数会向下微调，保证每档都是整数：调用次数没有"2.5 次"，
 * 若均分产生小数则减少档数，宁可少几条参考线也不显示无意义的小数。
 *
 * @param rawMax 数据真实最大值
 * @param desiredCount 期望的刻度档数（不含 0），默认 4
 * @returns 自下而上排列的刻度；无数据时返回空数组
 */
export function buildAxisTicks(rawMax: number, desiredCount = 4): AxisTick[] {
  const ceiling = niceCeiling(rawMax)
  if (ceiling <= 0) return []

  // 找一个能让每档都是整数的档数，从期望值往下退
  let count = desiredCount
  while (count > 1 && !Number.isInteger(ceiling / count)) {
    count -= 1
  }

  const ticks: AxisTick[] = []
  for (let i = 0; i <= count; i += 1) {
    ticks.push({ value: (ceiling / count) * i, ratio: i / count })
  }
  return ticks
}

/** 刻度数字的紧凑显示：上千折算为 k，避免长数字挤压绘图区。 */
export function formatTickValue(value: number): string {
  if (value >= 10000) return `${Math.round(value / 1000)}k`
  if (value >= 1000) {
    const inK = value / 1000
    return `${Number.isInteger(inK) ? inK : inK.toFixed(1)}k`
  }
  return String(value)
}
