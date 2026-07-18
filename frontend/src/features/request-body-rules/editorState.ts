import type { RuleSet } from './types'
import { createEmptyRuleSet } from './types'
import type { RequestBodyTemplateKey } from './requestBodyTemplates'
import { composeRequestBodyTemplate, DEFAULT_TEMPLATE_KEYS } from './requestBodyTemplates'

/** 请求体规则编辑器需要原子保存的完整状态。 */
export interface RequestBodyEditorState {
  templateKeys: RequestBodyTemplateKey[]
  previewBody: Record<string, unknown>
  rules: RuleSet
}

/** 创建默认请求体规则编辑器状态。 */
export function createDefaultRequestBodyEditorState(): RequestBodyEditorState {
  return {
    templateKeys: [...DEFAULT_TEMPLATE_KEYS],
    previewBody: composeRequestBodyTemplate(DEFAULT_TEMPLATE_KEYS),
    rules: createEmptyRuleSet(),
  }
}
