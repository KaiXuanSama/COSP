import { describe, expect, it } from 'vitest'
import { aggregateChunks } from './chunkAggregation'

describe('aggregateChunks', () => {
  it('keeps the existing OpenAI reasoning, tool-call, and content aggregation', () => {
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
        type: 'tool_calls',
        text: '',
        toolCalls: [{
          id: 'call_1',
          type: 'function',
          function: { name: 'weather', arguments: { city: 'Hangzhou' } },
        }],
      },
      { type: 'content', text: '答案：杭州' },
    ])
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
