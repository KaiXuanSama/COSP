import { describe, it, expect } from 'vitest'
import { buildDiffTree } from './diff'
import { transform } from './engine'
import { DEFAULT_REQUEST_BODY, MIMO_EXAMPLE_RULESET } from './defaultRequestBody'

describe('buildDiffTree', () => {
  it('未变化时全部标记为 same', () => {
    const input = { a: 1, b: { c: 'x' } }
    const tree = buildDiffTree(input, input)
    expect(tree.status).toBe('same')
    expect(tree.kind).toBe('object')
    expect(tree.children?.every((c) => c.status === 'same')).toBe(true)
  })

  it('字段值修改标记为 changed', () => {
    const original = { role: 'tool' }
    const current = { role: 'user' }
    const tree = buildDiffTree(original, current)
    expect(tree.status).toBe('changed')
    const role = tree.children?.find((c) => c.key === 'role')
    expect(role?.status).toBe('changed')
    expect(role?.value).toBe('user')
  })

  it('字段删除标记为 deleted 并保留原值', () => {
    const original = { role: 'tool', tool_call_id: '123' }
    const current = { role: 'user' }
    const tree = buildDiffTree(original, current)
    const deleted = tree.children?.find((c) => c.key === 'tool_call_id')
    expect(deleted?.status).toBe('deleted')
    expect(deleted?.value).toBe('123')
  })

  it('字段新增标记为 added', () => {
    const original = { a: 1 }
    const current = { a: 1, b: 2 }
    const tree = buildDiffTree(original, current)
    const added = tree.children?.find((c) => c.key === 'b')
    expect(added?.status).toBe('added')
    expect(added?.value).toBe(2)
  })

  it('数组元素不携带序号键名', () => {
    const tree = buildDiffTree(
      { messages: [{ role: 'user' }, { role: 'assistant' }] },
      { messages: [{ role: 'user' }, { role: 'assistant' }] },
    )
    const messages = tree.children?.find((c) => c.key === 'messages')
    expect(messages?.kind).toBe('array')
    expect(messages?.children?.every((child) => child.key === null)).toBe(true)
  })

  it('MiMo 示例规则下图片 tool 消息正确高亮', () => {
    const result = transform(DEFAULT_REQUEST_BODY, MIMO_EXAMPLE_RULESET)
    const tree = buildDiffTree(DEFAULT_REQUEST_BODY, result.output)
    const messages = tree.children?.find((c) => c.key === 'messages')
    expect(messages?.kind).toBe('array')

    // messages[4] 是图片 tool 消息
    const imageMsg = messages?.children?.[4]
    expect(imageMsg).toBeTruthy()
    expect(imageMsg?.status).toBe('changed')

    const role = imageMsg?.children?.find((c) => c.key === 'role')
    expect(role?.status).toBe('changed')
    expect(role?.value).toBe('user')

    const toolCallId = imageMsg?.children?.find((c) => c.key === 'tool_call_id')
    expect(toolCallId?.status).toBe('deleted')
    expect(toolCallId?.value).toBe('<string>')

    // 非图片 tool 消息不受影响
    const textToolMsg = messages?.children?.[3]
    expect(textToolMsg?.status).toBe('same')
  })
})
