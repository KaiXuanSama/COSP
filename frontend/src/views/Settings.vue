<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { NCard, NCheckbox, NInput, NButton, NSwitch, NTag, NDrawer, NDrawerContent, NModal, NSelect, NDropdown, useMessage } from 'naive-ui'
import ProviderModelsSection from '@/components/settings/ProviderModelsSection.vue'
import RequestBodyRuleEditor from '@/components/settings/request-body-rules/RequestBodyRuleEditor.vue'
import { useProviderStore, type ApiKeyEntry } from '@/stores/providers'
import type { RequestBodyEditorState } from '@/features/request-body-rules/editorState'
import { countRules } from '@/features/request-body-rules/editorState'
import { migrateRuleSet } from '@/features/request-body-rules/migration'
import { WIRE_PROTOCOL_LABELS, type WireProtocol } from '@/types/protocol'
import {
  ANTHROPIC_ENDPOINT_SUFFIX,
  DEFAULT_NEW_PROVIDER_PROTOCOLS,
  OPENAI_ENDPOINT_SUFFIX,
  aggregatorPresets,
  applyPullDiff,
  buildEditableModel,
  buildPullDiff,
  createProviderDefaultEditorState,
  describeEndpoint,
  describeProviderKey,
  displayKey,
  extractModelNames,
  findPreset,
  mirrorAnthropicBaseUrl,
  normalizeProtocols,
  orderProtocolRows,
  protocolsToJson,
  resolveModelPullTarget,
  resolvePrimaryProtocol,
  shouldMirrorOnFocus,
  toggleProtocol,
  hasChanges as pullDiffHasChanges,
  isNewKeyValue,
  keepMeaningfulEntries,
  newKeyValue,
  officialPresets,
  relayPresets,
  resolveActiveKeyIndex,
  resolveActiveValue,
  resolvePullCredential,
  resolvePullModelsErrorMessage,
  revertDiffEntry,
  toApiKeyPayloads,
  toEditableModel,
  toModelFormParams,
  toPresetFormValues,
  toProviderKey,
  type EditableModel,
  type HeaderEntry,
  type PullDiff,
} from '@/features/provider-config'

const providerStore = useProviderStore()
const message = useMessage()

const windowWidth = ref(window.innerWidth)
const DRAWER_MIN_WIDTH = 700
const drawerWidth = computed(() =>
  windowWidth.value <= DRAWER_MIN_WIDTH ? windowWidth.value : DRAWER_MIN_WIDTH
)

function onResize() {
  windowWidth.value = window.innerWidth
}

onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const providerMeta = ref<Record<string, { displayName: string; colorClass: string; apiUrlPlaceholder: string }>>({})

const editingKey = ref<string | null>(null)
const editForm = ref({
  baseUrl: '',
  anthropicBaseUrl: '',
  protocols: [...DEFAULT_NEW_PROVIDER_PROTOCOLS] as WireProtocol[],
  apiKeys: [] as ApiKeyEntry[],
  activeKeyUuid: '' as string,
  models: [] as EditableModel[],
})

/**
 * 抽屉里 OpenAI 地址编辑是否联动 Anthropic 地址。
 *
 * 与弹窗同一套逻辑但各自持有状态：两个界面可以先后打开，共用一个快照会让
 * 在弹窗里的一次聚焦影响抽屉的联动行为。
 */
const editMirroringAnthropicBaseUrl = ref(false)

/**
 * 折叠时显示在第一行的协议。
 *
 * <strong>打开抽屉时快照一次，之后不随勾选变化。</strong>做成 computed 会让
 * 「取消勾选 OpenAI」的瞬间两行交换位置，用户正在编辑的输入框跳到另一行去 ——
 * 那个跳动没有任何信息价值，纯粹是布局规则的副作用。
 */
const editPrimaryProtocol = ref<WireProtocol>('OPENAI')

/** 第二行地址是否展开。 */
const editUrlsExpanded = ref(false)

/**
 * 折叠动画的高度由这三个钩子按元素实际高度给出。
 *
 * 纯 CSS 写不好这个动画：`max-height` 的目标值只能猜，猜大了前段就空转；
 * 而 `grid-template-rows` 的 `fr` 插值非线性，250ms 的过渡实测约 75ms 就走完了
 * 大部分路程。读一次 `scrollHeight` 两个问题同时消失 —— 值是量出来的，
 * 且 `px` 的插值是线性的。
 */
function onProtocolRowEnter(el: Element) {
  const target = el as HTMLElement
  // 起点由 CSS 的 enter-from 给（max-height: 0），这里只需把终点设成实际高度。
  target.style.maxHeight = `${target.scrollHeight}px`
}

/** 动画结束后清掉内联高度，否则内容变高（如换行）时会被这个固定值裁掉。 */
function onProtocolRowAfterEnter(el: Element) {
  ;(el as HTMLElement).style.maxHeight = ''
}

function onProtocolRowLeave(el: Element) {
  const target = el as HTMLElement
  // 离场起点必须显式写成当前高度：此刻内联样式是空的，浏览器拿不到可插值的起始值，
  // 于是会直接跳到 leave-to 的 0 —— 那就完全没有动画。
  target.style.maxHeight = `${target.scrollHeight}px`
  // 强制读取布局，让上面这行先生效，再由 leave-to 的 0 触发过渡。
  void target.offsetHeight
  target.style.maxHeight = '0'
}

/** 两行的显示顺序：首行是快照选出的协议，另一个跟在后面。 */
const editProtocolRows = computed(() => orderProtocolRows(editPrimaryProtocol.value))

function isEditProtocolEnabled(protocol: WireProtocol) {
  return editForm.value.protocols.includes(protocol)
}

function setEditProtocolEnabled(protocol: WireProtocol, enabled: boolean) {
  editForm.value.protocols = toggleProtocol(editForm.value.protocols, protocol, enabled)
}

/** 按协议读地址。Anthropic 的空值不在这里回退 —— 输入框要如实显示空，占位符负责说明。 */
function editBaseUrlOf(protocol: WireProtocol) {
  return protocol === 'ANTHROPIC' ? editForm.value.anthropicBaseUrl : editForm.value.baseUrl
}

function onEditBaseUrlInput(protocol: WireProtocol, value: string) {
  if (protocol === 'ANTHROPIC') {
    editForm.value.anthropicBaseUrl = value
    // 用户亲手改过，联动立即终止，否则他的输入会被下一次同步覆盖。
    editMirroringAnthropicBaseUrl.value = false
    return
  }
  editForm.value.baseUrl = value
  editForm.value.anthropicBaseUrl = mirrorAnthropicBaseUrl(
    value, editMirroringAnthropicBaseUrl.value, editForm.value.anthropicBaseUrl,
  )
}

/** 进入 OpenAI 地址框时拍下「Anthropic 当前是否为空」，作为本轮编辑的联动依据。 */
function onEditBaseUrlFocus(protocol: WireProtocol) {
  if (protocol === 'OPENAI') {
    editMirroringAnthropicBaseUrl.value = shouldMirrorOnFocus(editForm.value.anthropicBaseUrl)
  }
}

const PROTOCOL_ROW_LABELS: Record<WireProtocol, string> = {
  OPENAI: 'OpenAI 请求Url',
  ANTHROPIC: 'Anthropic 请求Url',
}

const PROTOCOL_ROW_PLACEHOLDERS: Record<WireProtocol, string> = {
  OPENAI: 'https://api.example.com/v1',
  ANTHROPIC: '留空则与 OpenAI 地址相同',
}

/** 端点预览文案；地址为空时退回占位模板，不拼出只剩路径的半成品。 */
function editEndpointHintOf(protocol: WireProtocol) {
  if (protocol === 'ANTHROPIC') {
    return describeEndpoint(
      editForm.value.anthropicBaseUrl || editForm.value.baseUrl, ANTHROPIC_ENDPOINT_SUFFIX,
    ) || `\${anthropic_url}${ANTHROPIC_ENDPOINT_SUFFIX}`
  }
  return describeEndpoint(editForm.value.baseUrl, OPENAI_ENDPOINT_SUFFIX)
    || `\${openai_url}${OPENAI_ENDPOINT_SUFFIX}`
}
const pullingModels = ref(false)

