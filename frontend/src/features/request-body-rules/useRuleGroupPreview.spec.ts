import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { useRuleGroupPreview } from './useRuleGroupPreview'
import { createRuleGroup } from './editorState'
import { createEmptyRule, type RuleGroup } from './types'
import type { PreviewInput, PreviewResult } from './preview'

function fixture() {
  const group = ref<RuleGroup>({ ...createRuleGroup(0), templateKeys: ['base'], previewBody: { value: 1 }, rules: [] })
  const update = vi.fn((value: RuleGroup) => { group.value = value })
  const calls: PreviewInput[] = []
  const complete: Array<(value: PreviewResult) => void> = []
  const fail: Array<(reason: Error) => void> = []
  const scope = effectScope()
  const view = scope.run(() => useRuleGroupPreview({
    group: () => group.value, update,
    requester: input => {
      calls.push(input)
      return new Promise<PreviewResult>((resolve, reject) => { complete.push(resolve); fail.push(reject) })
    },
  }))!
  return { group, update, calls, complete, fail, scope, view }
}

describe('规则组叠放预览接线', () => {
  afterEach(() => vi.useRealTimers())

  it('编辑一次提交有效样本与自定义标记，受控回写保留真实空行', async () => {
    vi.useFakeTimers()
    const f = fixture()
    const draft = '{\n\n\t"value": 2\n}'
    f.view.updateInputJsonText(draft)
    await nextTick()
    expect(f.update).toHaveBeenCalledTimes(1)
    expect(f.group.value.previewBody).toEqual({ value: 2 })
    expect(f.group.value.templateKeys).toEqual(['custom'])
    expect(f.view.inputJsonText.value).toBe(draft)
    await vi.advanceTimersByTimeAsync(250)
    expect(f.calls).toHaveLength(1)
    expect(f.calls[0]!.sourceText).toBe(draft)
    f.scope.stop()
  })

  it('无效输入和非对象不覆盖上次样本，让在途预览作废并保留旧结果', async () => {
    vi.useFakeTimers()
    const f = fixture()
    await vi.advanceTimersByTimeAsync(250)
    f.complete[0]!({ output: { value: 2 }, warnings: [] })
    await nextTick()
    f.view.updateInputJsonText('{"value":3}')
    await vi.advanceTimersByTimeAsync(250)
    for (const text of ['{', '[]', 'null', '']) {
      f.view.updateInputJsonText(text)
      await nextTick()
      expect(f.view.inputError.value).not.toBe('')
      expect(f.group.value.previewBody).toEqual({ value: 3 })
      expect(f.view.lineView.value.lineMap).toBeNull()
      expect(f.view.outputJsonText.value).toContain('2')
    }
    f.complete[1]!({ output: { value: 999 }, warnings: [] })
    await nextTick()
    expect(f.view.outputJsonText.value).not.toContain('999')
    await vi.advanceTimersByTimeAsync(300)
    expect(f.calls).toHaveLength(2)
    f.scope.stop()
  })

  it('新结果待返回时展示旧 Diff 并暂停映射，成功后按新快照恢复', async () => {
    vi.useFakeTimers()
    const f = fixture()
    await vi.advanceTimersByTimeAsync(250)
    f.complete[0]!({ output: { value: 2 }, warnings: [] })
    await nextTick()
    expect(f.view.lineView.value.lineMap).not.toBeNull()
    f.view.updateInputJsonText('{\n  "value": 4\n}')
    expect(f.view.lineView.value.lineMap).toBeNull()
    expect(f.view.lineView.value.comparison?.resultText).toContain('2')
    await vi.advanceTimersByTimeAsync(250)
    f.complete[1]!({ output: { value: 5 }, warnings: [] })
    await nextTick()
    expect(f.view.lineView.value.lineMap).not.toBeNull()
    expect(f.view.outputJsonText.value).toContain('5')
    f.scope.stop()
  })

  it('切到自定义只改标记，选择结构模板或清空则原子替换样本', async () => {
    vi.useFakeTimers()
    const f = fixture()
    const original = f.view.inputJsonText.value
    f.view.updateTemplateSelection(['base', 'custom'])
    expect(f.group.value.templateKeys).toEqual(['custom'])
    expect(f.view.inputJsonText.value).toBe(original)
    await nextTick()
    f.view.updateTemplateSelection(['custom', 'base', 'message-start'])
    await nextTick()
    expect(f.group.value.templateKeys).toEqual(['base', 'message-start'])
    expect(f.group.value.previewBody.messages).toHaveLength(2)
    expect(JSON.parse(f.view.inputJsonText.value)).toEqual(f.group.value.previewBody)
    f.view.updateTemplateSelection([])
    await nextTick()
    expect(f.group.value.templateKeys).toEqual([])
    expect(f.group.value.previewBody).toEqual({})
    expect(f.view.inputJsonText.value).toBe('{}')
    f.scope.stop()
  })

  it('格式化是明确操作，不改变自定义标记，非法草稿拒绝格式化', async () => {
    vi.useFakeTimers()
    const f = fixture()
    f.view.updateInputJsonText('{"value":2}')
    expect(f.view.formatInput()).toBe(true)
    await nextTick()
    expect(f.view.inputJsonText.value).toBe('{\n  "value": 2\n}')
    expect(f.group.value.templateKeys).toEqual(['custom'])
    f.view.updateInputJsonText('{')
    expect(f.view.formatInput()).toBe(false)
    expect(f.view.inputJsonText.value).toBe('{')
    f.scope.stop()
  })

  it('规则变化重新请求，纯组名和协议修改不重发预览', async () => {
    vi.useFakeTimers()
    const f = fixture()
    await vi.advanceTimersByTimeAsync(250)
    f.group.value = { ...f.group.value, name: '新名称', protocols: ['MESSAGES'] }
    await nextTick()
    await vi.advanceTimersByTimeAsync(300)
    expect(f.calls).toHaveLength(1)
    f.group.value = { ...f.group.value, rules: [createEmptyRule(0)] }
    await nextTick()
    await vi.advanceTimersByTimeAsync(250)
    expect(f.calls).toHaveLength(2)
    expect(f.calls[1]!.rules).toHaveLength(1)
    f.scope.stop()
  })

  it('预览失败保留旧结果、警告与复制文本，显式显示失败原因', async () => {
    vi.useFakeTimers()
    const f = fixture()
    await vi.advanceTimersByTimeAsync(250)
    f.complete[0]!({ output: { value: 8 }, warnings: [{ ruleId: 'r', fieldPath: 'value', message: '样本警告' }] })
    await nextTick()
    f.view.updateInputJsonText('{"value":2}')
    await vi.advanceTimersByTimeAsync(250)
    f.fail[1]!(new Error('样本服务暂时不可用'))
    await nextTick()
    expect(f.view.previewNotice.value).toContain('样本服务暂时不可用')
    expect(f.view.outputJsonText.value).toBe('{\n  "value": 8\n}')
    expect(f.view.warnings.value).toHaveLength(1)
    expect(f.view.lineView.value.lineMap).toBeNull()
    f.scope.stop()
  })

  it('多个规则组独立，外部替换样本可同步，卸载使晚到结果作废', async () => {
    vi.useFakeTimers()
    const a = fixture(), b = fixture()
    a.group.value = { ...a.group.value, previewBody: { replacement: true } }
    await nextTick()
    expect(a.view.inputJsonText.value).toContain('replacement')
    expect(b.view.inputJsonText.value).not.toContain('replacement')
    await vi.advanceTimersByTimeAsync(250)
    a.scope.stop()
    a.complete[0]!({ output: { late: true }, warnings: [] })
    b.complete[0]!({ output: { value: 2 }, warnings: [] })
    await nextTick()
    expect(a.view.scheduler.result.value).toBeNull()
    expect(b.view.scheduler.result.value?.output).toEqual({ value: 2 })
    b.scope.stop()
  })
})
