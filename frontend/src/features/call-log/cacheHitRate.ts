/**
 * 缓存命中占比的计算。
 *
 * 抽成纯函数是因为列表页（`views/UsageLog.vue`）与详情组件
 * （`components/calllog/CallLogDetail.vue`）都要展示这个派生指标，而它需要按
 * **下游协议**分支 —— 两处各写一遍必然在新增协议时漂移。
 *
 * <h2>为何必须按协议分支：分母的定义不同</h2>
 * `api_call_usage` 的 `prompt_tokens` 一列承载着两种口径，取决于该行的下游协议：
 *
 * | 下游协议 | prompt_tokens 语义 | 缓存是否已包含在内 |
 * |---|---|---|
 * | `OPENAI` | OpenAI 的 `prompt_tokens` | **是** |
 * | `ANTHROPIC` | Anthropic 的 `input_tokens` | **否** |
 *
 * Anthropic 把「缓存读取」排在 `input_tokens` 之外单独计量，OpenAI 则把它算进
 * `prompt_tokens` 再用 `prompt_tokens_details.cached_tokens` 标出其中多少来自缓存。
 * 因此 `cached / prompt_tokens` 只在 OpenAI 口径下是一个比率；套到 Anthropic 上，
 * 分母比分子小一到两个数量级，结果会冲到几千甚至几万个百分点。
 *
 * <h2>为何分支键是下游而非上游</h2>
 * 展示应当反映<strong>下游客户端真正收到的数字</strong>。跨协议翻译时，上游返回的
 * usage 会被翻译层换算成下游协议的形状再出站，界面上给出的若是上游原始口径，
 * 就与用户在客户端里看到的对不上。
 *
 * <p>四条线路因此归为两组，与协议数而非线路数同增：
 *
 * | 线路 | 上游 → 下游 | 走哪一支 | 后端落库口径 | 现状 |
 * |---|---|---|---|---|
 * | OpenAI 直连 | O → O | OpenAI | OpenAI（`OpenAiUsageParser`） | 一致 |
 * | Anthropic 直连 | A → A | Anthropic | Anthropic（`AnthropicUsageParser`） | 一致 |
 * | A2O | A → O | OpenAI | **Anthropic** | **失配** |
 * | O2A（尚未实现） | O → A | Anthropic | **OpenAI** | **将失配** |
 *
 * <h2>已知失配：两条跨协议线路</h2>
 * 后端记账发生在<strong>上游侧</strong>（各上游服务直接调用自己协议的 usage 解析器落库），
 * 而本层按下游解读 —— 同协议直连两者本就一致，跨协议线路则对不上：
 *
 * <ul>
 *   <li><strong>A2O</strong>：落库的是 Anthropic 的 `input_tokens`（不含缓存），
 *       本层按 OpenAI 支直接相除，分母偏小，比率会远超 100%。
 *       <em>翻译层 `AnthropicUsageAccumulator` 已经算出了正确的 OpenAI 形态 usage
 *       （那份才是下游真正收到的），只是尚未接进记账链路。</em></li>
 *   <li><strong>O2A</strong>：落库的将是 OpenAI 的 `prompt_tokens`（已含缓存），
 *       本层按 Anthropic 支再加一次 `cached`，缓存会被计入两遍，比率偏低。</li>
 * </ul>
 *
 * <p>两者的解法是同一个，且在后端而非本层：<strong>让每条线路落库「下游侧」的 usage</strong>，
 * 而不是上游原样。在本层加线路级的特例分支会把「口径由上游决定」这个临时状态固化成约定，
 * 并让每条新增线路都要再来一次 —— 而按协议分支只有两支，与协议数同增。
 *
 * <p>后端补齐之前，跨协议行的比率仍会偏离，这是刻意的：错在明处比在前端悄悄纠偏更容易被发现。
 */
import type { WireProtocol } from '@/types/protocol'

/** 计算所需的最小字段集：两个 token 数与该行的下游协议。 */
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
 * <h2>分母的还原</h2>
 * 下游 Anthropic 时分母取 `prompt_tokens + cached_tokens`，把被排除在
 * `input_tokens` 之外的缓存读取量加回来，使分母重新代表「总输入」。
 *
 * <p>此处有一处已知的精度损失：Anthropic 的 `cache_creation_input_tokens`
 * （本次写入缓存的量，同样在 `input_tokens` 之外）没有落库，因此进不了分母，
 * 命中率会在发生缓存写入的请求上略微偏高。这是可接受的 —— 该字段属于成本项
 * 而非命中项，本就被刻意排除在分子之外，把它纳入分母才需要新增存储。
 *
 * @param usage 该行的 token 用量；`null` 表示没有用量行
 * @param downstreamProtocol 该行的下游协议，决定 `prompt_tokens` 的口径
 * @return 占比（0 表示 0%，1 表示 100%）；无从计算时 `null`
 */
export function cacheHitRate(
  usage: CacheHitRateInput | null,
  downstreamProtocol: WireProtocol,
): number | null {
  if (!usage) return null

  const cached = usage.cached_tokens
  const prompt = usage.prompt_tokens
  if (cached == null || prompt == null) return null

  const totalInput = downstreamProtocol === 'ANTHROPIC' ? prompt + cached : prompt
  // 分母为 0 无法做除法。注意这不等价于 prompt === 0：Anthropic 侧
  // prompt 为 0 但 cached 有值时分母仍然有效（全部输入都来自缓存命中）。
  if (totalInput === 0) return null

  return cached / totalInput
}

/**
 * 缓存命中占比的展示串，无从计算时返回「—」。
 *
 * 保留一位小数：命中率常落在 90% 以上的窄区间，整数百分比会把「98%」与「99%」
 * 之间的差异抹掉，而那恰好是判断缓存是否稳定生效的关键区分。
 */
export function formatCacheHitRate(
  usage: CacheHitRateInput | null,
  downstreamProtocol: WireProtocol,
): string {
  const rate = cacheHitRate(usage, downstreamProtocol)
  if (rate == null) return '—'
  return `${(rate * 100).toFixed(1)}%`
}