const pullDiffModal = ref<{ visible: boolean } & PullDiff>({
  visible: false,
  entries: [],
  addedCount: 0,
  removedCount: 0,
})

const showAddModal = ref(false)

const providerContextMenuForDisabled = ref({
  visible: false,
  providerKey: '',
  x: 0,
  y: 0,
})
const deleteProviderModal = ref({
  visible: false,
  providerKey: '',
})

const disabledProviderContextOptions = computed(() => [
  { label: '启用', key: 'enable' as const },
  { label: '修改', key: 'edit' as const },
  { label: '删除', key: 'delete' as const, props: { class: 'provider-context-menu__delete' } },
])

const deleteProviderName = computed(() => {
  const key = deleteProviderModal.value.providerKey
  return providerMeta.value[key]?.displayName || key
})

function openDisabledProviderContextMenu(event: MouseEvent, key: string) {
  event.preventDefault()
  providerContextMenuForDisabled.value = {
    visible: true,
    providerKey: key,
    x: event.clientX,
    y: event.clientY,
  }
}

function handleDisabledProviderContextSelect(action: 'enable' | 'edit' | 'delete') {
  const key = providerContextMenuForDisabled.value.providerKey
  providerContextMenuForDisabled.value.visible = false
  if (!key) return

  if (action === 'enable') {
    enableProvider(key)
    return
  }
  if (action === 'edit') {
    openEditProviderModal(key)
    return
  }
  deleteProviderModal.value = { visible: true, providerKey: key }
}

async function confirmRemoveProvider() {
  const key = deleteProviderModal.value.providerKey
  if (!key) return
  deleteProviderModal.value.visible = false
  await removeProvider(key)
}

const providerContextMenu = ref({
  visible: false,
  providerKey: '',
  x: 0,
  y: 0,
})

const providerContextOptions = computed(() => {
  const key = providerContextMenu.value.providerKey
  const options: Array<{ label: string; key: 'edit' | 'disable'; disabled?: boolean }> = []
  if (key) {
    options.push({ label: '修改', key: 'edit' })
  }
  options.push({ label: '停用', key: 'disable' })
  return options
})

function openProviderContextMenu(event: MouseEvent, key: string) {
  event.preventDefault()
  providerContextMenu.value = {
    visible: true,
    providerKey: key,
    x: event.clientX,
    y: event.clientY,
  }
}

async function handleProviderContextSelect(action: 'edit' | 'disable') {
  const key = providerContextMenu.value.providerKey
  providerContextMenu.value.visible = false
  if (!key) return

  if (action === 'edit') {
    openEditProviderModal(key)
    return
  }
  await toggleProvider(key, false)
}

// ==================== API Key 管理 ====================

const showApiKeyModal = ref(false)
const editingApiKeys = ref<ApiKeyEntry[]>([])

/** 构建下拉选项，value 使用 keyUuid（新增未保存项用临时标记） */
const apiKeyOptions = computed(() =>
  editForm.value.apiKeys.map((entry, index) => ({
    label: `${entry.name || '未命名'}: ${displayKey(entry)}`,
    value: entry.keyUuid || newKeyValue(index),
  }))
)

function openApiKeyModal() {
  editingApiKeys.value = editForm.value.apiKeys.map(k => ({ ...k }))
  showApiKeyModal.value = true
}

function addApiKeyEntry() {
  editingApiKeys.value.push({ name: '', apiKey: '' })
}

function removeApiKeyEntry(index: number) {
  editingApiKeys.value.splice(index, 1)
}

function saveApiKeyModal() {
  const valid = keepMeaningfulEntries(editingApiKeys.value)
  editForm.value.apiKeys = valid
  editForm.value.activeKeyUuid = resolveActiveValue(valid, editForm.value.activeKeyUuid)
  showApiKeyModal.value = false
}

function cancelApiKeyModal() {
  showApiKeyModal.value = false
}

// ==================== 供应商编辑 ====================

const showProviderModal = ref(false)
const providerName = ref('')
const providerAdvancedExpanded = ref(false)
const editingProviderKey = ref<string | null>(null)
const providerBaseUrl = ref('')
const providerAnthropicBaseUrl = ref('')
const providerProtocols = ref<WireProtocol[]>([...DEFAULT_NEW_PROVIDER_PROTOCOLS])
const providerUseProxy = ref(false)
const showPresetModal = ref(false)

/**
 * 本轮 OpenAI 地址编辑是否联动 Anthropic 地址。
 *
 * 在获得焦点时一次性拍快照，而不是每次输入时重新判空 —— 后者会让 Anthropic
 * 在同步到第一个字符后就不再为空，于是永远停在一个字母上。
 */
const mirroringAnthropicBaseUrl = ref(false)

/** 端点预览文案；地址为空时退回占位模板，不拼出只剩路径的半成品。 */
const openAiEndpointHint = computed(() =>
  describeEndpoint(providerBaseUrl.value, OPENAI_ENDPOINT_SUFFIX) || `\${openai_url}${OPENAI_ENDPOINT_SUFFIX}`
)
const anthropicEndpointHint = computed(() =>
  describeEndpoint(providerAnthropicBaseUrl.value, ANTHROPIC_ENDPOINT_SUFFIX)
    || `\${anthropic_url}${ANTHROPIC_ENDPOINT_SUFFIX}`
)

function isProtocolEnabled(protocol: WireProtocol) {
  return providerProtocols.value.includes(protocol)
}

function setProtocolEnabled(protocol: WireProtocol, enabled: boolean) {
  providerProtocols.value = toggleProtocol(providerProtocols.value, protocol, enabled)
}

/** 进入 OpenAI 地址输入框：拍下「Anthropic 当前是否为空」作为本轮编辑的联动依据。 */
function onOpenAiBaseUrlFocus() {
  mirroringAnthropicBaseUrl.value = shouldMirrorOnFocus(providerAnthropicBaseUrl.value)
}

function onOpenAiBaseUrlInput(value: string) {
  providerBaseUrl.value = value
  providerAnthropicBaseUrl.value = mirrorAnthropicBaseUrl(
    value, mirroringAnthropicBaseUrl.value, providerAnthropicBaseUrl.value,
  )
}

/** 用户亲自改过 Anthropic 地址，联动立即终止—— 否则他的输入会被下一次同步覆盖。 */
function onAnthropicBaseUrlInput(value: string) {
  providerAnthropicBaseUrl.value = value
  mirroringAnthropicBaseUrl.value = false
}

/** 协议配置的提交载荷。 */
function buildProtocolPayload() {
  return {
    supportedProtocolsJson: protocolsToJson(providerProtocols.value),
    anthropicBaseUrl: providerAnthropicBaseUrl.value.trim(),
  }
}

/** 宽容解析后端回传的 JSON 字段；无法解析时返回 undefined 交由迁移函数兼容。 */
function safeParseJson(text: string | null | undefined): unknown {
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

/**
 * 现有供应商的「路由标识 → 展示名」映射，供重名检测使用。
 *
 * 取自 store 而非 providerMeta：后者是前端本地元数据，可能滞后于数据库。
 */
const existingProviderKeys = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const [key, provider] of Object.entries(providerStore.providers)) {
    map[key] = provider?.displayName || key
  }
  return map
})

/** 当前输入的供应商名称会派生出什么样的路由标识。 */
const providerKeyInfo = computed(() =>
  describeProviderKey(providerName.value, {
    existing: existingProviderKeys.value,
    currentKey: editingProviderKey.value,
  })
)

/** 路由标识提示的说明文案。`ok` 无需额外解释，返回空串。 */
const providerKeyHint = computed(() => {
  const info = providerKeyInfo.value
  switch (info.status) {
    case 'unavailable':
      return '名称需包含至少一个英文字母或数字，否则无法生成路由标识'
    case 'conflict':
      return `已被供应商「${info.conflictWith}」占用，请换一个名称`
    case 'lossy':
      return `名称中的 ${info.droppedChars.join(' ')} 不参与标识生成`
    default:
      return info.renamed ? '改名后 Copilot 中的模型前缀会随之变化，需重新选择模型' : ''
  }
})

