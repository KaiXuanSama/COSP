import { describe, it, expect } from 'vitest'
import { transform } from './engine'
import { pathExists, pathValue, jsonEquals, parsePath } from './path'
import { MIMO_EXAMPLE_RULESET, DEFAULT_REQUEST_BODY } from './defaultRequestBody'
import type { RuleSet, FieldRule } from './types'
import { createEmptyRuleSet, createEmptyRule } from './types'

// ==================== 路径解析测试 ====================

describe('parsePath', () => {
  it('解析简单字段路径', () => {
    expect(parsePath('./image_url')).toEqual([{ field: 'image_url', arrayWildcard: false }])
  })

  it('解析数组通配符路径', () => {
    expect(parsePath('./content[*]/image_url')).toEqual([
      { field: 'content', arrayWildcard: true },
      { field: 'image_url', arrayWildcard: false },
    ])
  })

  it('解析嵌套字段路径', () => {
    expect(parsePath('./a/b/c')).toEqual([
      { field: 'a', arrayWildcard: false },
      { field: 'b', arrayWildcard: false },
      { field: 'c', arrayWildcard: false },
    ])
  })

  it('不以 ./ 开头时返回 null', () => {
    expect(parsePath('image_url')).toBeNull()
    expect(parsePath('')).toBeNull()
    expect(parsePath('./')).toBeNull()
  })
})

describe('pathExists', () => {
  const obj = {
    role: 'tool',
    content: [
      { type: 'text', text: 'hello' },
      { type: 'image_url', image_url: { url: 'data:...' } },
    ],
    tool_call_id: 'call_123',
  }

  it('直接字段存在', () => {
    expect(pathExists(obj, './role')).toBe(true)
    expect(pathExists(obj, './tool_call_id')).toBe(true)
  })

  it('直接字段不存在', () => {
    expect(pathExists(obj, './nonexistent')).toBe(false)
  })

  it('数组通配符路径存在', () => {
    expect(pathExists(obj, './content[*]/image_url')).toBe(true)
    expect(pathExists(obj, './content[*]/type')).toBe(true)
  })

  it('数组通配符路径不存在', () => {
    expect(pathExists(obj, './content[*]/nonexistent')).toBe(false)
  })

  it('字段值为 null 时仍视为存在', () => {
    expect(pathExists({ x: null }, './x')).toBe(true)
  })

  it('字段值为 false/0/空字符串时仍视为存在', () => {
    expect(pathExists({ a: false }, './a')).toBe(true)
    expect(pathExists({ a: 0 }, './a')).toBe(true)
    expect(pathExists({ a: '' }, './a')).toBe(true)
  })

  it('空数组通配符视为不存在', () => {
    expect(pathExists({ items: [] }, './items[*]/x')).toBe(false)
  })
})

describe('pathValue', () => {
  it('获取直接字段值', () => {
    expect(pathValue({ role: 'tool' }, './role')).toBe('tool')
  })

  it('获取数组通配符路径的第一个匹配值', () => {
    const obj = { content: [{ type: 'text' }, { type: 'image_url' }] }
    expect(pathValue(obj, './content[*]/type')).toBe('text')
  })

  it('路径不存在时返回 undefined', () => {
    expect(pathValue({ a: 1 }, './b')).toBeUndefined()
  })
})

describe('jsonEquals', () => {
  it('原始类型相等', () => {
    expect(jsonEquals('tool', 'tool')).toBe(true)
    expect(jsonEquals(1, 1)).toBe(true)
    expect(jsonEquals(true, true)).toBe(true)
    expect(jsonEquals(null, null)).toBe(true)
  })

  it('原始类型不相等', () => {
    expect(jsonEquals('tool', 'user')).toBe(false)
    expect(jsonEquals(1, '1')).toBe(false)
    expect(jsonEquals(true, 1)).toBe(false)
  })

  it('对象相等', () => {
    expect(jsonEquals({ a: 1, b: 2 }, { b: 2, a: 1 })).toBe(true)
    expect(jsonEquals({ a: 1 }, { a: 1, b: 2 })).toBe(false)
  })

  it('数组相等', () => {
    expect(jsonEquals([1, 2, 3], [1, 2, 3])).toBe(true)
    expect(jsonEquals([1, 2], [1, 2, 3])).toBe(false)
  })
})

// ==================== 引擎测试 ====================

