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
 * Responses 的一个 `output[]` 项在聚合过程中的累积状态。
 *
 * `text` 累加增量，`finalized` 存 `*.done` 给出的全文 —— 两者分开存是必须的：
 * 定稿全文不是又一个增量，混进同一个字段会让正文出现两遍。
 *
 * <p>工具调用的**参数也走 `text` / `finalized`**，`toolCall` 只存身份（id / name）。
 * 这一点曾写错过：参数同时在 `toolCall.arguments` 与 `text` 两处有存储位，
 * 而增量只往 `text` 累加、收尾却从 `toolCall.arguments` 取 —— 拼好的参数全丢了。
 * 单一存储位让这类错配无处可藏。
 */
interface ResponsesItem {
  type: ChunkSegment['type']
  /** 增量累加：正文 / 思考文本，或工具调用的参数分片。 */
  text: string
  /** `*.done` 事件给出的整段全文；`null` 表示只收到增量。 */
  finalized: string | null
  /** 工具调用的身份，只在 `output_item.*` 事件里出现。不存参数。 */
  toolCall?: {
    id: string | null
    name: string | null
  }
}

/**
 * Responses `output[]` 项类型 → 语义段类型。
 *
 * 与后端 `ResponsesContentDetector` 的四个 item 常量一一对应：
 * `message` / `reasoning` / `function_call` / `custom_tool_call`。
 * 工具调用是 `output[]` 的**兄弟项**而非嵌在消息里，这与 Chat 的
 * `delta.tool_calls` 不同。
 */
const RESPONSES_ITEM_TYPES: Record<string, ChunkSegment['type']> = {
  message: 'content',
  reasoning: 'thinking',
  function_call: 'tool_calls',
  custom_tool_call: 'tool_calls',
}

/**
 * 将流式响应的原始 data 载荷规整为可读的语义片段。
 *
 * Anthropic 的 event 名未单独保存，但每个事件自身的顶层 type 足以恢复
 * content block 的生命周期。Responses 同理，靠事件自身的 `type` 即可分派。
 *
 * <h2>为何按下游协议而非上游协议判定</h2>
 * 落库的 chunk 是「下游实际收到的形态」：直连时它就是上游原文（两侧协议相同，
 * 怎么判都一样）；跳协议翻译时它是翻译<strong>后</strong>的形态 ——
 * M2C 路线下存的是 Chat chunk，若按上游的 `MESSAGES` 去解析，
 * 所有事件都匹配不上，规整视图会空掉。
 *
 * <h2>必须穷举每个协议，不能留 else 兜底</h2>
 * 这里曾是 `MESSAGES ? anthropic : openai` 的二值判断。`RESPONSES` 接入后落进
 * 了 else 分支，而 Chat 解析器的第一道门是「没有 `choices` 就跳过」——
 * Responses 事件<strong>没有</strong> `choices`，于是每一帧都被跳过、
 * 规整视图恒为空数组，界面显示「无可解析的响应数据」，而块显示里明明有几十帧。
 *
 * <p>所以现在写成穷举 switch 且不带 `default`：函数有显式返回类型，
 * TypeScript 会在漏掉某个协议时报「并非所有代码路径都返回值」。
 * 加第四个协议时这里必须编译失败 —— 那正是需要它失败的时刻。
 */