/** 选择预设时自动填充名称、地址、请求头、请求体模板和规则。 */
function applyPreset(label: string) {
  const preset = findPreset(label)
  if (preset) {
    const values = toPresetFormValues(preset)
    providerName.value = values.displayName
    providerBaseUrl.value = values.baseUrl
    providerAnthropicBaseUrl.value = values.anthropicBaseUrl
    mirroringAnthropicBaseUrl.value = false
    providerHeaders.value = values.headers
    requestBodyEditorState.value = values.editorState
    providerAdvancedExpanded.value = true
  }
  showPresetModal.value = false
}

/** 清空供应商表单所有内容。 */
function clearProviderForm() {
  providerName.value = ''
  providerBaseUrl.value = ''
  providerAnthropicBaseUrl.value = ''
  providerProtocols.value = [...DEFAULT_NEW_PROVIDER_PROTOCOLS]
  providerUseProxy.value = false
  mirroringAnthropicBaseUrl.value = false
  providerHeaders.value = []
  requestBodyEditorState.value = createProviderDefaultEditorState()
  providerAdvancedExpanded.value = false
}

/** 高级设置 - 请求头覆盖列表 */
const providerHeaders = ref<HeaderEntry[]>([])

/** 请求体映射规则及编辑器预览状态。 */
const requestBodyEditorState = ref<RequestBodyEditorState>(createProviderDefaultEditorState())
const showRequestBodyRuleEditor = ref(false)

function addProviderHeader() {
  providerHeaders.value.push({ key: '', value: '' })
}

function removeProviderHeader(index: number) {
  providerHeaders.value.splice(index, 1)
}

function resetProviderAdvanced() {
  providerAdvancedExpanded.value = false
  providerHeaders.value = []
  requestBodyEditorState.value = createProviderDefaultEditorState()
  providerBaseUrl.value = ''
  providerAnthropicBaseUrl.value = ''
  providerProtocols.value = [...DEFAULT_NEW_PROVIDER_PROTOCOLS]
  providerUseProxy.value = false
  mirroringAnthropicBaseUrl.value = false
  editingProviderKey.value = null
}

/** 打开编辑供应商模态框。 */
function openEditProviderModal(key: string) {
  editingProviderKey.value = key
  const provider = providerStore.providers[key]
  const displayName = provider?.displayName || providerMeta.value[key]?.displayName || key.replace(/-/g, ' ')
  providerName.value = displayName
  providerHeaders.value = []
  requestBodyEditorState.value = createProviderDefaultEditorState()
  providerBaseUrl.value = provider?.baseUrl || ''
  providerAnthropicBaseUrl.value = provider?.anthropicBaseUrl || ''
  providerProtocols.value = normalizeProtocols(provider?.supportedProtocols)
  providerUseProxy.value = provider?.useProxy ?? false
  mirroringAnthropicBaseUrl.value = false
  if (provider) {
    try {
      const headerRules = JSON.parse(provider.requestTransform?.headerRulesJson || '[]')
      if (Array.isArray(headerRules)) {
        providerHeaders.value = headerRules.map((h: any) => ({ key: h.key || '', value: h.value || '' }))
      }
    } catch { /* ignore */ }
    try {
      const saved = provider.requestTransform
      if (saved) {
        // 旧的 bodyTemplateKeysJson / bodyPreviewJson 是 V1 时与规则并列的全局调试样本，
        // 升 V2 时得搬进唯一那个组，否则用户调过的预览请求体会丢。
        requestBodyEditorState.value = {
          rules: migrateRuleSet(JSON.parse(saved.bodyRulesJson), {
            templateKeys: safeParseJson(saved.bodyTemplateKeysJson),
            previewBody: safeParseJson(saved.bodyPreviewJson),
          }),
        }
      }
    } catch {
      requestBodyEditorState.value = createProviderDefaultEditorState()
    }
  }
  showAddModal.value = false
  showProviderModal.value = true
}

/** 构建请求头规则 JSON。 */
function buildHeaderRulesJson(): string {
  const headers = providerHeaders.value.filter(h => h.key.trim())
  return JSON.stringify(headers.map(h => ({ key: h.key.trim(), value: h.value })))
}

/**
 * 构建请求转换配置的提交载荷。
 *
 * `bodyTemplateKeysJson` / `bodyPreviewJson` 已是 legacy 列：真正的预览样本现在跟随每个
 * 规则组存在 `bodyRulesJson` 里。仍然提交它们是因为后端校验还要求非空，
 * 取首组的值保证旧版本回滚时能读到一份有意义的样本而非空对象。
 */
function buildRequestTransformPayload() {
  const ruleSet = requestBodyEditorState.value.rules
  const primary = ruleSet.groups[0]
  return {
    bodyTemplateKeysJson: JSON.stringify(primary?.templateKeys ?? ['base']),
    bodyPreviewJson: JSON.stringify(primary?.previewBody ?? {}),
    bodyRulesJson: JSON.stringify(ruleSet),
  }
}

/** 保存供应商。 */
async function saveProvider() {
  const name = providerName.value.trim()
  if (!name) {
    message.warning('请输入供应商名称')
    return
  }
  // 标识为空或冲突时本地就拦下：后端虽有同源校验，但它对冲突只能回
  // 「该供应商名称已存在」，与用户看到的展示名对不上。
  if (!providerKeyInfo.value.submittable) {
    message.warning(providerKeyHint.value)
    return
  }
  // 一个协议都不勾在后端是合法入参但非法配置：调度器会拒接该供应商的所有调用。
  // 在这里拦下比存进去再去排查「为什么全部请求都失败」便宜得多。
  if (providerProtocols.value.length === 0) {
    message.warning('至少需要启用一个协议')
    return
  }
  try {
    const headerRulesJson = buildHeaderRulesJson()
    const baseUrl = providerBaseUrl.value.trim()
    const requestTransform = buildRequestTransformPayload()
    const protocolPayload = buildProtocolPayload()
    if (editingProviderKey.value) {
      // 编辑模式
      const oldKey = editingProviderKey.value
      // 代理开关随保存一并提交，不再保存完再补一次专项请求 ——
      // 那样两次写入不在同一个请求里，第二次失败会留下半完成状态。
      await providerStore.updateProvider(
        oldKey, name, headerRulesJson, baseUrl, requestTransform, protocolPayload,
        providerUseProxy.value,
      )
      // 更新前端元数据
      const newKey = toProviderKey(name)
      const metaUpdate = { displayName: name, apiUrlPlaceholder: baseUrl || 'https://api.example.com/v1' }
      if (newKey !== oldKey && providerMeta.value[oldKey]) {
        providerMeta.value[newKey] = { ...providerMeta.value[oldKey], ...metaUpdate }
        delete providerMeta.value[oldKey]
      } else {
        providerMeta.value[oldKey] = { ...providerMeta.value[oldKey], ...metaUpdate }
      }
      showProviderModal.value = false
      resetProviderAdvanced()
      message.success(`已修改供应商「${name}」`)
    } else {
      // 新增模式：代理开关与供应商同一次请求建立，不存在「建好了但开关没生效」的中间态。
      const res = await providerStore.addProvider(
        name, headerRulesJson, baseUrl, requestTransform, protocolPayload,
        providerUseProxy.value,
      )
      providerMeta.value[res.providerKey] = {
        displayName: name,
        colorClass: 'accent',
        apiUrlPlaceholder: baseUrl || 'https://api.example.com/v1',
      }
      showProviderModal.value = false
      providerName.value = ''
      resetProviderAdvanced()
      message.success(`已添加供应商「${name}」`)
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error || '操作失败')
  }
}

/** 删除供应商。 */
async function removeProvider(key: string) {
  const displayName = providerMeta.value[key]?.displayName || key
  try {
    await providerStore.deleteProvider(key)
    delete providerMeta.value[key]
    if (editingKey.value === key) {
      closeEditPanel()
    }
    message.success(`已删除供应商「${displayName}」`)
  } catch {
    message.error('删除失败')
  }
}

