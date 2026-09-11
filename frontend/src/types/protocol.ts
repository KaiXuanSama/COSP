/**
 * 线路协议标识。
 *
 * 与后端 `application/protocol/WireProtocol` 枚举同名同值，序列化后直接互通，
 * 因此这里的字面量必须与枚举常量名逐字一致（全大写），不要改成 kebab 或小写。
 *
 * 两个名字都取自各自的 **API 路径全称**，同一维度、不混入厂商名：
 *
 * | 标识 | 含义 | 端点 | 缩写 |
 * |---|---|---|---|
 * | `CHAT` | Chat Completions API | `/v1/chat/completions` | `C` |
 * | `MESSAGES` | Anthropic API | `/v1/messages` | `M` |
 *
 * 展示名（{@link WIRE_PROTOCOL_LABELS}）与标识刻意不同：标识指「接口」，
 * 展示名用官方叫法。`MESSAGES` 若叫 `ANTHROPIC` 会让人以为它与 `CHAT` 不是同一类
 * 东西 —— 前者是厂商名、后者是接口名。原先叫 `OPENAI` 时这个歧义是隐性的
 * （OpenAI 只有一个接口在用），Responses API 一旦加入就会自相矛盾。
 *
 * 该类型有三类消费者：调用日志的两侧协议列、流式 chunk 规整（按上游协议选解析分支）、
 * 请求体规则组的适用线路。原先各处各写一遍联合类型，
 * 加入第三类消费者时收敛到此处 —— 将来新增协议只需改一个地方。
 */
export type WireProtocol = 'CHAT' | 'MESSAGES'

/** 全部线路协议，供多选控件与「未指定即全选」的兜底使用。 */
export const ALL_WIRE_PROTOCOLS: readonly WireProtocol[] = ['CHAT', 'MESSAGES']

/** 线路协议的展示名，取各自的官方叫法。 */
export const WIRE_PROTOCOL_LABELS: Record<WireProtocol, string> = {
  CHAT: 'Chat Completions API',
  MESSAGES: 'Anthropic API',
}

/**
 * 线路协议的单字母缩写，用于空间紧张处（调用 Toast 的路径标记、日志列表的类型列）。
 *
 * 收敛在此而非各处自写三元表达式：本文件已是协议标识的唯一真源，缩写是它的展示形态
 * 之一，散落各处会在新增协议时漏改 —— 这次重命名就在三个地方各改了一遍同样的映射。
 */
export const WIRE_PROTOCOL_ABBREVIATIONS: Record<WireProtocol, string> = {
  CHAT: 'C',
  MESSAGES: 'M',
}

/** 判断任意值是否是合法的线路协议标识。 */
export function isWireProtocol(value: unknown): value is WireProtocol {
  return value === 'CHAT' || value === 'MESSAGES'
}

/**
 * 协议名 → 单字母缩写。
 *
 * 未知协议取首字母、空值返回空串 —— 后端加第三种协议时，前端在同步改动之前也能显示出
 * 一个可读的标记，而不是空白或崩溃。
 *
 * <p>这个兜底在协议重命名时顺带兜过一次存量数据：库里还是 `OPENAI` / `ANTHROPIC`
 * 的行查表落空，走首字母得到 `O` / `A`，恰好与重命名前的显示一致。那是巧合而非设计
 * —— 迁移跑完（服务重启）后库里变成 `CHAT` / `MESSAGES`，才走查表得到 `C` / `M`。
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
