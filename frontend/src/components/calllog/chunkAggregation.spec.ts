import { describe, expect, it } from 'vitest'
import { aggregateChunks } from './chunkAggregation'

describe('aggregateChunks', () => {
  /**
   * 单帧内同时带三种载荷时，按 thinking → tool_calls → content 处理。
   *
   * 帧内次序无从得知（JSON 对象键无序），而这个固定次序与协议语义一致。
   * 跨帧顺序才是要如实保留的东西，见下面几条用例。
   *
   * 注意第二帧的 content 会并入**新的**正文段而非第一帧那段：中间隔了 tool_calls，
   * 类型切换即分段。这正是「正文 → 工具 → 正文」能被如实显示的机制。
   */
  it('handles multiple payload kinds inside one chunk in a fixed intra-chunk order', () => {
    const segments = aggregateChunks([
      JSON.stringify({
        choices: [{
          delta: {
            reasoning_content: '先分析。',
            content: '答案：',
            tool_calls: [{
              index: 0,
              id: 'call_1',
              type: 'function',
              function: { name: 'weather', arguments: '{"city":"Hang' },
            }],
          },
        }],
      }),
      JSON.stringify({
        choices: [{
          delta: {
            content: '杭州',
            tool_calls: [{ index: 0, function: { arguments: 'zhou"}' } }],
          },
        }],
      }),
      '[DONE]',
    ], 'OPENAI')

    expect(segments).toEqual([
      { type: 'thinking', text: '先分析。' },
      {
        // 参数在两帧里分片到达，合并成完整 JSON —— 合并表是全流程唯一的，
        // 因此中间被 content 帧打断也不会把一个调用切成两半。
        type: 'tool_calls',
        text: '',
        toolCalls: [{
          id: 'call_1',
          type: 'function',
          function: { name: 'weather', arguments: { city: 'Hangzhou' } },
        }],
      },
      // 第一帧的「答案：」与第二帧的「杭州」各自成段：两帧的 content 之间
      // 都插入了 tool_calls 帧内载荷，因此发生了类型切换。
      // 第二帧的 tool_calls 分片虽然也开了一个新段，但它没有认领新的 index
      // （index 0 已被第一段认领），那个空段不产出任何内容。
      { type: 'content', text: '答案：' },
      { type: 'content', text: '杭州' },
    ])
  })

  /**
   * 工具调用先于正文时，段序必须如实反映，不得重排成「工具在后」。
   *
   * 这一条是本组的核心：早先的实现把整条流归成三个桶后按写死的
   * `thinking → tool_calls → content` 输出，于是无论上游实际顺序如何，
   * 规整视图永远显示工具调用在正文之前 —— 顺序类问题因此无法从日志中看出。
   */
  it('preserves tool-call-before-content order for OpenAI streams', () => {
    const segments = aggregateChunks([
      JSON.stringify({
        choices: [{
          delta: {
            role: 'assistant',
            tool_calls: [{
              index: 0,
              id: 'call_x',
              type: 'function',
              function: { name: 'read_file', arguments: '{"filePath":' },
            }],
          },
        }],
      }),
      JSON.stringify({
        choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: '"README.md"}' } }] } }],
      }),
      JSON.stringify({ choices: [{ delta: { content: '我来看一下' } }] }),
      JSON.stringify({ choices: [{ delta: { content: '这个文件的内容。' } }] }),
      JSON.stringify({ choices: [{ delta: {}, finish_reason: 'tool_calls' }] }),
      '[DONE]',
    ], 'OPENAI')

    expect(segments).toEqual([
      {
        type: 'tool_calls',
        text: '',
        toolCalls: [{
          id: 'call_x',
          type: 'function',
          function: { name: 'read_file', arguments: { filePath: 'README.md' } },
        }],
      },
      { type: 'content', text: '我来看一下这个文件的内容。' },
    ])
  })

  /** 反向顺序：正文在前、工具调用在后，同样如实保留。 */
  it('preserves content-before-tool-call order for OpenAI streams', () => {
    const segments = aggregateChunks([
      JSON.stringify({ choices: [{ delta: { role: 'assistant', content: '我来看一下' } }] }),
      JSON.stringify({ choices: [{ delta: { content: '这个文件的内容。' } }] }),
      JSON.stringify({
        choices: [{
          delta: {
            tool_calls: [{
              index: 0,
              id: 'call_x',
              type: 'function',
              function: { name: 'read_file', arguments: '{"filePath":"README.md"}' },
            }],
          },
        }],
      }),
      JSON.stringify({ choices: [{ delta: {}, finish_reason: 'tool_calls' }] }),
      '[DONE]',
    ], 'OPENAI')

    expect(segments).toEqual([
      { type: 'content', text: '我来看一下这个文件的内容。' },
      {
        type: 'tool_calls',
        text: '',
        toolCalls: [{
          id: 'call_x',
          type: 'function',
          function: { name: 'read_file', arguments: { filePath: 'README.md' } },
        }],
      },
    ])
  })

  /**
   * 「正文 → 工具 → 正文」交替必须显示成三段。
   *
   * 早先的归桶实现会把两段正文合成一段，产出「一段正文 + 一段工具调用」，
   * 丢掉了模型「说一句、调个工具、再说一句」这个真实过程。
   */
  it('splits interleaved content and tool calls into separate segments', () => {
    const segments = aggregateChunks([
      JSON.stringify({ choices: [{ delta: { content: '先看文件。' } }] }),
      JSON.stringify({
        choices: [{
          delta: {
            tool_calls: [{
              index: 0,
              id: 'call_a',
              type: 'function',
              function: { name: 'read_file', arguments: '{}' },
            }],
          },
        }],
      }),
      JSON.stringify({ choices: [{ delta: { content: '读完了。' } }] }),
      '[DONE]',
    ], 'OPENAI')

    expect(segments.map(s => s.type)).toEqual(['content', 'tool_calls', 'content'])
    expect(segments[0].text).toBe('先看文件。')
    expect(segments[2].text).toBe('读完了。')
  })

  /** 同一工具调用的跨帧参数分片不得被切成多段。 */
  it('keeps a single tool call together across argument fragments', () => {
    const segments = aggregateChunks([
      JSON.stringify({
        choices: [{
          delta: {
            tool_calls: [{
              index: 0,
              id: 'call_1',
              type: 'function',
              function: { name: 'search', arguments: '{"q":' },
            }],
          },
        }],
      }),
      JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: '"a' } }] } }] }),
      JSON.stringify({ choices: [{ delta: { tool_calls: [{ index: 0, function: { arguments: 'bc"}' } }] } }] }),
      '[DONE]',
    ], 'OPENAI')

    expect(segments).toHaveLength(1)
    expect(segments[0].toolCalls).toEqual([{
      id: 'call_1',
      type: 'function',
      function: { name: 'search', arguments: { q: 'abc' } },
    }])
  })

  /** 并行调用（多个 index）在同一段内并列，不因 index 不同而分段。 */
  it('keeps parallel tool calls in one segment', () => {
    const segments = aggregateChunks([
      JSON.stringify({
        choices: [{
          delta: {
            tool_calls: [
              { index: 0, id: 'call_a', type: 'function', function: { name: 'f1', arguments: '{}' } },
              { index: 1, id: 'call_b', type: 'function', function: { name: 'f2', arguments: '{}' } },
            ],
          },
        }],
      }),
      '[DONE]',
    ], 'OPENAI')

    expect(segments).toHaveLength(1)
    expect(segments[0].toolCalls).toHaveLength(2)
  })

  it('aggregates Anthropic thinking, text, and tool-use blocks in block order', () => {
    const segments = aggregateChunks([
      JSON.stringify({ type: 'message_start', message: { usage: { input_tokens: 12 } } }),
      JSON.stringify({
        type: 'content_block_start',
        index: 0,
        content_block: { type: 'thinking', thinking: '', signature: '' },
      }),
      JSON.stringify({
        type: 'content_block_delta',
        index: 0,
        delta: { type: 'thinking_delta', thinking: '先分析。' },
      }),
      JSON.stringify({ type: 'content_block_stop', index: 0 }),
      JSON.stringify({
        type: 'content_block_start',
        index: 1,
        content_block: { type: 'tool_use', id: 'toolu_1', name: 'weather', input: {} },
      }),
      JSON.stringify({
        type: 'content_block_delta',
        index: 1,
        delta: { type: 'input_json_delta', partial_json: '{"city":"Hang' },
      }),
      JSON.stringify({
        type: 'content_block_delta',
        index: 1,
        delta: { type: 'input_json_delta', partial_json: 'zhou"}' },
      }),
      JSON.stringify({ type: 'content_block_stop', index: 1 }),
      JSON.stringify({
        type: 'content_block_start',
        index: 2,
        content_block: { type: 'text', text: '' },
      }),
      JSON.stringify({
        type: 'content_block_delta',
        index: 2,
        delta: { type: 'text_delta', text: '这是正文。' },
      }),
      JSON.stringify({ type: 'message_stop' }),
    ], 'ANTHROPIC')

    expect(segments).toEqual([
      { type: 'thinking', text: '先分析。' },
      {
        type: 'tool_calls',
        text: '',
        toolCalls: [{
          id: 'toolu_1',
          type: 'function',
          function: { name: 'weather', arguments: { city: 'Hangzhou' } },
        }],
      },
      { type: 'content', text: '这是正文。' },
    ])
  })

  /**
   * Anthropic 侧的工具在前顺序（与上面 OpenAI 那条对称）。
   *
   * 这一侧原本就按 `blockOrder` 输出，本用例是回归防护：确保两侧在同一场景下
   * 给出同样的段序，否则跨协议一致性校验（ChunksViewer 的 semanticConsistency）
   * 会因为分段粒度不同而误报。
   */
  it('preserves tool-use-before-text order for Anthropic streams', () => {
    const segments = aggregateChunks([
      JSON.stringify({ type: 'message_start', message: { usage: { input_tokens: 12 } } }),
      JSON.stringify({
        type: 'content_block_start',
        index: 0,
        content_block: { type: 'tool_use', id: 'toolu_1', name: 'read_file', input: {} },
      }),
      JSON.stringify({
        type: 'content_block_delta',
        index: 0,
        delta: { type: 'input_json_delta', partial_json: '{"filePath":"README.md"}' },
      }),
      JSON.stringify({ type: 'content_block_stop', index: 0 }),
      JSON.stringify({
        type: 'content_block_start',
        index: 1,
        content_block: { type: 'text', text: '' },
      }),
      JSON.stringify({
        type: 'content_block_delta',
        index: 1,
        delta: { type: 'text_delta', text: '我来看一下。' },
      }),
      JSON.stringify({ type: 'message_stop' }),
    ], 'ANTHROPIC')

    expect(segments.map(s => s.type)).toEqual(['tool_calls', 'content'])
  })

  it('ignores Anthropic control and malformed events without treating them as content', () => {
    expect(aggregateChunks([
      '{bad json',
      JSON.stringify({ type: 'message_start', message: {} }),
      JSON.stringify({ type: 'message_delta', delta: { stop_reason: 'end_turn' } }),
      JSON.stringify({ type: 'message_stop' }),
    ], 'ANTHROPIC')).toEqual([])
  })

  /**
   * 跨协议翻译（A→O）路线下，落库的 chunk 已经是 OpenAI 形态，
   * 因此必须按**下游**协议解析。
   *
   * 早先这里传的是上游协议，导致 A→O 的日志按 Anthropic 规则去解析
   * 已经翻译好的 OpenAI chunk，所有事件都匹配不上，规整视图显示「无可解析的响应数据」。
   */
  it('parses translated OpenAI chunks from a cross-protocol call by downstream protocol', () => {
    const translatedChunks = [
      JSON.stringify({
        choices: [{ delta: { role: 'assistant', content: '' } }],
      }),
      JSON.stringify({
        choices: [{ delta: { reasoning_content: '先想一下' } }],
      }),
      JSON.stringify({
        choices: [{ delta: { content: '好' } }],
      }),
      JSON.stringify({
        choices: [{ delta: { content: '' }, finish_reason: 'stop' }],
      }),
      '[DONE]',
    ]

    expect(aggregateChunks(translatedChunks, 'OPENAI')).toEqual([
      { type: 'thinking', text: '先想一下' },
      { type: 'content', text: '好' },
    ])
  })
})
