/**
 * 线路协议标识。
 *
 * 与后端 `application/protocol/WireProtocol` 枚举同名同值，序列化后直接互通，
 * 因此这里的字面量必须与枚举常量名逐字一致（全大写），不要改成 kebab 或小写。
 *
 * 三个名字都取自各自的 **API 路径全称**，同一维度、不混入厂商名：
 *
 * | 标识 | 含义 | 端点 | 缩写 |
 * |---|---|---|---|
 * | `CHAT` | Chat Completions API | `/v1/chat/completions` | `C` |
 * | `MESSAGES` | Anthropic API | `/v1/messages` | `M` |
 * | `RESPONSES` | Responses API | `/v1/responses` | `R` |
 *
 * `CHAT` 与 `RESPONSES` 都是 OpenAI 推出的，但**是两个不同的接口**，报文形态与事件模型
 * 都不通用。这正是当初把 `OPENAI` 改名为 `CHAT` 的原因 —— 旧名同时指代厂商与接口，
 * Responses 一加入就自相矛盾。
 *
 * 展示名（{@link WIRE_PROTOCOL_LABELS}）与标识刻意不同：标识指「接口」，
 * 展示名用官方叫法。`MESSAGES` 若叫 `ANTHROPIC` 会让人以为它与 `CHAT` 不是同一类
 * 东西 —— 前者是厂商名、后者是接口名。
 *
 * 该类型有三类消费者：调用日志的两侧协议列、流式 chunk 规整（按上游协议选解析分支）、
 * 请求体规则组的适用线路。原先各处各写一遍联合类型，
 * 加入第三类消费者时收敛到此处 —— 将来新增协议只需改一个地方。
 */
export type WireProtocol = 'CHAT' | 'MESSAGES' | 'RESPONSES'

/**
 * 全部线路协议，供多选控件与「未指定即全选」的兜底使用。
 *
 * <h2>顺序跟随后端的翻译回退优先级，而非语义分组</h2>
 * 取 `CHAT, MESSAGES, RESPONSES`，与后端 `ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER`
 * 逐项一致。**这个顺序被界面当作优先级来读**：协议地址行从上到下排列，用户自然会推断
 * 越靠上的越优先被选中。
 *
 * <p>曾经取语义序（`CHAT, RESPONSES, MESSAGES`，两个 OpenAI 接口相邻），那样更好读，
 * 但会**误导优先级**：界面上 Responses 在第二行，而实际当上游同时支持 Messages 与
 * Responses、下游打 Chat 时，回退序选的是 Messages（C2M 已实现，C2R 未实现）。
 * 「读起来舒服」不值得用「对系统行为的错误预期」去换 —— 尤其这个预期会让人以为
 * 请求会走一条尚未实现的翻译路径。
 *
 * <p>因此这里与后端回退序**必须同步改动**。仍与后端落库的字母序无关（那个服务于
 * 直接查库对比，当前数值上恰好也是 `CHAT, MESSAGES, RESPONSES`，纯属巧合）。
 *
 * <p>更根本地说，「用一个全局固定顺序表达优先级」本身是过渡方案 ——
 * 真正的需求是让用户按供应商、按模型编排协议流转。见下方 TODO。
 */
// TODO(待实现) 协议流转编排：让用户自行指定「下游协议 → 上游协议」的映射，
//  粒度到供应商与模型。当前用一个全局固定顺序（本常量 + 后端 TRANSLATION_FALLBACK_ORDER）
//  表达优先级，无法覆盖真实场景 —— 例如某供应商不支持 Chat、只支持 Messages 与 Responses，
//  而它名下模型 1 只支持 Responses、模型 2 只支持 Messages：全局顺序对这两个模型
//  只能给出同一个答案，其中必有一个是错的。
//  编排能力将在 C2R 翻译落地之前规划与实现；在那之前直连场景不受影响
//  （规则 1「同名协议优先直连」不看这个顺序）。
export const ALL_WIRE_PROTOCOLS: readonly WireProtocol[] = ['CHAT', 'MESSAGES', 'RESPONSES']

/** 线路协议的展示名，取各自的官方叫法。 */
export const WIRE_PROTOCOL_LABELS: Record<WireProtocol, string> = {
  CHAT: 'Chat Completions API',
  MESSAGES: 'Anthropic API',
  RESPONSES: 'Responses API',
}

