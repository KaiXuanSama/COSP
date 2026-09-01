/**
 * 供应商配置的纯领域逻辑。
 *
 * 这些模块不含 UI 与请求，全部可单测；`views/Settings.vue` 与
 * `components/settings/**` 从这里取逻辑，自身只负责状态编排与渲染。
 */

export {
  describeProviderKey,
  toProviderKey,
  type DescribeProviderKeyOptions,
  type ProviderKeyDescription,
  type ProviderKeyStatus,
} from './providerKey'

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
  OVERWRITE_MODE_LABELS,
  buildOverwriteModeHints,
  canonicalizeOverwriteMode,
  modeUsesConfiguredValue,
  nextOverwriteMode,
  parseModeScopedValue,
  serializeModeScopedValue,
  type ModeScopedCodec,
  type ModeScopedValue,
  type OverwriteMode,
} from './overwriteMode'

export {
  directionFromWheel,
  stepInSequence,
  stepNumericPreset,
  type StepDirection,
} from './wheelStep'

export {
  browserScheduleHooks,
  createTransientFlagScheduler,
  type ScheduleHooks,
  type TransientFlagScheduler,
} from './transientFlag'

export {
  DEFAULT_REASONING_EFFORT,
  DEFAULT_REASONING_OVERWRITE_MODE,
  REASONING_EFFORT_OPTIONS,
  REASONING_OVERWRITE_MODES,
  REASONING_OVERWRITE_MODE_HINTS,
  REASONING_OVERWRITE_MODE_LABELS,
  nextReasoningOverwriteMode,
  parseReasoningEffortConfig,
  serializeReasoningEffortConfig,
  type ReasoningEffortConfig,
  type ReasoningOverwriteMode,
} from './reasoningEffort'

export {
  DEFAULT_MAX_OUTPUT_OVERWRITE_MODE,
  DEFAULT_MAX_OUTPUT_TOKENS,
  MAX_OUTPUT_OVERWRITE_MODES,
  MAX_OUTPUT_OVERWRITE_MODE_HINTS,
  MAX_OUTPUT_OVERWRITE_MODE_LABELS,
  MAX_OUTPUT_PRESETS,
  nextMaxOutputOverwriteMode,
  parseMaxOutputConfig,
  serializeMaxOutputConfig,
  type MaxOutputConfig,
  type MaxOutputOverwriteMode,
} from './maxOutput'

export {
  ANTHROPIC_ENDPOINT_SUFFIX,
  DEFAULT_NEW_PROVIDER_PROTOCOLS,
  OPENAI_ENDPOINT_SUFFIX,
  describeEndpoint,
  mirrorAnthropicBaseUrl,
  normalizeProtocols,
  orderProtocolRows,
  protocolsToJson,
  resolveModelPullTarget,
  resolvePrimaryProtocol,
  shouldMirrorOnFocus,
  toggleProtocol,
  type ModelPullTarget,
} from './protocolUrls'

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