export function aggregateChunks(chunks: string[], downstreamProtocol: WireProtocol): ChunkSegment[] {
  switch (downstreamProtocol) {
    case 'MESSAGES':
      return aggregateAnthropicChunks(chunks)
    case 'RESPONSES':
      return aggregateResponsesChunks(chunks)
    case 'CHAT':
      return aggregateOpenAiChunks(chunks)
  }
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

/**
 * Responses 流：按 `output_index` 分项，靠事件 `type` 判定每项的语义。
 *
 * <h2>分段依据是 output_index，不是帧序</h2>
 * 与 Anthropic 的 `content_block_start` 同构：Responses 用
 * `response.output_item.added` 声明一个新 item 并给出它的 `output_index`，
 * 后续增量事件都带同一个下标。所以顺序由「item 首次出现的次序」决定，
 * 不需要像 Chat 那样靠「类型切换」推断边界（Chat 没有等价的分段事件）。
 *
 * <p>`output_index` 缺失时退回 0：少数上游在只有一个 item 时省略该字段。
 * 归到同一段比整帧丢弃更接近事实。
 *
 * <h2>只认三类事件后缀，不枚举 62 个事件名</h2>
 * 官方事件已有 62 个且会继续增加。判据落在<strong>字段名</strong>与
 * <strong>后缀</strong>上，与后端 `ResponsesContentDetector` 同一取向：
 * 事件名会演进，而「`delta` 是增量、`text` / `refusal` / `arguments` 是全文」
 * 这层含义稳定。
 *
 * <h2>为何同时认 delta 与 done</h2>
 * 存在一个 delta 都不发、只在 `*.done` 给全文的上游（后端判定器为此吃过一次
 * 「正常回复被判空响应、重试 5 次」）。但两者不能相加 —— `*.done` 的 `text`
 * 是<strong>整段全文</strong>而非又一个增量，累加会让正文出现两遍。
 * 因此定稿事件走「取全文覆盖」：已有增量则以定稿为准（两者应当一致，
 * 不一致时定稿更权威），没有增量则定稿就是唯一来源。
 *
 * <h2>思考链的两条路径都要认</h2>
 * `reasoning_text.*`（思考正文）与 `reasoning_summary_text.*`（思考摘要）是官方
 * 并列的两支，实测 MiMo 与 DeepSeek 都只走前者。归入同一段是刻意的：
 * 规整视图的语义单位是「这是思考还是正文」，摘要与正文的区别在这个粒度上
 * 不承载信息，分两段只会让界面更碎。
 */
function aggregateResponsesChunks(chunks: string[]): ChunkSegment[] {
  /** 每个 output_index 对应的段。工具调用的参数跨帧增量到达，故必须按下标累积。 */
  const items = new Map<number, ResponsesItem>()
  /** item 首次出现的次序，决定最终段序。 */
  const itemOrder: number[] = []

  const itemAt = (index: number, type: ChunkSegment['type']): ResponsesItem => {
    const existing = items.get(index)
    if (existing) {
      // 已声明的项不改类型：`output_item.added` 给出的类型比增量事件的推断更权威。
      return existing
    }
    const created: ResponsesItem = { type, text: '', finalized: null }
    items.set(index, created)
    itemOrder.push(index)
    return created
  }

  for (const raw of chunks) {
    const event = parseObject(raw)
    if (!event) continue
    const type = typeof event.type === 'string' ? event.type : null
    if (!type) continue

    const index = typeof event.output_index === 'number' ? event.output_index : 0

    // 声明式事件：只建段、不带内容。工具调用的 name 与 id 只在这里出现。
    if (type === 'response.output_item.added' || type === 'response.output_item.done') {
      applyResponsesOutputItem(items, itemOrder, index, asObject(event.item))
      continue
    }

    const semantic = responsesSemanticOf(type)
    if (!semantic) continue

    const item = itemAt(index, semantic)
    if (type.endsWith('.delta')) {
      if (typeof event.delta === 'string') item.text += event.delta
      continue
    }
    // 定稿事件：取全文覆盖而非累加（见方法注释）。
    for (const field of ['text', 'refusal', 'arguments'] as const) {
      const value = event[field]
      if (typeof value === 'string' && value.length > 0) {
        item.finalized = value
        break
      }
    }
  }

  return itemOrder.flatMap((index): ChunkSegment[] => {
    const item = items.get(index)
    if (!item) return []
    // 定稿全文优先；只有增量时用拼接结果。
    const text = item.finalized ?? item.text
    if (item.type === 'tool_calls') {
      if (!item.toolCall) return []
      return [{
        type: 'tool_calls',
        text: '',
        // 参数此刻才全部到齐，与另两侧一样推迟到收尾才解析 ——
        // 当场 `parseJsonOrText` 会把半截字符串（如 `{"city":"Hang`）固定下来。
        toolCalls: [{
          id: item.toolCall.id,
          type: 'function',
          function: { name: item.toolCall.name, arguments: parseJsonOrText(text) },
        }],
      }]
    }
    return text ? [{ type: item.type, text }] : []
  })
}

/**
 * 把 `output_item.added` / `.done` 的 item 并入分段表。
 *
 * <p>工具调用的身份（`id` / `name`）只在这两个事件里出现，参数则通过后续
 * `function_call_arguments.delta` 增量到达 —— 因此这里只能建壳并补身份，
 * <strong>不能</strong>覆盖已累积的参数。`.done` 事件会再来一次，
 * 那时若把 `arguments` 无条件写进去，反而会覆盖掉更完整的增量结果；
 * 故只在自身非空时才采用。
 */
function applyResponsesOutputItem(
  items: Map<number, ResponsesItem>,
  itemOrder: number[],
  index: number,
  item: JsonObject | null,
): void {
  if (!item) return
  const itemType = typeof item.type === 'string' ? item.type : null
  const semantic = itemType ? RESPONSES_ITEM_TYPES[itemType] : undefined
  if (!semantic) return

  let existing = items.get(index)
  if (!existing) {
    existing = { type: semantic, text: '', finalized: null }
    items.set(index, existing)
    itemOrder.push(index)
  } else {
    // 声明事件的类型比增量事件的推断权威：增量先到时段可能被建成了别的语义。
    existing.type = semantic
  }

  if (semantic !== 'tool_calls') return
  const id = typeof item.id === 'string' ? item.id : null
  const name = typeof item.name === 'string' ? item.name : null

  if (!existing.toolCall) {
    existing.toolCall = { id, name }
  } else {
    // 只在非空时覆盖：`.done` 会把 item 再发一次，字段可能比首次更稀疏。
    if (id) existing.toolCall.id = id
    if (name) existing.toolCall.name = name
  }

  // 官方两种工具项的参数字段名不同：`function_call` 用 `arguments`、
  // `custom_tool_call` 用 `input`。只认一个会让另一种显示成「无参数」。
  const inlineArguments = typeof item.arguments === 'string' && item.arguments.length > 0
    ? item.arguments
    : typeof item.input === 'string' && item.input.length > 0 ? item.input : null
  // 当作定稿全文而非增量：item 里的参数本就是完整的一份。
  // 仅在非空时赋值 —— `.done` 常带一个空串，无条件采用会抹掉 delta 拼好的结果。
  if (inlineArguments) existing.finalized = inlineArguments
}

/**
 * 事件类型 → 语义段类型。返回 `undefined` 表示该事件不承载内容。
 *
 * <p>用 `includes` 而非精确匹配，因为同一语义有 `.delta` 与 `.done` 两种后缀，
 * 且官方还在往里加中间事件。判据是「事件名里出现哪个内容标识」。
 */
function responsesSemanticOf(type: string): ChunkSegment['type'] | undefined {
  if (type.includes('reasoning')) return 'thinking'
  if (type.includes('function_call_arguments') || type.includes('custom_tool_call_input')) {
    return 'tool_calls'
  }
  // refusal 归入正文：模型拒绝回答时那段说明就是它给出的正文，
  // 单独立一类会让视图多一个只在拒绝时出现的分支，而语义上它确实是助手输出。
  if (type.includes('output_text') || type.includes('refusal')) return 'content'
  return undefined
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