const enabledProviderKeys = computed(() => {
  const metaKeys = Object.keys(providerMeta.value)
  // provider_config 中可能存在但 meta 中没有的（如数据库残留）
  const storeKeys = Object.keys(providerStore.providers)
  const allKeys = [...new Set([...metaKeys, ...storeKeys])]
  return allKeys.filter(k => providerStore.providers[k]?.enabled)
})

const disabledProviderKeys = computed(() => {
  const metaKeys = Object.keys(providerMeta.value)
  const storeKeys = Object.keys(providerStore.providers)
  const allKeys = [...new Set([...metaKeys, ...storeKeys])]
  return allKeys.filter(k => !providerStore.providers[k]?.enabled)
})

async function enableProvider(key: string) {
  await providerStore.toggleProvider(key, true)
  message.success(`${providerMeta.value[key]?.displayName || key} 已启用`)
  showAddModal.value = false
}

onMounted(async () => {
  await providerStore.fetchAll()
  // 将数据库中尚未配置展示元数据的供应商注入 providerMeta
  for (const key of Object.keys(providerStore.providers)) {
    if (!providerMeta.value[key]) {
      const provider = providerStore.providers[key]
      const displayName = provider?.displayName || key
        .replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
      const actualBaseUrl = provider?.baseUrl || ''
      providerMeta.value[key] = {
        displayName,
          colorClass: 'accent',
        apiUrlPlaceholder: actualBaseUrl || 'https://api.example.com/v1',
      }
    }
  }
})

function openEditPanel(key: string) {
  editingKey.value = key
  const p = providerStore.providers[key]
  if (p) {
    // 后端返回脱敏后的 apiKeys（含 keyUuid / masked / active）
    const apiKeys: ApiKeyEntry[] = Array.isArray(p.apiKeys)
      ? p.apiKeys.map(k => ({ keyUuid: k.keyUuid, name: k.name, masked: k.masked, active: k.active }))
      : []
    const activeEntry = apiKeys.find(k => k.active)
    editForm.value = {
      baseUrl: p.baseUrl || providerMeta.value[key]?.apiUrlPlaceholder || '',
      anthropicBaseUrl: p.anthropicBaseUrl || '',
      protocols: normalizeProtocols(p.supportedProtocols),
      apiKeys,
      activeKeyUuid: activeEntry?.keyUuid || apiKeys[0]?.keyUuid || '',
      models: p.models.map(toEditableModel),
    }
    editMirroringAnthropicBaseUrl.value = false
    // 首行协议与折叠状态都以「打开时」为准：之后勾选变化不重排，避免输入框跳位。
    editPrimaryProtocol.value = resolvePrimaryProtocol(editForm.value.protocols)
    editUrlsExpanded.value = false
  }
}

function closeEditPanel() {
  editingKey.value = null
}

async function saveEditPanel() {
  if (!editingKey.value) return
  const key = editingKey.value
  // 与弹窗同一道拦：空集在后端是合法入参但非法配置，存进去会让该供应商的全部调用被拒。
  if (editForm.value.protocols.length === 0) {
    message.warning('至少需要启用一个协议')
    return
  }
  const params: Record<string, string> = {
    baseUrl: editForm.value.baseUrl,
    anthropicBaseUrl: editForm.value.anthropicBaseUrl.trim(),
    supportedProtocolsJson: protocolsToJson(editForm.value.protocols),
    apiKeys: JSON.stringify(toApiKeyPayloads(editForm.value.apiKeys)),
    activeKeyUuid: isNewKeyValue(editForm.value.activeKeyUuid) ? '' : editForm.value.activeKeyUuid,
    ...toModelFormParams(editForm.value.models),
  }
  // 激活项是尚未落库的新增条目时，activeKeyUuid 被清成空串（它此刻没有 keyUuid）。
  // 用它在提交数组中的下标兜底表达激活意图，后端按下标命中；否则会回退到第一条旧 Key。
  const activeKeyIndex = resolveActiveKeyIndex(editForm.value.activeKeyUuid)
  if (activeKeyIndex !== null) {
    params.activeKeyIndex = String(activeKeyIndex)
  }
  try {
    await providerStore.saveProviderConfig(key, params)
    // 保存后从后端重新拉取，确保拿到最新的 keyUuid 与脱敏值
    await providerStore.fetchAll()
    message.success('保存成功')
    setTimeout(() => closeEditPanel(), 600)
  } catch {
    message.error('保存失败')
  }
}

async function toggleProvider(key: string, val: boolean) {
  await providerStore.toggleProvider(key, val)
  if (val) {
    message.success(`${providerMeta.value[key]?.displayName || key} 已启用`)
  } else {
    message.info(`${providerMeta.value[key]?.displayName || key} 已禁用`)
  }
}

async function pullModels() {
  if (!editingKey.value) return
  const providerKey = editingKey.value

  const credential = resolvePullCredential(editForm.value.apiKeys, editForm.value.activeKeyUuid)
  if (!credential) {
    message.warning('拉取模型需要 API Key。请在"管理 API Key"中新增一条 Key 后再拉取。')
    return
  }

  // 拉取走哪条线路由启用状态决定，不让用户选：两个协议的模型列表端点路径完全相同
  // （都是 GET /v1/models），无法从响应判断上游以哪种协议作答，所以「选线路」没有参考依据。
  const target = resolveModelPullTarget(
    editForm.value.protocols, editForm.value.baseUrl, editForm.value.anthropicBaseUrl,
  )
  if (!target) {
    message.warning('请先启用至少一个协议再拉取模型')
    return
  }

  pullingModels.value = true
  try {
    const resolvedBaseUrl = target.baseUrl || providerMeta.value[providerKey]?.apiUrlPlaceholder || ''
    const payload: Record<string, string> = { baseUrl: resolvedBaseUrl, protocol: target.protocol }
    if (credential.apiKey) {
      payload.apiKey = credential.apiKey
    } else if (credential.keyUuid) {
      payload.keyUuid = credential.keyUuid
    }
    const responsePayload = await providerStore.pullProviderModels(providerKey, payload)
    const modelNames = extractModelNames(responsePayload)
    if (modelNames.length === 0) {
      message.warning('未拉取到模型')
      return
    }

    pullDiffModal.value = {
      visible: true,
      ...buildPullDiff(editForm.value.models, modelNames),
    }
  } catch (error: any) {
    message.error(resolvePullModelsErrorMessage(error))
  } finally {
    pullingModels.value = false
  }
}

function applyPulledModels() {
  const { addedCount, removedCount } = pullDiffModal.value
  editForm.value.models = applyPullDiff(pullDiffModal.value)
  pullDiffModal.value.visible = false
  message.success(`已应用：新增 ${addedCount} 个，移除 ${removedCount} 个`)
}

function cancelPulledModels() {
  pullDiffModal.value.visible = false
}

function revertPullDiff(index: number) {
  pullDiffModal.value = {
    visible: pullDiffModal.value.visible,
    ...revertDiffEntry(pullDiffModal.value, index),
  }
}

function addModel() {
  editForm.value.models.push(buildEditableModel())
}

function removeModel(index: number) {
  editForm.value.models.splice(index, 1)
}
</script>

