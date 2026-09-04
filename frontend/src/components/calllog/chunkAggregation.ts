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

function aggregateOpenAiChunks(chunks: string[]): ChunkSegment[] {
  let reasoning = ''
  let content = ''
  const mergedToolCalls = new Map<number, OpenAiToolCall>()

  for (const raw of chunks) {
    if (raw === '[DONE]') continue
    const obj = parseObject(raw)
    if (!obj) continue
    const choices = obj.choices
    if (!Array.isArray(choices) || choices.length === 0) continue

    const firstChoice = asObject(choices[0])
    const delta = firstChoice && asObject(firstChoice.delta)
    if (!delta) continue

    const reasoningDelta = delta.reasoning_text ?? delta.reasoning_content
    if (typeof reasoningDelta === 'string' && reasoningDelta.length > 0) {
      reasoning += reasoningDelta
    }
    if (typeof delta.content === 'string' && delta.content.length > 0) {
      content += delta.content
    }

    if (!Array.isArray(delta.tool_calls)) continue
    for (const rawCall of delta.tool_calls) {
      const call = asObject(rawCall)
      if (!call || typeof call.index !== 'number') continue
      const functionPart = asObject(call.function)
      const existing = mergedToolCalls.get(call.index)
      if (!existing) {
        mergedToolCalls.set(call.index, {
          id: typeof call.id === 'string' ? call.id : null,
          type: typeof call.type === 'string' ? call.type : 'function',
          function: {
            name: typeof functionPart?.name === 'string' ? functionPart.name : null,
            arguments: typeof functionPart?.arguments === 'string' ? functionPart.arguments : '',
          },
        })
        continue
      }
      if (typeof call.id === 'string') existing.id = call.id
      if (typeof functionPart?.name === 'string') existing.function.name = functionPart.name
      if (typeof functionPart?.arguments === 'string') existing.function.arguments += functionPart.arguments
    }
  }

  const segments: ChunkSegment[] = []
  if (reasoning) segments.push({ type: 'thinking', text: reasoning })
  if (mergedToolCalls.size > 0) {
    segments.push({
      type: 'tool_calls',
      text: '',
      toolCalls: [...mergedToolCalls.values()].map(call => ({
        id: call.id,
        type: call.type,
        function: {
          name: call.function.name,
          arguments: parseJsonOrText(call.function.arguments),
        },
      })),
    })
  }
  if (content) segments.push({ type: 'content', text: content })
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
