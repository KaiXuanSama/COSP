/**
 * 缓存命中占比的计算。
 *
 * 抽成纯函数是因为列表页（`views/UsageLog.vue`）与详情组件
 * （`components/calllog/CallLogDetail.vue`）都要展示这个派生指标，两处各写一遍
 * 迟早漂移。
 *
 * <h2>只有一套口径</h2>
 * `api_call_usage` 的三个 token 列是跨协议共用的度量列，口径由**后端**固定：
 *
 * ```text
 * prompt_tokens = 本次调用的总输入 token，包含缓存命中部分
 * cached_tokens = 其中来自缓存命中的部分
 * ```
 *
 * 于是占比就是 `cached / prompt`，与该行走的是哪条线路无关。
 *
 * Anthropic 把缓存读取排在 `input_tokens` 之外单独计量，那个差异在
 * `AnthropicUsageParser.toTokens` 里就被抹平了（把 `cache_read_input_tokens`
 * 加回输入）—— 那个换算只依赖**上游**协议，因此属于解析层而非展示层。
 *
 * <h2>为何这里曾经按协议分两支</h2>
 * 早期后端只在 A2O 路线上换算口径（`DownstreamLogView.usageRewriter`），
 * Anthropic 直连仍落不含缓存的 `input_tokens`。一列承载两种定义，前端只能靠
 * `downstream_protocol` 判断该行属于哪一种，于是有了两支分母。
 *
 * 那个设计有个前端无法弥补的漏洞：**汇总查询做不到按行区分协议**。概览页那些
 * `SUM(prompt_tokens)` 一求和就把两种定义混在一起了，前端纠偏只能救日志列表这一处，
 * 反而让两个页面对同一批数据给出不一致的解读。
 *
 * 所以口径统一到了后端，本模块的协议分支随之删除。不要把它加回来 ——
 * 在展示层纠偏会把「口径由行决定」这个已被推翻的假设重新固化。
 *
 * 另有一处**固有**精度损失与线路无关，见 {@link cacheHitRate} 的分母说明。
 */

/** 计算所需的最小字段集。不再需要协议标识。 */
export interface CacheHitRateInput {
  prompt_tokens: number | null
  cached_tokens: number | null
}

/**
 * 缓存命中占比，`null` 表示无从计算。
 *
 * <h2>null 与 0 的区分必须保留</h2>
 * 任一 token 为 `null`（上游未提供该字段）→ 返回 `null`，展示为「—」，表示无从计算
 * 而非未命中。`cached_tokens` 为 0 且输入有值 → 返回 0，那是上游报告的真实未命中，
 * 应显示 `0.0%`。把两者抹平会毁掉这个指标的可解读性 —— 「上游不支持缓存统计」与
 * 「这次确实没命中」是完全不同的结论。
 *
 * <h2>分母</h2>
 * 直接取 `prompt_tokens` —— 后端已保证它是含缓存的总输入。
 *
 * 此处有一处已知的精度损失：Anthropic 的 `cache_creation_input_tokens`
 * （本次写入缓存的量，同样在 `input_tokens` 之外）没有落库，因此没被算进
 * `prompt_tokens`，命中率会在发生缓存写入的请求上略微偏高。这是可接受的 ——
 * 该字段属于成本项而非命中项，本就被刻意排除在分子之外，把它纳入分母需要新增存储。
 *
 * @param usage 该行的 token 用量；`null` 表示没有用量行
 * @return 占比（0 表示 0%，1 表示 100%）；无从计算时 `null`
 */
export function cacheHitRate(usage: CacheHitRateInput | null): number | null {
  if (!usage) return null

  const cached = usage.cached_tokens
  const prompt = usage.prompt_tokens
  if (cached == null || prompt == null) return null
  // 分母为 0 无法做除法。归一化后 prompt 已含缓存，因此它为 0 意味着两者都是 0。
  if (prompt === 0) return null

  return cached / prompt
}

/**
 * 缓存命中占比的展示串，无从计算时返回「—」。
 *
 * 保留一位小数：命中率常落在 90% 以上的窄区间，整数百分比会把「98%」与「99%」
 * 之间的差异抹掉，而那恰好是判断缓存是否稳定生效的关键区分。
 */
export function formatCacheHitRate(usage: CacheHitRateInput | null): string {
  const rate = cacheHitRate(usage)
  if (rate == null) return '—'
  return `${(rate * 100).toFixed(1)}%`
}