<template>
  <div class="settings-page">
    <!-- 供应商配置 -->
    <n-card title="供应商配置" :bordered="true">
      <template #header-extra>
        <n-button text size="tiny" @click="showAddModal = true" class="add-provider-btn">
          <template #icon>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
              stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          </template>
          添加
        </n-button>
      </template>
      <div class="provider-grid">
        <div v-for="key in enabledProviderKeys" :key="key" class="provider-card"
          @click="openEditPanel(key)" @contextmenu="openProviderContextMenu($event, key)">
          <div class="provider-card-top" :class="providerMeta[key]?.colorClass || 'accent'"></div>
          <div class="provider-card-header">
            <span class="provider-card-name">{{ providerMeta[key]?.displayName || key }}</span>
            <n-switch :value="providerStore.providers[key]?.enabled" @click.stop
              @update:value="(val) => toggleProvider(key, val)" />
          </div>
          <div class="provider-card-status">
            <n-tag v-if="providerStore.providers[key]?.enabled" type="success" size="small"
              :bordered="false">已启用</n-tag>
            <n-tag v-else size="small" :bordered="false">已禁用</n-tag>
          </div>
          <div class="provider-card-models">
            <span v-if="providerStore.providers[key]?.models?.length">
              {{providerStore.providers[key].models.filter(m => m.enabled).length}} 个模型
            </span>
            <span v-else class="text-muted">未配置</span>
          </div>
        </div>
      </div>
    </n-card>

    <n-dropdown
      placement="bottom-start"
      trigger="manual"
      :show="providerContextMenu.visible"
      :x="providerContextMenu.x"
      :y="providerContextMenu.y"
      :options="providerContextOptions"
      @select="handleProviderContextSelect"
      @clickoutside="providerContextMenu.visible = false"
    />

    <!-- 添加供应商模态框 -->
    <n-modal v-model:show="showAddModal" preset="card" title="添加供应商" :style="{ maxWidth: '480px' }" closable
      :mask-closable="true">
      <div v-if="disabledProviderKeys.length === 0" class="add-modal-empty">
        所有供应商已启用
      </div>
      <div class="add-modal-grid">
        <div v-for="key in disabledProviderKeys" :key="key" class="add-modal-card"
          @click="enableProvider(key)"
          @contextmenu="openDisabledProviderContextMenu($event, key)">
          <div class="add-modal-card-top" :class="providerMeta[key]?.colorClass || 'accent'"></div>
          <div class="add-modal-card-name">{{ providerMeta[key]?.displayName || key }}</div>
          <div class="add-modal-card-desc">{{ providerMeta[key]?.apiUrlPlaceholder || '' }}</div>
        </div>
        <!-- 添加供应商入口 — 始终显示，不依赖 disabledProviderKeys -->
        <div class="add-modal-card add-modal-card--new" @click="showAddModal = false; showProviderModal = true">
          <div class="add-modal-card-top accent"></div>
          <div class="add-modal-card-name">添加供应商</div>
          <div class="add-modal-card-desc">标准 OpenAI 兼容接口</div>
        </div>
      </div>
    </n-modal>

    <n-dropdown
      placement="bottom-start"
      trigger="manual"
      :show="providerContextMenuForDisabled.visible"
      :x="providerContextMenuForDisabled.x"
      :y="providerContextMenuForDisabled.y"
      :options="disabledProviderContextOptions"
      @select="handleDisabledProviderContextSelect"
      @clickoutside="providerContextMenuForDisabled.visible = false"
    />

    <n-modal v-model:show="deleteProviderModal.visible" preset="dialog" type="error"
      title="确认删除供应商" positive-text="删除" negative-text="取消"
      @positive-click="confirmRemoveProvider">
      删除供应商「{{ deleteProviderName }}」后，其 API Key、模型和请求转换配置将无法恢复。确定继续吗？
    </n-modal>

    <!-- 供应商名称输入模态框 -->
    <n-modal v-model:show="showProviderModal" preset="card" :title="editingProviderKey ? '修改供应商' : '添加供应商'"
      :style="{ maxWidth: '480px' }" closable :mask-closable="true"
      @update:show="(val: boolean) => { if (!val) resetProviderAdvanced() }">
      <div class="field-group">
        <label class="field-label">供应商名称</label>
        <div class="provider-name-row">
          <n-input v-model:value="providerName" placeholder="输入供应商名称" />
          <n-button size="small" @click="clearProviderForm">清空</n-button>
          <n-button size="small" @click="showPresetModal = true">预设</n-button>
        </div>

        <!-- 路由标识预览：让用户在输入时就知道名称会被转换成什么 -->
        <div v-if="providerKeyInfo.status !== 'blank'" class="provider-key-preview"
          :class="`provider-key-preview--${providerKeyInfo.status}`">
          <div class="provider-key-preview-row">
            <span class="provider-key-preview-label">路由标识</span>
            <code v-if="providerKeyInfo.renamed" class="provider-key-preview-value">
              <span class="provider-key-preview-old">{{ providerKeyInfo.previousKey }}</span>
              <span class="provider-key-preview-arrow">→</span>{{ providerKeyInfo.key }}
            </code>
            <code v-else-if="providerKeyInfo.key" class="provider-key-preview-value">{{ providerKeyInfo.key }}</code>
            <span v-else class="provider-key-preview-value provider-key-preview-value--empty">无法生成</span>
          </div>
          <div v-if="providerKeyHint" class="provider-key-preview-hint">{{ providerKeyHint }}</div>
        </div>
      </div>

      <!-- 高级设置折叠区域 -->
      <div class="advanced-toggle" @click="providerAdvancedExpanded = !providerAdvancedExpanded">
        <span class="advanced-toggle-label">高级设置</span>
        <svg class="advanced-toggle-arrow" :class="{ 'advanced-toggle-arrow--expanded': providerAdvancedExpanded }"
          width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </div>

      <div v-if="providerAdvancedExpanded" class="advanced-panel">
        <!-- OpenAI 请求 Url -->
        <div class="advanced-section">
          <div class="advanced-section-header">
            <span class="advanced-section-title">OpenAI 请求Url</span>
            <span class="endpoint-hint" :title="openAiEndpointHint">{{ openAiEndpointHint }}</span>
          </div>
          <div class="protocol-url-row">
            <n-checkbox :checked="isProtocolEnabled('OPENAI')"
              :title="`启用 ${WIRE_PROTOCOL_LABELS.OPENAI} 协议`"
              @update:checked="setProtocolEnabled('OPENAI', $event)" />
            <n-input :value="providerBaseUrl" placeholder="https://api.example.com/v1"
              @update:value="onOpenAiBaseUrlInput" @focus="onOpenAiBaseUrlFocus" />
          </div>
        </div>

        <!-- Anthropic 请求 Url -->
        <div class="advanced-section">
          <div class="advanced-section-header">
            <span class="advanced-section-title">Anthropic 请求Url</span>
            <span class="endpoint-hint" :title="anthropicEndpointHint">{{ anthropicEndpointHint }}</span>
          </div>
          <div class="protocol-url-row">
            <n-checkbox :checked="isProtocolEnabled('ANTHROPIC')"
              :title="`启用 ${WIRE_PROTOCOL_LABELS.ANTHROPIC} 协议`"
              @update:checked="setProtocolEnabled('ANTHROPIC', $event)" />
            <n-input :value="providerAnthropicBaseUrl" placeholder="留空则与 OpenAI 地址相同"
              @update:value="onAnthropicBaseUrlInput" />
          </div>
        </div>

        <!-- 请求头覆盖 -->
        <div class="advanced-section">
          <div class="advanced-section-header">
            <span class="advanced-section-title">额外覆写或修剪请求头</span>
            <n-button text size="tiny" class="advanced-add-btn" @click="addProviderHeader">
              <template #icon>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="12" y1="5" x2="12" y2="19" />
                  <line x1="5" y1="12" x2="19" y2="12" />
                </svg>
              </template>
              新增
            </n-button>
          </div>
          <div v-if="providerHeaders.length === 0" class="advanced-empty">暂无请求头</div>
          <div v-for="(header, idx) in providerHeaders" :key="idx" class="advanced-row">
            <n-input v-model:value="header.key" placeholder="Header 名称" class="advanced-input-key" />
            <n-input v-model:value="header.value" placeholder="值（/del/ 表示删除）" class="advanced-input-value" />
            <button class="advanced-delete-btn" title="删除" @click="removeProviderHeader(idx)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </div>
        </div>

        <!-- 请求体映射规则 -->
        <div class="advanced-section">
          <div class="advanced-section-header">
            <span class="advanced-section-title">请求体映射规则</span>
            <n-button text size="tiny" class="advanced-add-btn" @click="showRequestBodyRuleEditor = true">
              配置规则
            </n-button>
          </div>
          <div class="advanced-empty" style="cursor: pointer;" @click="showRequestBodyRuleEditor = true">
            已配置 {{ requestBodyEditorState.rules.groups.length }} 个规则组、{{ countRules(requestBodyEditorState.rules) }} 条规则
            <span class="request-body-rules-hint">（保存后按线路作用于实际请求）</span>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="provider-modal-footer">
          <!-- 两种模式都显示：新增时也得能当场定代理，否则只能先存再进来改一次。 -->
          <n-checkbox v-model:checked="providerUseProxy" class="provider-proxy-checkbox">
            启用代理
          </n-checkbox>
          <div class="provider-modal-footer-actions">
            <n-button @click="showProviderModal = false; resetProviderAdvanced()">取消</n-button>
            <n-button type="primary" @click="saveProvider">{{ editingProviderKey ? '应用' : '添加' }}</n-button>
          </div>
        </div>
      </template>
    </n-modal>

    <!-- 请求体规则编辑器（二级模态框） -->
    <RequestBodyRuleEditor
      v-model:show="showRequestBodyRuleEditor"
      :model-value="requestBodyEditorState"
      @apply="requestBodyEditorState = $event"
    />

    <!-- 预设供应商选择模态框 -->
    <n-modal v-model:show="showPresetModal" preset="card" title="选择预设供应商"
      :style="{ maxWidth: '640px' }" closable :mask-closable="true">
      <div class="preset-columns">
        <n-card class="preset-card" title="官方供应商" :bordered="true" size="small" content-scrollable>
          <div v-for="p in officialPresets" :key="p.label"
            class="preset-item" @click="applyPreset(p.label)">{{ p.label }}</div>
        </n-card>
        <n-card class="preset-card" title="整合商" :bordered="true" size="small" content-scrollable>
          <div v-for="p in aggregatorPresets" :key="p.label"
            class="preset-item" @click="applyPreset(p.label)">{{ p.label }}</div>
        </n-card>
        <n-card class="preset-card" title="中转站" :bordered="true" size="small" content-scrollable>
          <div v-for="p in relayPresets" :key="p.label"
            class="preset-item" @click="applyPreset(p.label)">{{ p.label }}</div>
        </n-card>
      </div>
    </n-modal>

    <!-- 拉取模型差异对比模态框 -->
    <n-modal :show="pullDiffModal.visible" preset="card" title="模型变更预览"
      :style="{ maxWidth: '560px' }" closable :mask-closable="false"
      @update:show="(val: boolean) => { if (!val) cancelPulledModels() }">
      <div class="pull-diff-summary">
        <span v-if="pullDiffModal.addedCount" class="pull-diff-badge pull-diff-badge--added">
          +{{ pullDiffModal.addedCount }} 新增
        </span>
        <span v-if="pullDiffModal.removedCount" class="pull-diff-badge pull-diff-badge--removed">
          -{{ pullDiffModal.removedCount }} 移除
        </span>
        <span v-if="!pullDiffModal.addedCount && !pullDiffModal.removedCount" class="pull-diff-badge">
          无变更
        </span>
      </div>
      <div class="pull-diff-list">
        <div v-for="(entry, idx) in pullDiffModal.entries" :key="entry.modelName"
          class="pull-diff-item" :class="`pull-diff-item--${entry.status}`">
          <span class="pull-diff-icon">
            <template v-if="entry.status === 'added'">＋</template>
            <template v-else-if="entry.status === 'removed'">－</template>
            <template v-else>＝</template>
          </span>
          <span class="pull-diff-name">{{ entry.modelName }}</span>
          <button v-if="entry.status !== 'unchanged'" class="pull-diff-revert" title="撤销此变更"
            @click.stop="revertPullDiff(idx)">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="1 4 1 10 7 10" />
              <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
            </svg>
          </button>
        </div>
      </div>
      <template #footer>
        <div class="pull-diff-footer">
          <n-button @click="cancelPulledModels">取消</n-button>
          <n-button type="primary" @click="applyPulledModels"
            :disabled="!pullDiffHasChanges(pullDiffModal)">
            应用
          </n-button>
        </div>
      </template>
    </n-modal>

    <!-- 侧滑面板 -->
    <n-drawer :show="!!editingKey" :width="drawerWidth" placement="right"
      @update:show="(val: boolean) => { if (!val) closeEditPanel() }" @mask-click="closeEditPanel" @esc="closeEditPanel"
      class="edit-drawer">
      <n-drawer-content :title="editingKey ? providerMeta[editingKey]?.displayName : ''" closable
        @close="closeEditPanel">
        <!--
          两个协议地址共处一个容器：左侧是地址行，右侧是展开控件。
          折叠时只显示首行（由打开时的启用状态决定是谁），把纵向空间让给模型列表。
        -->
        <div class="field-group protocol-urls">
          <div class="protocol-urls__rows">
            <!--
              首行与次行显式写出而非用 v-for：Transition 只接受单个子元素，
              而只有次行参与折叠动画。两者内容结构相同但仅此两处，
              重复的代价小于为了消重再引入一层组件与 props 传递。
            -->
            <div class="protocol-urls__row">
              <div class="field-label-row">
                <label class="field-label">{{ PROTOCOL_ROW_LABELS[editProtocolRows[0]] }}</label>
                <span class="endpoint-hint" :title="editEndpointHintOf(editProtocolRows[0])">
                  {{ editEndpointHintOf(editProtocolRows[0]) }}
                </span>
              </div>
              <div class="protocol-url-row">
                <n-input :value="editBaseUrlOf(editProtocolRows[0])"
                  :placeholder="PROTOCOL_ROW_PLACEHOLDERS[editProtocolRows[0]]"
                  @update:value="(val: string) => onEditBaseUrlInput(editProtocolRows[0], val)"
                  @focus="onEditBaseUrlFocus(editProtocolRows[0])" />
                <n-checkbox :checked="isEditProtocolEnabled(editProtocolRows[0])"
                  @update:checked="setEditProtocolEnabled(editProtocolRows[0], $event)">启用</n-checkbox>
              </div>
            </div>
            <!--
              次行外面多一层 __collapse：grid-template-rows 过渡要求过渡元素自身是
              grid 容器、且内容位于单个可裁剪的子元素中。若直接把 __row 作为过渡元素，
              它的两个子 div（标签行、输入行）会各占一个轨道，收缩时只有第一个轨道在动。
            -->
            <Transition name="protocol-url-slide"
              @enter="onProtocolRowEnter" @after-enter="onProtocolRowAfterEnter"
              @leave="onProtocolRowLeave">
              <div v-if="editUrlsExpanded" class="protocol-urls__collapse">
                <div class="protocol-urls__row">
                  <div class="field-label-row">
                    <label class="field-label">{{ PROTOCOL_ROW_LABELS[editProtocolRows[1]] }}</label>
                    <span class="endpoint-hint" :title="editEndpointHintOf(editProtocolRows[1])">
                      {{ editEndpointHintOf(editProtocolRows[1]) }}
                    </span>
                  </div>
                  <div class="protocol-url-row">
                    <n-input :value="editBaseUrlOf(editProtocolRows[1])"
                      :placeholder="PROTOCOL_ROW_PLACEHOLDERS[editProtocolRows[1]]"
                      @update:value="(val: string) => onEditBaseUrlInput(editProtocolRows[1], val)"
                      @focus="onEditBaseUrlFocus(editProtocolRows[1])" />
                    <n-checkbox :checked="isEditProtocolEnabled(editProtocolRows[1])"
                      @update:checked="setEditProtocolEnabled(editProtocolRows[1], $event)">启用</n-checkbox>
                  </div>
                </div>
              </div>
            </Transition>
          </div>
          <button type="button" class="protocol-urls__toggle"
            :class="{ 'protocol-urls__toggle--expanded': editUrlsExpanded }"
            :title="editUrlsExpanded ? '收起另一个协议地址' : '展开另一个协议地址'"
            :aria-expanded="editUrlsExpanded"
            @click="editUrlsExpanded = !editUrlsExpanded">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </button>
        </div>
        <div class="field-group">
          <label class="field-label">API Key</label>
          <div style="display: flex; gap: 8px; align-items: center;">
            <n-select
              v-model:value="editForm.activeKeyUuid"
              :options="apiKeyOptions"
              :placeholder="editForm.apiKeys.length ? '选择 API Key' : '暂无 API Key，请点击管理添加'"
              style="flex: 1;"
              :disabled="editForm.apiKeys.length === 0"
            />
            <n-button @click="openApiKeyModal" size="small">管理</n-button>
          </div>
        </div>

        <ProviderModelsSection v-model:models="editForm.models" :pulling-models="pullingModels"
          :compact="windowWidth <= DRAWER_MIN_WIDTH"
          @pull-models="pullModels" @add-model="addModel" @remove-model="removeModel" />

        <template #footer>
          <n-button type="primary" block @click="saveEditPanel">保存</n-button>
        </template>
      </n-drawer-content>
    </n-drawer>

    <!-- API Key 管理模态框 -->
    <n-modal v-model:show="showApiKeyModal" preset="card" title="管理 API Key" style="width: 600px; max-width: 90vw;">
      <div style="display: flex; flex-direction: column; gap: 12px;">
        <div v-for="(entry, index) in editingApiKeys" :key="index"
          style="display: flex; gap: 8px; align-items: center;">
          <n-input v-model:value="entry.name" placeholder="名称" style="flex: 0 0 120px;" />
          <n-input v-model:value="entry.apiKey" type="password"
            :placeholder="entry.masked ? `已保存：${entry.masked}（留空不修改）` : 'API Key'"
            style="flex: 1;" />
          <n-button size="small" quaternary type="error" @click="removeApiKeyEntry(index)"
            style="flex-shrink: 0;">删除</n-button>
        </div>
        <div v-if="editingApiKeys.length === 0" style="color: #999; text-align: center; padding: 16px 0;">
          暂无 API Key，点击下方"新增"按钮添加
        </div>
      </div>
      <template #action>
        <div style="display: flex; justify-content: space-between; width: 100%;">
          <n-button @click="addApiKeyEntry">新增</n-button>
          <div style="display: flex; gap: 8px;">
            <n-button @click="cancelApiKeyModal">取消</n-button>
            <n-button type="primary" @click="saveApiKeyModal">保存</n-button>
          </div>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.field-group {
  margin-bottom: $space-md;
}

