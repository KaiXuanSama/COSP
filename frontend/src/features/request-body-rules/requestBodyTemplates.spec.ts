import { describe, expect, it } from 'vitest'
import {
  composeRequestBodyTemplate,
  DEFAULT_TEMPLATE_KEYS,
} from './requestBodyTemplates'

describe('请求体模板片段组合', () => {
  it('基础参数片段包含用户指定的全部基础字段且不包含 messages', () => {
    expect(composeRequestBodyTemplate(['base'])).toEqual({
      model: '<string>',
      temperature: 0.1,
      top_p: 1,
      stream: true,
      n: 1,
      stream_options: { include_usage: true },
      reasoning_effort: 'medium',
    })
  })

  it('Message 起始数据包含 system 和 user 消息', () => {
    const result = composeRequestBodyTemplate(['message-start']) as { messages: unknown[] }

    expect(result.messages).toEqual([
      { role: 'system', content: '<string>' },
      { role: 'user', content: '<string>' },
    ])
  })

  it('多个 Message 片段按固定场景顺序追加到同一个数组', () => {
    const result = composeRequestBodyTemplate([
      'message-tool-image',
      'message-assistant',
      'message-tool-basic',
    ]) as { messages: Array<Record<string, unknown>> }

    expect(result.messages.map((message) => message.role)).toEqual([
      'assistant',
      'tool',
      'tool',
    ])
    expect(result.messages[2].content).toBeInstanceOf(Array)
  })

  it('基础参数、消息与工具定义可同时叠加', () => {
    const result = composeRequestBodyTemplate(['base', 'message-start', 'tools'])

    expect(result).toHaveProperty('model', '<string>')
    expect(result).toHaveProperty('messages')
    expect(result).toHaveProperty('tools')
  })

  it('自定义选项本身不生成或覆盖任何字段', () => {
    expect(composeRequestBodyTemplate(['custom'])).toEqual({})
  })

  it('空选择生成空请求体对象', () => {
    expect(composeRequestBodyTemplate([])).toEqual({})
  })

  it('默认选项仅生成基础参数', () => {
    expect(DEFAULT_TEMPLATE_KEYS).toEqual(['base'])
    expect(composeRequestBodyTemplate(DEFAULT_TEMPLATE_KEYS)).toEqual(
      composeRequestBodyTemplate(['base']),
    )
  })
})
