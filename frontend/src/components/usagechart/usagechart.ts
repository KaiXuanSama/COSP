/**
 * 概览下钻柱状图的类型契约。
 *
 * <h2>解耦要点</h2>
 * 组件与数据层<strong>只认「主维度 / 次维度」两个抽象</strong>，不知道它们的业务含义。
 * 第一版主维度是供应商、次维度是模型；将来把模型作为主维度时，只需换一份
 * {@link BreakdownDimension} 配置，组件与 pivot 逻辑零改动。
 *
 * 因此本文件中一律用 primary / secondary 命名，不出现 provider / model 字样
 * （后端 DTO 的字段名由 dimension 配置负责映射）。
 */

/** 后端 `/config/api/usage-breakdown` 返回的明细行（与 protocol 层 UsageBreakdownRow 对应）。 */
export interface UsageBreakdownRow {
  date: string
  providerKey: string
  modelName: string
  callCount: number
}

/**
 * SSE `breakdown-delta` 帧 —— 一次调用产生的那一行（与 protocol 层 UsageBreakdownDelta 对应）。
 *
 * 它是 {@link UsageBreakdownRow} 的超集：前四个字段完全一致，故可直接当作一行明细
 * 累加进已有数组，不需要转换。
 *
 * `createdAt` 与两个 token 字段目前无人消费 —— 它们是给将来共用同一条流的 token 折线图
 * 预留的（折线图按完整时刻定位分桶）。token 可为 null，语义是「上游未提供」而非零。
 */
export interface UsageBreakdownDelta extends UsageBreakdownRow {
  createdAt: string
  inputTokens: number | null
  outputTokens: number | null
}

/**
 * 维度配置 —— 决定「谁是主维度、谁是次维度」。
 *
 * 主次维度互换即得另一种视图，这是整套下钻能复用同一份数据与同一套组件的关键。
 */
export interface BreakdownDimension<T = UsageBreakdownRow> {
  /** 维度组合标识，用于 UI 状态与缓存键。 */
  key: string
  /** 主维度取值（一级堆叠段、二级柱子的身份）。 */
  primaryOf: (row: T) => string
  /** 次维度取值（hover 明细、三级柱子的身份）。 */
  secondaryOf: (row: T) => string
  /** 主维度显示名，缺省时直接用 primaryOf 的结果。 */
  primaryLabel?: (value: string) => string
  /** 次维度显示名，缺省时直接用 secondaryOf 的结果。 */
  secondaryLabel?: (value: string) => string
  /** 主维度在 UI 上的称呼（如「供应商」），用于面包屑与空态文案。 */
  primaryTerm: string
  /** 次维度在 UI 上的称呼（如「模型」）。 */
  secondaryTerm: string
}

/** 指标取值方式。第一版只有调用次数，保留抽象以便后续接入 token 等指标。 */
export interface BreakdownMetric<T = UsageBreakdownRow> {
  key: string
  display: string
  unit: string
  valueOf: (row: T) => number
}

/** 一级堆叠柱中的一段。 */
export interface StackSegment {
  /** 主维度原始值；other 段为 null。 */
  primary: string | null
  /** 展示用标签。 */
  label: string
  /** 指标值。 */
  value: number
  /** 占当列总量的比例（0~1）。 */
  ratio: number
  /** 是否为「其余」合并段。 */
  isOther: boolean
  /**
   * 次维度明细，供 hover 展开。
   *
   * - 普通段 —— 次维度在该主维度内部的占比（单层）；
   * - other 段 —— 被合并的各主维度及其内部次维度（双层）。
   */
  detail: SegmentDetail[]
}

/** hover 明细条目。普通段只用到 label/ratio；other 段用 children 承载第二层。 */
export interface SegmentDetail {
  label: string
  value: number
  /** 普通段：次维度占所属主维度的比例；other 段：该主维度占 other 总量的比例。 */
  ratio: number
  /** 仅 other 段有值 —— 该主维度下的次维度明细。 */
  children?: SegmentDetail[]
}

/**
 * 图表中的一根柱子 —— 三级视图共用的唯一柱子模型。
 *
 * <h2>为什么三级共用一种结构</h2>
 * 分类柱状图本质是「只有一段的堆叠柱状图」：
 * 一级每列由多个主维度段堆叠而成，二 / 三级每列只有一段（该分类自身）。
 * 既然只是段数不同，就没必要为它们准备两套结构与两个组件 ——
 * 统一之后图表只认一种数据，样式与交互逻辑都只有一份实现。
 *
 * <h2>身份稳定性</h2>
 * {@link key} 是这根柱子在同一层级内的稳定标识（一级是日期，二 / 三级是分类值）。
 * 层级切换时按 key 复用 DOM 节点，柱子才能「平滑长高 / 缩短」而不是整批淡出重建，
 * 这是下钻动效能做成 morph 而非 fade 的前提。
 */
export interface StackBar {
  /** 同层级内唯一的稳定标识：一级为日期，二 / 三级为分类原始值。 */
  key: string
  /** 横轴标签（一级为「M/D」，二 / 三级为分类显示名）。 */
  label: string
  /** 供 aria-label 与面包屑使用的完整名称（一级为完整日期）。 */
  fullLabel: string
  /** 该柱总量，等于各段之和。 */
  total: number
  /**
   * 自下而上的段序列。
   *
   * 一级：各主维度按值降序（下方更粗），other 固定末尾（最上方）。
   * 二 / 三级：只有一段，即该分类自身。
   */
  segments: StackSegment[]
}

/** 下钻层级。 */
export type DrilldownLevel = 'overview' | 'day' | 'primary'

/** 当前下钻位置。 */
export interface DrilldownState {
  level: DrilldownLevel
  /** 二 / 三级选中的日期。 */
  date: string | null
  /** 三级选中的主维度值。 */
  primary: string | null
}
