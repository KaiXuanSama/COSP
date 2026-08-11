/**
 * 供应商配置的纯领域逻辑。
 *
 * 这些模块不含 UI 与请求，全部可单测；`views/Settings.vue` 与
 * `components/settings/**` 从这里取逻辑，自身只负责状态编排与渲染。
 */

export { toProviderKey } from './providerKey'

export {
  NEW_KEY_VALUE_PREFIX,
  displayKey,
  hasPlaintext,
  isNewKeyValue,
  isPersisted,
  keepMeaningfulEntries,
  maskApiKey,
  newKeyValue,
  resolveActiveValue,
  resolvePullCredential,
  toApiKeyPayloads,
  type ApiKeyPayload,
  type ResolvedPullCredential,
} from './apiKey'

export {
  buildEditableModel,
  extractModelNames,
  toEditableModel,
  toModelFormParams,
  type EditableModel,
} from './modelPayload'

export {
  applyPullDiff,
  buildPullDiff,
  hasChanges,
  revertDiffEntry,
  type PullDiff,
  type PullDiffEntry,
  type PullDiffStatus,
} from './pullDiff'

export { resolvePullModelsErrorMessage } from './errorMessage'

export {
  IMAGE_COMPATIBILITY_TEMPLATE_KEYS,
  aggregatorPresets,
  allPresets,
  createProviderDefaultEditorState,
  findPreset,
  officialPresets,
  presetRuleSetV2,
  relayPresets,
  toPresetFormValues,
  type HeaderEntry,
  type PresetFormValues,
  type ProviderPreset,
} from './presets'
