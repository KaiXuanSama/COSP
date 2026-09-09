/**
 * 线路协议标识。
 *
 * 与后端 `application/protocol/WireProtocol` 枚举同名同值，序列化后直接互通，
 * 因此这里的字面量必须与枚举常量名逐字一致（全大写），不要改成 kebab 或小写。
 *
 * 该类型有三类消费者：调用日志的两侧协议列、流式 chunk 规整（按上游协议选解析分支）、
 * 请求体规则组的适用线路。原先各处各写一遍 `'OPENAI' | 'ANTHROPIC'`，
 * 加入第三类消费者时收敛到此处 —— 将来新增协议只需改一个地方。
 */
export type WireProtocol = 'OPENAI' | 'ANTHROPIC'

/** 全部线路协议，供多选控件与「未指定即全选」的兜底使用。 */
export const ALL_WIRE_PROTOCOLS: readonly WireProtocol[] = ['OPENAI', 'ANTHROPIC']

/** 线路协议的展示名。 */
export const WIRE_PROTOCOL_LABELS: Record<WireProtocol, string> = {
  OPENAI: 'OpenAI',
  ANTHROPIC: 'Anthropic',
}

/** 判断任意值是否是合法的线路协议标识。 */
export function isWireProtocol(value: unknown): value is WireProtocol {
  return value === 'OPENAI' || value === 'ANTHROPIC'
}