describe('transform - 基础操作', () => {
  it('空规则集不修改输入', () => {
    const input = { model: 'gpt', temperature: 0.7 }
    const result = transform(input, createEmptyRuleSet())
    expect(result.output).toEqual(input)
    expect(result.warnings).toEqual([])
  })

  it('不修改原始输入对象', () => {
    const input = { role: 'tool', tool_call_id: '123' }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'role',
          operations: [{ type: 'set_value', value: 'user' }],
        },
      ],
    }
    transform(input, ruleSet)
    expect(input.role).toBe('tool') // 原始未被修改
  })

  it('set_value 保持 JSON 类型', () => {
    const input = { temperature: 0.5, enabled: false, count: 10 }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'temperature',
          operations: [{ type: 'set_value', value: 0.9 }],
        },
        {
          ...createEmptyRule(1),
          field: 'enabled',
          operations: [{ type: 'set_value', value: true }],
        },
        {
          ...createEmptyRule(2),
          field: 'count',
          operations: [{ type: 'set_value', value: 42 }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual({ temperature: 0.9, enabled: true, count: 42 })
  })

  it('delete 删除字段', () => {
    const input = { a: 1, b: 2, c: 3 }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'b',
          operations: [{ type: 'delete' }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual({ a: 1, c: 3 })
  })

  it('edit_object 进入嵌套对象', () => {
    const input = { config: { model: 'gpt', temp: 0.5 } }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'config',
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'temp',
                  operations: [{ type: 'set_value', value: 0.9 }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual({ config: { model: 'gpt', temp: 0.9 } })
  })
})

describe('transform - 数组遍历', () => {
  it('遍历对象数组并对每个元素执行操作', () => {
    const input = {
      messages: [
        { role: 'user', content: 'hello' },
        { role: 'assistant', content: 'hi' },
      ],
    }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'messages',
          array: true,
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'content',
                  operations: [{ type: 'set_value', value: 'modified' }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual({
      messages: [
        { role: 'user', content: 'modified' },
        { role: 'assistant', content: 'modified' },
      ],
    })
  })

  it('跳过非对象数组元素', () => {
    const input = { items: [1, 'string', { x: 1 }, null, { x: 2 }] }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'items',
          array: true,
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'x',
                  operations: [{ type: 'set_value', value: 99 }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual({
      items: [1, 'string', { x: 99 }, null, { x: 99 }],
    })
  })

  it('字段不是数组时静默跳过', () => {
    const input = { messages: 'not an array' }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'messages',
          array: true,
          operations: [{ type: 'edit_object', rules: [] }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual(input)
    expect(result.warnings).toEqual([])
  })
})

describe('transform - 条件执行', () => {
  it('exists 条件满足时执行', () => {
    const input = { messages: [{ role: 'tool', tool_call_id: '123', content: [{ type: 'image_url', image_url: { url: 'data:...' } }] }] }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'messages',
          array: true,
          conditional: true,
          conditions: [{ path: './content[*]/image_url', operator: 'exists', value: null }],
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'role',
                  operations: [{ type: 'set_value', value: 'user' }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    const messages = (result.output as any).messages
    expect(messages[0].role).toBe('user')
  })

  it('exists 条件不满足时跳过', () => {
    const input = { messages: [{ role: 'tool', content: 'plain text' }] }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'messages',
          array: true,
          conditional: true,
          conditions: [{ path: './content[*]/image_url', operator: 'exists', value: null }],
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'role',
                  operations: [{ type: 'set_value', value: 'user' }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    const messages = (result.output as any).messages
    expect(messages[0].role).toBe('tool') // 未被修改
  })

  it('equals 条件满足时执行', () => {
    const input = { role: 'tool' }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'role',
          conditional: true,
          conditions: [{ path: './role', operator: 'equals', value: 'tool' }],
          operations: [{ type: 'set_value', value: 'user' }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect((result.output as any).role).toBe('user')
  })

  it('equals 条件不满足时跳过', () => {
    const input = { role: 'user' }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'role',
          conditional: true,
          conditions: [{ path: './role', operator: 'equals', value: 'tool' }],
          operations: [{ type: 'set_value', value: 'user' }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect((result.output as any).role).toBe('user') // 原值未被修改
  })

  it('多条件 AND 全部满足时执行', () => {
    const input = { messages: [{ role: 'tool', content: [{ type: 'image_url', image_url: { url: 'data:...' } }], tool_call_id: '123' }] }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'messages',
          array: true,
          conditional: true,
          conditions: [
            { path: './content[*]/image_url', operator: 'exists', value: null },
            { path: './role', operator: 'equals', value: 'tool' },
          ],
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'role',
                  operations: [{ type: 'set_value', value: 'user' }],
                },
                {
                  ...createEmptyRule(1),
                  field: 'tool_call_id',
                  operations: [{ type: 'delete' }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    const msg = (result.output as any).messages[0]
    expect(msg.role).toBe('user')
    expect(msg.tool_call_id).toBeUndefined()
  })

  it('多条件 AND 部分不满足时跳过', () => {
    const input = { messages: [{ role: 'user', content: [{ type: 'image_url', image_url: { url: 'data:...' } }], tool_call_id: '123' }] }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'messages',
          array: true,
          conditional: true,
          conditions: [
            { path: './content[*]/image_url', operator: 'exists', value: null },
            { path: './role', operator: 'equals', value: 'tool' },
          ],
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'role',
                  operations: [{ type: 'set_value', value: 'user' }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    const msg = (result.output as any).messages[0]
    expect(msg.role).toBe('user') // 原值未变
    expect(msg.tool_call_id).toBe('123') // 未被删除
  })
})

describe('transform - 规则顺序', () => {
  it('按 order 排序执行', () => {
    const input = { x: 1 }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(1),
          order: 1,
          field: 'x',
          operations: [{ type: 'set_value', value: 2 }],
        },
        {
          ...createEmptyRule(0),
          order: 0,
          field: 'x',
          operations: [{ type: 'set_value', value: 3 }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    // order=0 先执行设为 3，order=1 后执行设为 2
    expect((result.output as any).x).toBe(2)
  })
})

describe('transform - 缺失路径和类型不匹配', () => {
  it('set_value 对不存在的字段会创建它', () => {
    const input = { a: 1 }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'nonexistent',
          operations: [{ type: 'set_value', value: 99 }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual({ a: 1, nonexistent: 99 })
  })

  it('delete 对不存在的字段是空操作', () => {
    const input = { a: 1 }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'nonexistent',
          operations: [{ type: 'delete' }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.output).toEqual({ a: 1 })
  })

  it('edit_object 作用于非对象字段时产生警告', () => {
    const input = { x: 'string' }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: 'x',
          operations: [
            {
              type: 'edit_object',
              rules: [
                {
                  ...createEmptyRule(0),
                  field: 'y',
                  operations: [{ type: 'set_value', value: 1 }],
                },
              ],
            },
          ],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.warnings.length).toBeGreaterThan(0)
    expect(result.warnings[0].message).toContain('不是对象')
  })

  it('未选择字段的规则产生警告', () => {
    const input = { a: 1 }
    const ruleSet: RuleSet = {
      version: 1,
      rules: [
        {
          ...createEmptyRule(0),
          field: '',
          operations: [{ type: 'set_value', value: 99 }],
        },
      ],
    }
    const result = transform(input, ruleSet)
    expect(result.warnings.length).toBe(1)
    expect(result.warnings[0].message).toContain('未选择目标字段')
  })
})

// ==================== MiMo 端到端验收测试 ====================

describe('MiMo 示例规则端到端验收', () => {
  it('图片 tool 消息的 role 改为 user 并删除 tool_call_id', () => {
    const result = transform(DEFAULT_REQUEST_BODY, MIMO_EXAMPLE_RULESET)
    const output = result.output as any

    // 找到图片 tool 消息（messages[4]）
    const imageMsg = output.messages[4]
    expect(imageMsg.role).toBe('user')
    expect(imageMsg.tool_call_id).toBeUndefined()

    // image_url 对象结构保持不变
    const imageItem = imageMsg.content.find((item: any) => item.type === 'image_url')
    expect(imageItem.image_url).toEqual({ url: 'data:image/png;base64,<base64>' })
  })

  it('非图片 tool 消息不受影响', () => {
    const result = transform(DEFAULT_REQUEST_BODY, MIMO_EXAMPLE_RULESET)
    const output = result.output as any

    // messages[3] 是纯文本 tool 消息
    const textToolMsg = output.messages[3]
    expect(textToolMsg.role).toBe('tool')
    expect(textToolMsg.tool_call_id).toBe('<string>')
  })

  it('普通 user 和 assistant 消息不受影响', () => {
    const result = transform(DEFAULT_REQUEST_BODY, MIMO_EXAMPLE_RULESET)
    const output = result.output as any

    expect(output.messages[0].role).toBe('system')
    expect(output.messages[1].role).toBe('user')
    expect(output.messages[2].role).toBe('assistant')
  })

  it('根字段不受影响', () => {
    const result = transform(DEFAULT_REQUEST_BODY, MIMO_EXAMPLE_RULESET)
    const output = result.output as any

    expect(output.model).toBe(DEFAULT_REQUEST_BODY.model)
    expect(output.temperature).toBe(DEFAULT_REQUEST_BODY.temperature)
    expect(output.stream).toBe(DEFAULT_REQUEST_BODY.stream)
    expect(output.reasoning_effort).toBe(DEFAULT_REQUEST_BODY.reasoning_effort)
  })

  it('执行无警告', () => {
    const result = transform(DEFAULT_REQUEST_BODY, MIMO_EXAMPLE_RULESET)
    expect(result.warnings).toEqual([])
  })
})