/**
 * 线路协议的一句话说明，用于标题的原生悬停气泡。
 *
 * 标题本身只写「Chat 请求Url」这类短名（列宽有限），而短名无法回答
 * 「这三个到底什么关系」—— 尤其 `CHAT` 与 `RESPONSES` 同属 OpenAI，
 * 光看名字容易以为是新旧版本的同一个东西。气泡负责补上这层信息。
 */
export const WIRE_PROTOCOL_DESCRIPTIONS: Record<WireProtocol, string> = {
  CHAT: 'OpenAI 的旧 Chat Completions 协议（/v1/chat/completions），兼容面最广',
  MESSAGES: 'Anthropic 的 Messages 协议（/v1/messages），Claude 系客户端使用',
  RESPONSES: 'OpenAI 的新 Responses 协议（/v1/responses），与 Chat 是两个不同的接口',
}

/**
 * 协议地址输入行的短标题。
 *
 * 取**接口名**而非厂商名：曾经写作「OpenAI 请求Url」「Anthropic 请求Url」，
 * 两个维度混在一起 —— 前者是厂商、后者也是厂商，而 Responses 同样属于 OpenAI，
 * 沿用厂商名就会出现两行都叫「OpenAI」。改成接口名后三行同维度、可并列阅读。
 */
export const WIRE_PROTOCOL_URL_LABELS: Record<WireProtocol, string> = {
  CHAT: 'Chat 请求Url',
  MESSAGES: 'Messages 请求Url',
  RESPONSES: 'Responses 请求Url',
}

/**
 * **请求体规则引擎**支持的协议，与后端 `ProviderRequestTransformService.PROTOCOLS`
 * 逐项对应。
 *
 * <h2>为何保留这个常量，即使它当前等于 {@link ALL_WIRE_PROTOCOLS}</h2>
 * 两者语义不同：{@link ALL_WIRE_PROTOCOLS} 是「系统认识哪些协议」（地址配置、协议勾选、
 * 日志展示），本常量是「规则引擎能对哪些协议生效」，而后者受**后端白名单**约束。
 *
 * <p>它曾短暂地是真子集：Responses 加入系统后、后端白名单放开之前，
 * 规则组若铺上全集会在保存时被拒（400「不支持的线路协议」）—— 用户什么都没配错。
 * 那段时间证明了这两个概念确实会分叉，因此即使现在数值相同也不合并。
 *
 * <p>两处必须同一批改：只改前端会让用户勾了之后保存报错，只改后端则界面上勾不到、
 * 规则永远不会对那条线路生效。
 */
export const RULE_ENGINE_WIRE_PROTOCOLS: readonly WireProtocol[] =
  ['CHAT', 'MESSAGES', 'RESPONSES']

/**
 * 线路协议的单字母缩写，用于空间紧张处（调用 Toast 的路径标记、日志列表的类型列）。
 *
 * 收敛在此而非各处自写三元表达式：本文件已是协议标识的唯一真源，缩写是它的展示形态
 * 之一，散落各处会在新增协议时漏改 —— 这次重命名就在三个地方各改了一遍同样的映射。
 */
export const WIRE_PROTOCOL_ABBREVIATIONS: Record<WireProtocol, string> = {
  CHAT: 'C',
  MESSAGES: 'M',
  RESPONSES: 'R',
}

/** 判断任意值是否是合法的线路协议标识。 */
export function isWireProtocol(value: unknown): value is WireProtocol {
  return value === 'CHAT' || value === 'MESSAGES' || value === 'RESPONSES'
}

/**
 * 协议名 → 单字母缩写。
 *
 * 未知协议取首字母、空值返回空串 —— 后端加新协议时，前端在同步改动之前也能显示出
 * 一个可读的标记，而不是空白或崩溃。
 *
 * <p>这个兜底已经兜过两次真实的存量数据，都是**巧合而非设计**：
 * - 协议重命名（V12）期间库里还是 `OPENAI` / `ANTHROPIC`，查表落空走首字母得到 `O` / `A`，
 *   恰好与重命名前的显示一致；
 * - Responses 落库（V13）后、前端加 `RESPONSES` 之前，走首字母得到 `R`，
 *   与现在查表的结果相同。
 *
 * 记下来是为了防止有人把它当成「兼容层」而依赖它 —— 它只是让未同步期间的界面可读。
 */
export function protocolAbbreviation(protocol: string): string {
  if (!protocol) return ''
  const known = WIRE_PROTOCOL_ABBREVIATIONS as Record<string, string>
  return known[protocol.toUpperCase()] ?? protocol.charAt(0).toUpperCase()
}

