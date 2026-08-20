import { describe, it, expect } from 'vitest'
import { buildDiffTree } from './diff'
import { DEFAULT_REQUEST_BODY } from './defaultRequestBody'

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

  /**
   * 图片 tool 消息被改写后的高亮形态。
   *
   * <p>转换结果是**手写**的而非由引擎算出：规则的执行已迁到后端，前端不再有引擎；
   * 而这条用例要验的是 diff 树的着色，与「谁算出这个结果」无关。
   * 手写输入输出对反而让被测意图更清楚 —— 改了 role、删了 tool_call_id，别的都没动。
   */
  it('图片 tool 消息改写后 role 标 changed、tool_call_id 标 deleted', () => {
    const original = DEFAULT_REQUEST_BODY
    const transformed = JSON.parse(JSON.stringify(original)) as typeof DEFAULT_REQUEST_BODY
    const imageToolMessage = transformed.messages[4] as Record<string, unknown>
    imageToolMessage.role = 'user'
    delete imageToolMessage.tool_call_id

    const tree = buildDiffTree(original, transformed)
    const messages = tree.children?.find((c) => c.key === 'messages')
    expect(messages?.kind).toBe('array')

    const imageMsg = messages?.children?.[4]
    expect(imageMsg?.status).toBe('changed')

    const role = imageMsg?.children?.find((c) => c.key === 'role')
    expect(role?.status).toBe('changed')
    expect(role?.value).toBe('user')

    const toolCallId = imageMsg?.children?.find((c) => c.key === 'tool_call_id')
    expect(toolCallId?.status).toBe('deleted')
    expect(toolCallId?.value).toBe('<string>')

    // 未被改动的文本 tool 消息保持 same
    expect(messages?.children?.[3]?.status).toBe('same')
  })
})