.field-label {
  display: block;
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: $text-muted;
}

/**
 * 两个协议地址的外层容器：左侧地址行，右侧展开控件。
 *
 * 展开按钮垂直居中于**整个容器**而非首行，因为它控制的是容器的展开状态；
 * 若钉在首行，展开后它会停在上方，看起来像只属于第一行。
 */
.protocol-urls {
  // stretch 而非 center：按钮要纵向撑满容器，居中会让它只占内容高度。
  display: flex;
  align-items: stretch;
  gap: $space-sm;
}

/**
 * 行间距由次行的 margin 而非容器的 gap 提供。
 *
 * gap 不参与过渡：次行被移除的那一帧，那 8px 会瞬间消失，于是平滑的高度动画末尾
 * 总带一下突跳。改成 margin 后它能和 max-height 一起被过渡掉。
 */
.protocol-urls__rows {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.protocol-urls__row {
  min-width: 0;
}

/**
 * 次行的折叠包装层。
 *
 * 间距落在这一层而非行本身：它是被过渡的那个元素，`margin-top` 只有挂在这里
 * 才能与高度一起被插值掉。
 */
.protocol-urls__collapse {
  min-width: 0;
  margin-top: $space-sm;
}

/**
 * 展开/收起控件：纵向竖长条，高度由容器（左侧地址行）决定。
 *
 * 用原生 button 而非 n-button：它只是一个箭头，n-button 的内边距与最小宽度会让它
 * 在这个位置显得过重，而这里要的是最小横向占用。
 *
 * <p>高度靠 `align-items: stretch` 由父容器撑开，不写死数值 —— 展开后左侧多一行，
 * 写死的高度会与它脱节，而这个控件的语义正是「作用于整个容器」。
 */
.protocol-urls__toggle {
  flex: 0 0 auto;
  width: 20px;
  align-self: stretch;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid $border;
  border-radius: 4px;
  background: transparent;
  color: $text-muted;
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease, background 0.15s ease;

  &:hover {
    color: $accent;
    border-color: $accent;
    background: $accent-light;
  }

  svg {
    transition: transform 0.25s ease;
  }

  &--expanded svg {
    transform: rotate(180deg);
  }
}

/**
 * 次行的滑动淡入 / 淡出。
 *
 * <h2>为何高度由 JS 钩子给而不写在 CSS 里</h2>
 * 两种纯 CSS 写法都测出了可见的不流畅：
 * <ul>
 *   <li>`max-height` 的目标值只能猜。实测行高 56px，写 80px 时收起的前 24px
 *       内容并未被裁剪 —— 那一段是纯空转，元素一动不动，真正的收缩挤在后段，
 *       观感是「先停一下再突然收起」。</li>
 *   <li>`grid-template-rows: 1fr → 0fr` 不需猜值，但 `fr` 是比例单位，插值并非线性：
 *       实测 250ms 的过渡在约 75ms 内就走完了绝大部分路程，变成「一下就没了」。</li>
 * </ul>
 * 钩子里读 `scrollHeight` 再写回 `max-height`，两个问题同时消失：值是量出来的不用猜，
 * 而 `px` 的插值是线性的。
 *
 * <p>透明度与位移仍要一起过渡：只做高度像是被挤出来的、没有出现感；
 * 只做透明度则会让下方的模型列表在展开瞬间被整块推下去，位移是突变的。
 *
 * <p>行间距的 `margin-top` 必须同步过渡 —— 它若在最后一帧瞬间消失，
 * 平滑的高度动画末尾仍会带一下突跳。
 */
.protocol-url-slide-enter-active,
.protocol-url-slide-leave-active {
  overflow: hidden;
  transition: max-height 0.25s ease, opacity 0.2s ease, transform 0.25s ease,
    margin-top 0.25s ease;
}

.protocol-url-slide-enter-from,
.protocol-url-slide-leave-to {
  max-height: 0;
  margin-top: 0;
  opacity: 0;
  transform: translateY(-6px);
}

/**
 * 标签与端点预览同一行。
 *
 * 这里的标签不能沿用 `.field-label` 的 `text-transform: uppercase` —— 那会把
 * 「OpenAI 请求Url」显示成「OPENAI 请求URL」，而协议名的大小写是它的正式写法。
 */
.field-label-row {
  display: flex;
  align-items: baseline;
  gap: $space-sm;
  margin-bottom: 4px;

  .field-label {
    flex: 0 0 auto;
    margin-bottom: 0;
    text-transform: none;
    letter-spacing: 0.05em;
  }
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: $space-md;
}

.provider-card {
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius-lg;
  padding: $space-md;
  cursor: pointer;
  transition: all 0.25s ease;
  position: relative;
  overflow: hidden;

  &:hover {
    transform: translateY(-2px);
    box-shadow: $shadow-md;
  }
}

.provider-card-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;

  &.accent {
    background: $accent;
  }

  &.blue {
    background: $blue;
  }

  &.success {
    background: $success;
  }

  &.warning {
    background: $warning;
  }

}

