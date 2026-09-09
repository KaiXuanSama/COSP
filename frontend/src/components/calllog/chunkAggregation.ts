import type { WireProtocol } from '@/types/protocol'

/** 便于 ChunksViewer 一次导入解析器与其协议入参类型。 */
export type { WireProtocol }

export interface ChunkSegment {
  type: 'thinking' | 'content' | 'tool_calls'
  text: string
  toolCalls?: unknown[]
}

type JsonObject = Record<string, unknown>

interface OpenAiToolCall {
  id: string | null
  type: string
  function: {
    name: string | null
    arguments: string
  }
}

interface AnthropicBlock {
  type: ChunkSegment['type'] | null
  text: string
  toolCall?: {
    id: string | null
    name: string | null
    arguments: string
  }
}

/**
 * 将流式响应的原始 data 载荷规整为可读的语义片段。
 *
 * Anthropic 的 event 名未单独保存，但每个事件自身的顶层 type 足以恢复
 * content block 的生命周期。
 *
 * <h2>为何按下游协议而非上游协议判定</h2>
 * 落库的 chunk 是「下游实际收到的形态」：直连时它就是上游原文（两侧协议相同，
 * 怎么判都一样）；跳协议翻译时它是翻译<strong>后</strong>的形态 ——
 * A→O 路线下存的是 OpenAI chunk，若按上游的 ANTHROPIC 去解析，
 * 所有事件都匹配不上，规整视图会空掉。
 */
export function aggregateChunks(chunks: string[], downstreamProtocol: WireProtocol): ChunkSegment[] {
  return downstreamProtocol === 'ANTHROPIC'
    ? aggregateAnthropicChunks(chunks)
    : aggregateOpenAiChunks(chunks)
}

/**
 * OpenAI 流：按<strong>帧序</strong>切段，同类型连续帧并入当前段。
 *
 * <h2>为何必须保留顺序</h2>
 * 早先这里把整条流归成三个桶（reasoning / tool_calls / content），最后按写死的
 * `thinking → tool_calls → content` 顺序输出。代价是<strong>规整视图永远显示
 * 「工具调用在正文之前」</strong>，与上游实际帧序无关。
 *
 * <p>这让日志在「顺序类问题」上给出误导性的图像。直接动因是一次排查
 * 「工具调用先于正文时客户端不执行工具」的怀疑 —— 那个怀疑后来被实测推翻
 * （见 `tools/mock-toolorder/README.md`），但推翻它的前提正是先让日志说真话：
 * <strong>日志不可信时，由它得出的现象描述也不可信</strong>。
 *
 * <h2>与 Anthropic 侧对齐</h2>
 * Anthropic 侧靠 `content_block_start` 天然分段，顺序由 `blockOrder` 记录。
 * OpenAI 没有等价的分段边界事件，但每帧属于哪个类型是明确的（看 delta 里出现的键），
 * 因此改成「类型切换即开新段」，两侧的分段粒度就一致了。
 *
 * <p>这同时修好了另一个哑病：**「正文 → 工具 → 正文」这种交替**此前会被压成
 * 一段正文加一段工具调用，现在能如实显示三段。
 *
 * <h2>代价</h2>
 * 视图会比以前碎 —— 原先「一段正文」可能变成多段。这是真实形态，不是退步；
 * 需要连续全文时用「块显示」或复制按钮。
 *
 * <h2>tool_calls 按 index 全局合并，段只记位置</h2>
 * 这一点最容易写错。工具调用的 `arguments` 跨帧增量到达，`index` 才是它的身份
 * （`id` 与 `name` 只在首帧出现），而**分片之间可能被正文帧打断**。
 *
 * <p>因此合并表必须是全流程唯一的：若改成「每个 tool_calls 段各持一张表」，
 * 被正文打断的工具调用会碎成两段，两半的 `arguments` 各自都不是合法 JSON
 * （如 `{"city":"Hang` 与 `zhou"}`），第二段还会丢掉 `id` 与 `name`。
 *
 * <p>段内只记录「本段涉及哪些 index」，**流结束后**才从全局表取合并后的完整调用。
 * 推迟这一步是必须的：段在遇到下一个类型时就结算了，那一刻参数往往还没收全，
 * 若当场 `parseJsonOrText` 会把半截字符串固化下来。
 *
 * <p>同一个 index 只在首次出现的那一段展示，避免同一个调用重复出现在多段里。
 */