/**
 * 协议名 → 展示名。
 *
 * 未知标识原样返回而非留空：后端加了新协议、前端尚未同步时，显示 `RESPONSES`
 * 仍比显示空白有用。
 */
export function protocolDisplayName(protocol: string): string {
  if (!protocol) return ''
  const labels = WIRE_PROTOCOL_LABELS as Record<string, string>
  return labels[protocol.toUpperCase()] ?? protocol
}

/**
 * 一次调用的「类型」标签：`流式: Chat Completions API` / `非流: M→C`。
 *
 * <h2>同协议给全名、跨协议给缩写</h2>
 * 同协议时那一列只需回答「说的哪种话」，展示名最好读；跨协议时要回答的是「从哪翻到哪」，
 * 全名拼起来会长到撑破列宽（`Anthropic API→Chat Completions API`），缩写箭头反而更清楚。
 *
 * <h2>箭头方向是「响应翻译」，与调用 Toast 刻意相反</h2>
 * 日志的箭头是 <strong>上游 → 下游</strong>（响应翻译方向），Toast 的是
 * <strong>下游 → 上游</strong>（请求翻译方向）。两处不统一是<strong>刻意的</strong>，
 * 不是笔误：日志面向<strong>执行者</strong>（COSP 自己就是那个翻译者，它关心的是
 * 「上游给了什么、我译成什么交给下游」），Toast 面向<strong>调用者</strong>
 * （关心「我的请求被译成什么发出去了」）。同一次跨协议调用因此在两处显示相反的箭头，
 * 这是两个视角的正确答案，不要为了「一致」把任一侧掉个头。
 *
 * <p>正因为容易读反，两处都在原生 title 气泡里写明方向：日志用
 * {@link formatCallTypeTitle}，Toast 用它自己的 `protocolTitle`。改箭头方向时必须同步
 * 改对应的气泡文案，否则标记与说明会互相矛盾。
 *
 * <h2>参数顺序即显示顺序</h2>
 * 形参按 `源 → 目标` 排列，与产出的箭头同序。收敛成共享函数的第一版把形参写成
 * `(upstream, downstream)` 却在两个调用点传成了 `(downstream, upstream)`，于是同一条
 * 记录在消费者视图显示 `M→C`、在调用者视图显示 `C→M`。类型相同（都是 `WireProtocol`）
 * 让编译器无从发现，因此这里靠命名把顺序说清，并由 `protocol.spec.ts` 钉住。
 *
 * @param sourceProtocol 响应的来源协议，即<strong>上游</strong>线路协议
 * @param targetProtocol 响应译成的协议，即<strong>下游</strong>线路协议
 * @param isStream 是否流式（数字 0/1 或布尔皆可，方便直接传后端字段）
 */
export function formatCallTypeLabel(
  sourceProtocol: string,
  targetProtocol: string,
  isStream: number | boolean,
): string {
  const source = protocolAbbreviation(sourceProtocol)
  const target = protocolAbbreviation(targetProtocol)
  const protocol = source === target
    ? protocolDisplayName(sourceProtocol)
    : `${source}→${target}`
  return `${isStream ? '流式' : '非流'}: ${protocol}`
}

/**
 * 调用类型标记的悬停说明，与 {@link formatCallTypeLabel} 配对。
 *
 * <p>存在的唯一理由是消除方向歧义：缩写箭头 `M→C` 无法自解释，而它与 Toast 上的
 * `C→M` 方向相反（见 {@link formatCallTypeLabel} 的说明）。气泡里写明「响应翻译」
 * 加两侧全名，读者不必记住哪个视图用哪个方向。
 *
 * <p>直连时不写箭头也不提翻译：那次调用根本没有翻译发生，写成「响应翻译 X → X」
 * 会凭空暗示有一层转换。
 *
 * @param sourceProtocol 响应的来源协议，即上游线路协议
 * @param targetProtocol 响应译成的协议，即下游线路协议
 */
export function formatCallTypeTitle(sourceProtocol: string, targetProtocol: string): string {
  const source = protocolDisplayName(sourceProtocol)
  const target = protocolDisplayName(targetProtocol)
  if (!source) return ''
  if (!target || source === target) {
    return `上游与下游同为 ${source}，直连无需翻译`
  }
  return `响应翻译 ${source} → ${target}（上游 → 下游）`
}