.provider-card-delete {
  position: absolute;
  top: 8px;
  right: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 999px;
  background: rgba(184, 74, 74, 0.08);
  color: $text-muted;
  cursor: pointer;
  transition: all 0.2s ease;
  z-index: 2;

  &:hover {
    color: $danger;
    background: rgba(184, 74, 74, 0.15);
  }
}

.provider-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $space-sm;
}

.provider-card-name {
  font-family: $font-display;
  font-size: 17px;
  font-weight: 600;
  color: $text-primary;
}

.provider-card-status {
  margin-bottom: $space-sm;
}

.provider-card-models {
  font-family: $font-body;
  font-size: 13px;
  color: $text-body;
}

.text-muted {
  color: $text-muted;
}

.add-provider-btn {
  flex-shrink: 0;
  color: $text-muted !important;

  &:hover {
    color: $accent !important;
  }
}

/* ── 添加供应商模态框 ── */
.add-modal-empty {
  text-align: center;
  padding: $space-xl 0;
  font-family: $font-body;
  font-size: 14px;
  color: $text-muted;
}

.add-modal-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $space-md;
}

.add-modal-card {
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius-lg;
  padding: $space-md;
  cursor: pointer;
  transition: all 0.25s ease;
  position: relative;
  overflow: hidden;

  &:hover {
    transform: translateY(-2px);
    box-shadow: $shadow-md;
  }
}

.add-modal-card-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;

  &.accent {
    background: $accent;
  }

  &.blue {
    background: $blue;
  }

  &.success {
    background: $success;
  }

  &.warning {
    background: $warning;
  }

}

