import { describe, expect, it } from 'vitest'
import { transform } from './engine'
import { RULE_HELP_EXAMPLES } from './helpExamples'

describe('规则帮助实时示例', () => {
  it('每个帮助选项卡都能由正式引擎无警告执行', () => {
    for (const example of RULE_HELP_EXAMPLES) {
      const result = transform(example.input, example.rules)
      expect(result.warnings, example.title).toEqual([])
    }
  })

  it('设置字段值示例把 temperature 改为数字 0.7', () => {
    const example = RULE_HELP_EXAMPLES.find((item) => item.key === 'set-value')!
    const result = transform(example.input, example.rules)

    expect(result.output).toMatchObject({ temperature: 0.7 })
  })

  it('调整对象内容示例只修改对象内部目标字段', () => {
    const example = RULE_HELP_EXAMPLES.find((item) => item.key === 'edit-object')!
    const result = transform(example.input, example.rules)

    expect(result.output).toEqual({
      stream: true,
      stream_options: { include_usage: true, debug: true },
    })
  })

  it('删除字段示例从真实输出中移除目标字段', () => {
    const example = RULE_HELP_EXAMPLES.find((item) => item.key === 'delete')!
    const result = transform(example.input, example.rules) as { output: Record<string, unknown> }

    expect(result.output).not.toHaveProperty('reasoning_effort')
  })

  it('条件数组示例仅修改同时满足全部条件的元素', () => {
    const example = RULE_HELP_EXAMPLES.find((item) => item.key === 'conditions')!
    const result = transform(example.input, example.rules) as {
      output: { messages: Array<Record<string, unknown>> }
    }

    expect(result.output.messages[0]).toEqual({ role: 'user', content: 'hello' })
    expect(result.output.messages[1]).toEqual({ role: 'tool', content: 'result' })
    expect(result.output.messages[2]).toEqual({ role: 'tool', content: 'already compatible' })
  })

  it('图片工具消息兼容示例只转换图片 tool 消息并保留 image_url', () => {
    const example = RULE_HELP_EXAMPLES.find((item) => item.key === 'image-tool-compatibility')!
    const result = transform(example.input, example.rules) as {
      output: { messages: Array<Record<string, any>> }
    }

    expect(result.output.messages[0]).toHaveProperty('tool_call_id', 'call_text')
    expect(result.output.messages[1].role).toBe('user')
    expect(result.output.messages[1]).not.toHaveProperty('tool_call_id')
    expect(result.output.messages[1].content[1].image_url.url).toBe('data:image/png;base64,...')
  })
})