function aggregateOpenAiChunks(chunks: string[]): ChunkSegment[] {
  const segments: ChunkSegment[] = []
  /** 全流程的工具调用合并表，key 为 index。分片可能被正文帧打断，故不能按段分表。 */
  const allCalls = new Map<number, OpenAiToolCall>()
  /** 已被某一段认领的 index，确保同一个调用不重复展示。 */
  const claimed = new Set<number>()
  /** 当前正在累积的段。类型切换或流结束时结算。 */
  let current: { type: ChunkSegment['type']; text: string; indexes: number[] } | null = null

  /** 待回填工具调用的段，与它认领的 index 列表。回填在流结束后进行。 */
  const pendingToolSegments: Array<{ segment: ChunkSegment; indexes: number[] }> = []

  const flush = () => {
    if (!current) return
    if (current.type === 'tool_calls') {
      if (current.indexes.length > 0) {
        const segment: ChunkSegment = { type: 'tool_calls', text: '', toolCalls: [] }
        segments.push(segment)
        pendingToolSegments.push({ segment, indexes: current.indexes })
      }
    } else if (current.text) {
      segments.push({ type: current.type, text: current.text })
    }
    current = null
  }

  /** 切换到目标类型；同类型则复用当前段，不同类型先结算旧段。 */
  const open = (type: ChunkSegment['type']) => {
    if (current?.type !== type) {
      flush()
      current = { type, text: '', indexes: [] }
    }
    return current!
  }

  for (const raw of chunks) {
    if (raw === '[DONE]') continue
    const obj = parseObject(raw)
    if (!obj) continue
    const choices = obj.choices
    if (!Array.isArray(choices) || choices.length === 0) continue

    const firstChoice = asObject(choices[0])
    const delta = firstChoice && asObject(firstChoice.delta)
    if (!delta) continue

    // 单帧内可能同时带思考、工具调用与正文（少数上游会这么发）。按
    // thinking → tool_calls → content 的固定次序处理帧内多类型：帧内次序无从得知
    // （JSON 对象键无序），而这个次序与协议语义一致（思考先于结论）。
    // 跨帧的顺序才是本函数要如实保留的东西。
    const reasoningDelta = delta.reasoning_text ?? delta.reasoning_content
    if (typeof reasoningDelta === 'string' && reasoningDelta.length > 0) {
      open('thinking').text += reasoningDelta
    }

    if (Array.isArray(delta.tool_calls)) {
      const segment = open('tool_calls')
      for (const rawCall of delta.tool_calls) {
        const call = asObject(rawCall)
        if (!call || typeof call.index !== 'number') continue
        const functionPart = asObject(call.function)
        const existing = allCalls.get(call.index)
        if (!existing) {
          allCalls.set(call.index, {
            id: typeof call.id === 'string' ? call.id : null,
            type: typeof call.type === 'string' ? call.type : 'function',
            function: {
              name: typeof functionPart?.name === 'string' ? functionPart.name : null,
              arguments: typeof functionPart?.arguments === 'string' ? functionPart.arguments : '',
            },
          })
        } else {
          if (typeof call.id === 'string') existing.id = call.id
          if (typeof functionPart?.name === 'string') existing.function.name = functionPart.name
          if (typeof functionPart?.arguments === 'string') {
            existing.function.arguments += functionPart.arguments
          }
        }
        if (!claimed.has(call.index)) {
          claimed.add(call.index)
          segment.indexes.push(call.index)
        }
      }
    }

    if (typeof delta.content === 'string' && delta.content.length > 0) {
      open('content').text += delta.content
    }
  }

  flush()

  // 参数分片此时才全部到齐，统一解析回填。
  for (const { segment, indexes } of pendingToolSegments) {
    segment.toolCalls = indexes.map(index => {
      const call = allCalls.get(index)!
      return {
        id: call.id,
        type: call.type,
        function: {
          name: call.function.name,
          arguments: parseJsonOrText(call.function.arguments),
        },
      }
    })
  }

  return segments
}

function aggregateAnthropicChunks(chunks: string[]): ChunkSegment[] {
  const blocks = new Map<number, AnthropicBlock>()
  const blockOrder: number[] = []

  for (const raw of chunks) {
    const event = parseObject(raw)
    if (!event || event.type !== 'content_block_start' && event.type !== 'content_block_delta') continue
    if (typeof event.index !== 'number') continue

    if (event.type === 'content_block_start') {
      const contentBlock = asObject(event.content_block)
      const block = createAnthropicBlock(contentBlock)
      blocks.set(event.index, block)
      blockOrder.push(event.index)
      continue
    }

    const block = blocks.get(event.index)
    const delta = asObject(event.delta)
    if (!block || !delta) continue
    if (delta.type === 'text_delta' && block.type === 'content' && typeof delta.text === 'string') {
      block.text += delta.text
    } else if (delta.type === 'thinking_delta' && block.type === 'thinking' && typeof delta.thinking === 'string') {
      block.text += delta.thinking
    } else if (delta.type === 'input_json_delta' && block.type === 'tool_calls'
        && typeof delta.partial_json === 'string' && block.toolCall) {
      block.toolCall.arguments += delta.partial_json
    }
  }

  return blockOrder.flatMap(index => {
    const block = blocks.get(index)
    if (!block || block.type === null) return []
    if (block.type === 'tool_calls' && block.toolCall) {
      return [{
        type: 'tool_calls' as const,
        text: '',
        toolCalls: [{
          id: block.toolCall.id,
          type: 'function',
          function: {
            name: block.toolCall.name,
            arguments: parseJsonOrText(block.toolCall.arguments),
          },
        }],
      }]
    }
    return block.text ? [{ type: block.type, text: block.text }] : []
  })
}

function createAnthropicBlock(contentBlock: JsonObject | null): AnthropicBlock {
  if (!contentBlock) return { type: null, text: '' }
  if (contentBlock.type === 'text') return { type: 'content', text: '' }
  if (contentBlock.type === 'thinking') return { type: 'thinking', text: '' }
  if (contentBlock.type === 'redacted_thinking') {
    return {
      type: 'thinking',
      text: typeof contentBlock.data === 'string' ? contentBlock.data : '',
    }
  }
  if (contentBlock.type === 'tool_use') {
    return {
      type: 'tool_calls',
      text: '',
      toolCall: {
        id: typeof contentBlock.id === 'string' ? contentBlock.id : null,
        name: typeof contentBlock.name === 'string' ? contentBlock.name : null,
        arguments: '',
      },
    }
  }
  return { type: null, text: '' }
}

function parseObject(raw: string): JsonObject | null {
  try {
    return asObject(JSON.parse(raw))
  } catch {
    return null
  }
}

function asObject(value: unknown): JsonObject | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as JsonObject
    : null
}

function parseJsonOrText(value: string): unknown {
  try {
    return JSON.parse(value)
  } catch {
    return value
  }
}