.add-modal-card-name {
  font-family: $font-display;
  font-size: 16px;
  font-weight: 600;
  color: $text-primary;
  margin-bottom: 4px;
}

.add-modal-card-desc {
  font-family: $font-mono;
  font-size: 11px;
  color: $text-muted;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.provider-context-menu__delete) {
  color: $danger !important;
}

.add-modal-card--new {
  border: 1px dashed $border;
  background: rgba($accent, 0.03);

  &:hover {
    border-color: $accent;
    background: rgba($accent, 0.08);
  }
}

.provider-modal-footer {
  display: flex;
  align-items: center;
  gap: $space-sm;
}

.provider-proxy-checkbox {
  margin-right: auto;
}

/**
 * 按钮靠右不得依赖左侧元素的 margin-right: auto。
 *
 * 曾经只有复选框那一个撑开器，而它带 v-if —— 新增模式下不渲染，按钮就滑到了左边。
 * 自己声明 margin-left: auto 后，无论左侧有没有东西都靠右。
 */
.provider-modal-footer-actions {
  display: flex;
  gap: $space-sm;
  margin-left: auto;
}

.provider-name-row {
  display: flex;
  gap: $space-sm;
  align-items: center;
}

/* 路由标识预览 —— 把「展示名 → provider-key」的转换结果摊开给用户看 */
.provider-key-preview {
  margin-top: $space-sm;
  padding: $space-sm $space-sm + 2px;
  border-radius: $radius;
  border: 1px solid $border;
  background: $border-light;
  border-left: 2px solid $text-muted;
}

.provider-key-preview-row {
  display: flex;
  align-items: baseline;
  gap: $space-sm;
}

.provider-key-preview-label {
  flex-shrink: 0;
  font-family: $font-mono;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: $text-muted;
}

.provider-key-preview-value {
  font-family: $font-mono;
  font-size: 12px;
  color: $text-primary;
  word-break: break-all;
}

.provider-key-preview-value--empty {
  color: $danger;
}

/* 改名时并列旧标识，划掉表示即将失效 */
.provider-key-preview-old {
  color: $text-muted;
  text-decoration: line-through;
}

.provider-key-preview-arrow {
  margin: 0 $space-xs;
  color: $text-muted;
}

.provider-key-preview-hint {
  margin-top: $space-xs;
  font-size: 12px;
  line-height: 1.5;
  color: $text-body;
}

.provider-key-preview--lossy {
  border-left-color: $warning;

  .provider-key-preview-hint {
    color: $warning;
  }
}

.provider-key-preview--unavailable,
.provider-key-preview--conflict {
  border-left-color: $danger;

  .provider-key-preview-hint {
    color: $danger;
  }
}

/* 未改名的正常态：仅陈述事实，不需要强调 */
.provider-key-preview--ok {
  border-left-color: $success;
}

/* ── 预设供应商列表 ── */
.preset-columns {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: $space-md;
}

.preset-card {
  min-height: 0;
  max-height: 320px;
}

.preset-item {
  padding: 8px $space-md;
  border-radius: $radius;
  font-family: $font-display;
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    background: $accent-light;
    color: $accent;
  }
}

/* ── 高级设置 ── */
.advanced-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $space-sm 0;
  margin-top: $space-xs;
  cursor: pointer;
  user-select: none;
  border-top: 1px solid $border-light;
}

.advanced-toggle-label {
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: $text-muted;
  transition: color 0.2s ease;

  .advanced-toggle:hover & {
    color: $accent;
  }
}

.advanced-toggle-arrow {
  color: $text-muted;
  transition: transform 0.25s ease, color 0.2s ease;

  &--expanded {
    transform: rotate(180deg);
  }

  .advanced-toggle:hover & {
    color: $accent;
  }
}

.advanced-panel {
  padding-top: $space-sm;
}

.advanced-section {
  margin-bottom: $space-md;
}

.advanced-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $space-sm;
}

.advanced-section-title {
  font-family: $font-body;
  font-size: 13px;
  font-weight: 600;
  color: $text-body;
  // 标题不参与压缩：地址一长，该被截断的是右侧提示而不是「OpenAI 请求Url」。
  flex: 0 0 auto;
}

/**
 * 端点预览。占据标题右侧的剩余空间并单行截断 —— 完整地址由 title 属性提供，
 * 因为它可以很长，换行会把整个区块的高度撑起来。
 */
.endpoint-hint {
  flex: 1 1 auto;
  min-width: 0;
  margin-left: $space-sm;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: $font-mono;
  font-size: 11px;
  color: $text-muted;
  text-align: right;
}

/**
 * 地址输入框与启用复选框同一行。
 *
 * 输入框吃掉剩余空间、复选框宽度由内容决定。两者的左右次序在弹窗与抽屉里不同
 * （弹窗复选框在前、抽屉在后），由模板的元素顺序决定，这里不做假设。
 */
.protocol-url-row {
  display: flex;
  align-items: center;
  gap: $space-sm;

  :deep(.n-input) {
    flex: 1 1 auto;
    min-width: 0;
  }

  :deep(.n-checkbox) {
    flex: 0 0 auto;
    white-space: nowrap;
  }
}

.advanced-add-btn {
  color: $text-muted !important;

  &:hover {
    color: $accent !important;
  }
}

.advanced-empty {
  text-align: center;
  padding: $space-sm 0;
  font-family: $font-body;
  font-size: 12px;
  color: $text-muted;
}

.request-body-rules-hint {
  font-size: 11px;
  opacity: 0.7;
}

.advanced-row {
  display: flex;
  align-items: center;
  gap: $space-sm;
  margin-bottom: $space-sm;
}

.advanced-input-key {
  flex: 0 0 35%;
}

.advanced-input-value {
  flex: 1;
}

.advanced-delete-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: $radius;
  background: transparent;
  color: $text-muted;
  cursor: pointer;
  transition: all 0.2s ease;
  flex-shrink: 0;

  &:hover {
    color: $danger;
    background: rgba($danger, 0.08);
  }
}

/* ── 拉取模型差异对比模态框 ── */
.pull-diff-summary {
  display: flex;
  gap: $space-sm;
  margin-bottom: $space-md;
}

.pull-diff-badge {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-family: $font-mono;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  background: rgba($text-muted, 0.08);
  color: $text-muted;

  &--added {
    background: rgba($success, 0.12);
    color: #2d7049;
  }

  &--removed {
    background: rgba($danger, 0.12);
    color: #a03e3e;
  }
}

.pull-diff-list {
  max-height: 360px;
  overflow-y: auto;
  border: 1px solid $border;
  border-radius: $radius;
}

.pull-diff-item {
  display: flex;
  align-items: center;
  gap: $space-sm;
  padding: 8px 12px;
  font-family: $font-mono;
  font-size: 13px;
  color: $text-body;
  border-bottom: 1px solid $border-light;

  &:last-child {
    border-bottom: 0;
  }

  &--added {
    background: rgba($success, 0.08);
  }

  &--removed {
    background: rgba($danger, 0.08);
    color: $text-muted;
  }

  &--unchanged {
    background: transparent;
  }
}

.pull-diff-icon {
  flex-shrink: 0;
  width: 16px;
  text-align: center;
  font-size: 12px;
  font-weight: 700;

  .pull-diff-item--added & {
    color: $success;
  }

  .pull-diff-item--removed & {
    color: $danger;
  }

  .pull-diff-item--unchanged & {
    color: $text-muted;
  }
}

.pull-diff-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pull-diff-revert {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: $text-muted;
  cursor: pointer;
  margin-left: auto;
  transition: all 0.2s ease;

  &:hover {
    color: $accent;
    background: $accent-light;
  }
}

.pull-diff-footer {
  display: flex;
  justify-content: flex-end;
  gap: $space-sm;
}

/* ── 移动端抽屉适配 ── */
@media (max-width: 768px) {
  .edit-drawer {
    :deep(.n-drawer-content) {
      overflow-x: hidden;
    }
  }

  /* 添加供应商模态框网格单列 */
  .add-modal-grid {
    grid-template-columns: 1fr;
  }

}
</style>