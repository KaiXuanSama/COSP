import { computed, onScopeDispose, ref, watch } from 'vue'
import { composeRequestBodyTemplate, type RequestBodyTemplateKey } from './requestBodyTemplates'
import { createPreviewScheduler, type PreviewRequester } from './preview'
import { resolvePreviewLines } from './jsonLines'
import { jsonEquals } from './diff'
import type { RuleGroup } from './types'

interface RuleGroupPreviewOptions {
  group: () => RuleGroup
  update: (group: RuleGroup) => void
  requester: PreviewRequester
}

function parseDraft(text: string): { body: Record<string, unknown> | null; error: string } {
  if (!text.trim()) return { body: null, error: '输入为空' }
  try {
    const body = JSON.parse(text)
    if (body === null || typeof body !== 'object' || Array.isArray(body)) {
      return { body: null, error: '请求体必须是 JSON 对象' }
    }
    return { body, error: '' }
  } catch (cause) {
    return { body: null, error: `JSON 语法错误：${cause instanceof Error ? cause.message : String(cause)}` }
  }
}

/** 每组独立持有真实草稿；只提交有效样本，预览结果与发起时的草稿快照绑定。 */
export function useRuleGroupPreview(options: RuleGroupPreviewOptions) {
  const inputJsonText = ref(JSON.stringify(options.group().previewBody, null, 2))
  const parsed = computed(() => parseDraft(inputJsonText.value))
  const inputError = computed(() => parsed.value.error)
  const scheduler = createPreviewScheduler(options.requester)

  function commit(text: string, templateKeys?: RequestBodyTemplateKey[]) {
    inputJsonText.value = text
    const body = parsed.value.body
    options.update({
      ...options.group(),
      ...(templateKeys ? { templateKeys } : {}),
      ...(body ? { previewBody: body } : {}),
    })
  }

  function updateInputJsonText(text: string) {
    // 样本与模板标记一次写回，避免两个 emit 分别读取旧 props 而互相覆盖。
    commit(text, ['custom'])
  }

  function updateTemplateSelection(keys: RequestBodyTemplateKey[]) {
    const current = options.group().templateKeys
    const wasCustom = current.length === 1 && current[0] === 'custom'
    if (keys.includes('custom') && !wasCustom) {
      options.update({ ...options.group(), templateKeys: ['custom'] })
      return
    }
    const structured = keys.filter(key => key !== 'custom')
    if (keys.length === 1 && keys[0] === 'custom') return
    commit(JSON.stringify(composeRequestBodyTemplate(structured), null, 2), structured)
  }

  function formatInput(): boolean {
    const body = parsed.value.body
    if (!body) return false
    commit(JSON.stringify(body, null, 2))
    return true
  }

  // 外部替换样本时同步；正常受控回写不规范化当前草稿的缩进、空行或光标。
  watch(() => options.group().previewBody, (body) => {
    if (!jsonEquals(parsed.value.body, body)) inputJsonText.value = JSON.stringify(body, null, 2)
  })

  watch(
    () => JSON.stringify([inputJsonText.value, options.group().rules]),
    () => {
      const body = parsed.value.body
      if (!body) {
        scheduler.invalidate()
        return
      }
      scheduler.schedule({ previewBody: body, rules: options.group().rules, sourceText: inputJsonText.value })
    },
    { immediate: true, flush: 'sync' },
  )
  onScopeDispose(scheduler.dispose)

  const lineView = computed(() => resolvePreviewLines(inputJsonText.value, scheduler.snapshot.value, scheduler.stale.value))
  const outputJsonText = computed(() => scheduler.result.value ? JSON.stringify(scheduler.result.value.output, null, 2) : '')
  const warnings = computed(() => scheduler.result.value?.warnings ?? [])
  const previewNotice = computed(() => {
    if (inputError.value) return '请先修正原始 JSON；已暂停同步滚动'
    if (scheduler.error.value) return `预览请求失败：${scheduler.error.value}；已暂停同步滚动`
    if (!scheduler.result.value) return '正在计算预览…'
    return lineView.value.notice
  })

  return {
    inputJsonText, inputError, scheduler, lineView, outputJsonText, warnings, previewNotice,
    updateInputJsonText, updateTemplateSelection, formatInput,
  }
}
