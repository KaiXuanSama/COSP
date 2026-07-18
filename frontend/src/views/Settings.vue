<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { NCard, NInput, NButton, NSwitch, NTag, NDrawer, NDrawerContent, NModal, NSelect, NDropdown, useMessage } from 'naive-ui'
import ProviderModelsSection from '@/components/settings/ProviderModelsSection.vue'
import RequestBodyRuleEditor from '@/components/settings/request-body-rules/RequestBodyRuleEditor.vue'
import { useProviderStore, type ApiKeyEntry } from '@/stores/providers'
import type { RequestBodyEditorState } from '@/features/request-body-rules/editorState'
import { createDefaultRequestBodyEditorState } from '@/features/request-body-rules/editorState'
import { MIMO_EXAMPLE_RULESET } from '@/features/request-body-rules/defaultRequestBody'
import type { RequestBodyTemplateKey } from '@/features/request-body-rules/requestBodyTemplates'
import { composeRequestBodyTemplate } from '@/features/request-body-rules/requestBodyTemplates'
import type { RuleSet } from '@/features/request-body-rules/types'

const providerStore = useProviderStore()
const message = useMessage()
const fakeVersion = ref('')
const versionPlaceholder = ref('0.6.4')

const windowWidth = ref(window.innerWidth)
const DRAWER_MIN_WIDTH = 700
const drawerWidth = computed(() =>
  windowWidth.value <= DRAWER_MIN_WIDTH ? windowWidth.value : DRAWER_MIN_WIDTH
)

const docsWindow = ref({
  visible: false,
  providerKey: null as string | null,
  x: 24,
  y: 72,
  width: 560,
  height: 680,
})

function clamp(value: number, min: number, max: number) {
  if (max < min) return min
  return Math.min(Math.max(value, min), max)
}

function clampDocsWindow() {
  const minWidth = 360
  const minHeight = 320
  docsWindow.value.width = clamp(docsWindow.value.width, minWidth, Math.max(minWidth, window.innerWidth - 32))
  docsWindow.value.height = clamp(docsWindow.value.height, minHeight, Math.max(minHeight, window.innerHeight - 32))
  docsWindow.value.x = clamp(docsWindow.value.x, 16, Math.max(16, window.innerWidth - docsWindow.value.width - 16))
  docsWindow.value.y = clamp(docsWindow.value.y, 16, Math.max(16, window.innerHeight - docsWindow.value.height - 16))
}

function getInitialDocsWindowRect() {
  const minWidth = 360
  const preferredWidth = 620
  const availableLeftWidth = window.innerWidth - drawerWidth.value - 48
  const width = clamp(
    availableLeftWidth > minWidth ? Math.min(preferredWidth, availableLeftWidth) : Math.min(preferredWidth, window.innerWidth - 32),
    minWidth,
    Math.max(minWidth, window.innerWidth - 32),
  )
  const height = clamp(window.innerHeight - 96, 360, 760)
  const x = clamp(window.innerWidth - drawerWidth.value - width - 24, 16, Math.max(16, window.innerWidth - width - 16))
  const y = clamp(48, 16, Math.max(16, window.innerHeight - height - 16))
  return { x, y, width, height }
}

function onResize() {
  windowWidth.value = window.innerWidth
  if (docsWindow.value.visible) {
    clampDocsWindow()
  }
}

function startDocsWindowDrag(event: PointerEvent) {
  event.preventDefault()
  const startX = event.clientX
  const startY = event.clientY
  const originX = docsWindow.value.x
  const originY = docsWindow.value.y

  const onMove = (moveEvent: PointerEvent) => {
    docsWindow.value.x = clamp(
      originX + moveEvent.clientX - startX,
      16,
      Math.max(16, window.innerWidth - docsWindow.value.width - 16),
    )
    docsWindow.value.y = clamp(
      originY + moveEvent.clientY - startY,
      16,
      Math.max(16, window.innerHeight - docsWindow.value.height - 16),
    )
  }

  const onUp = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
  }

  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

function startDocsWindowResize(event: PointerEvent) {
  event.preventDefault()
  const startX = event.clientX
  const startY = event.clientY
  const originWidth = docsWindow.value.width
  const originHeight = docsWindow.value.height

  const onMove = (moveEvent: PointerEvent) => {
    docsWindow.value.width = clamp(
      originWidth + moveEvent.clientX - startX,
      360,
      Math.max(360, window.innerWidth - docsWindow.value.x - 16),
    )
    docsWindow.value.height = clamp(
      originHeight + moveEvent.clientY - startY,
      320,
      Math.max(320, window.innerHeight - docsWindow.value.y - 16),
    )
  }

  const onUp = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
  }

  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

function closeOfficialDocs() {
  docsWindow.value.visible = false
}

function openOfficialDocs() {
  if (!editingKey.value) return
  docsWindow.value = {
    visible: true,
    providerKey: editingKey.value,
    ...getInitialDocsWindowRect(),
  }
}

const activeDocsUrl = computed(() => {
  const key = docsWindow.value.providerKey
  return key ? providerMeta.value[key]?.docsUrl ?? '' : ''
})

const activeDocsTitle = computed(() => {
  const key = docsWindow.value.providerKey
  return key ? `${providerMeta.value[key]?.displayName ?? key} 官方文档` : '官方文档'
})

onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const providerMeta = ref<Record<string, { displayName: string; colorClass: string; apiUrlPlaceholder: string; docsUrl: string }>>({
  mimo: {
    displayName: 'MiMo',
    colorClass: 'blue',
    apiUrlPlaceholder: 'https://api.xiaomimimo.com/v1',
    docsUrl: 'https://platform.xiaomimimo.com/docs/zh-CN/pricing',
  },
  deepseek: {
    displayName: 'DeepSeek',
    colorClass: 'warning',
    apiUrlPlaceholder: 'https://api.deepseek.com/v1',
    docsUrl: 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing',
  },
})

const editingKey = ref<string | null>(null)
const editForm = ref({
  baseUrl: '',
  apiKeys: [] as ApiKeyEntry[],
  activeKeyUuid: '' as string,
  models: [] as any[],
})
const pullingModels = ref(false)

interface PullDiffEntry {
  modelName: string
  status: 'added' | 'removed' | 'unchanged'
  existingModel?: any
}

const pullDiffModal = ref({
  visible: false,
  entries: [] as PullDiffEntry[],
  addedCount: 0,
  removedCount: 0,
})

const showAddModal = ref(false)

const customProviderContextMenu = ref({
  visible: false,
  providerKey: '',
  x: 0,
  y: 0,
})
const deleteCustomProviderModal = ref({
  visible: false,
  providerKey: '',
})

const customProviderContextOptions = computed(() => [
  { label: '启用', key: 'enable' as const },
  { label: '修改', key: 'edit' as const },
  { label: '删除', key: 'delete' as const, props: { class: 'provider-context-menu__delete' } },
])

const deleteCustomProviderName = computed(() => {
  const key = deleteCustomProviderModal.value.providerKey
  return providerMeta.value[key]?.displayName || key
})

function openCustomProviderContextMenu(event: MouseEvent, key: string) {
  event.preventDefault()
  customProviderContextMenu.value = {
    visible: true,
    providerKey: key,
    x: event.clientX,
    y: event.clientY,
  }
}

function handleCustomProviderContextSelect(action: 'enable' | 'edit' | 'delete') {
  const key = customProviderContextMenu.value.providerKey
  customProviderContextMenu.value.visible = false
  if (!key) return

  if (action === 'enable') {
    enableProvider(key)
    return
  }
  if (action === 'edit') {
    openEditCustomModal(key)
    return
  }
  deleteCustomProviderModal.value = { visible: true, providerKey: key }
}

async function confirmRemoveCustomProvider() {
  const key = deleteCustomProviderModal.value.providerKey
  if (!key) return
  deleteCustomProviderModal.value.visible = false
  await removeCustomProvider(key)
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
  if (key && isEditableProvider(key)) {
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
    openEditCustomModal(key)
    return
  }
  await toggleProvider(key, false)
}

// ==================== API Key 管理 ====================

const showApiKeyModal = ref(false)
const editingApiKeys = ref<ApiKeyEntry[]>([])

/** 脱敏显示 API Key：前4位 + **** + 后4位 */
function maskApiKey(key: string): string {
  if (!key || key.length <= 10) return key ? '****' : ''
  return key.substring(0, 6) + '****' + key.substring(key.length - 4)
}

/** 展示某条 Key 的脱敏值：优先展示后端脱敏值，其次对新输入的明文脱敏 */
function displayKey(entry: ApiKeyEntry): string {
  if (entry.apiKey && entry.apiKey.trim()) return maskApiKey(entry.apiKey.trim())
  return entry.masked || ''
}

/** 构建下拉选项，value 使用 keyUuid（新增未保存项用临时标记） */
const apiKeyOptions = computed(() =>
  editForm.value.apiKeys.map((entry, index) => ({
    label: `${entry.name || '未命名'}: ${displayKey(entry)}`,
    value: entry.keyUuid || `__new_${index}`,
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
  // 保留有 keyUuid（已有）或填了新明文的项
  const valid = editingApiKeys.value.filter(k => (k.keyUuid && k.keyUuid.length > 0) || (k.apiKey && k.apiKey.trim()))
  editForm.value.apiKeys = valid
  // 若激活项已被删除，重置为第一项
  const activeStillExists = valid.some(k => k.keyUuid && k.keyUuid === editForm.value.activeKeyUuid)
  if (!activeStillExists) {
    editForm.value.activeKeyUuid = valid[0]?.keyUuid || (valid.length > 0 ? `__new_0` : '')
  }
  showApiKeyModal.value = false
}

function cancelApiKeyModal() {
  showApiKeyModal.value = false
}

// ==================== 自定义供应商 ====================

const showCustomAddModal = ref(false)
const customProviderName = ref('')
const customAdvancedExpanded = ref(false)
const editingCustomKey = ref<string | null>(null)
const customBaseUrl = ref('')
const showPresetModal = ref(false)

/** 预设供应商模板 */
interface ProviderPreset {
  label: string
  baseUrl: string
  headers: KeyValueEntry[]
  /** 可选的默认请求体模板键；未配置时回退为基础参数。 */
  requestBodyTemplateKeys?: RequestBodyTemplateKey[]
  /** 可选的默认请求体映射规则；未配置时回退为空规则集。 */
  requestBodyRules?: RuleSet
}

const officialPresets: ProviderPreset[] = [
  {
    label: 'LongCat',
    baseUrl: 'https://api.longcat.chat/openai/v1',
    headers: [],
  },
  {
    label: 'Kimi',
    baseUrl: 'https://api.moonshot.cn/v1',
    headers: [],
  },
  {
    label: 'Kimi (CodePlan)',
    baseUrl: 'https://api.kimi.com/coding/v1',
    headers: [],
  },
  {
    label: 'Mimo (TokenPlan)',
    baseUrl: 'https://token-plan-cn.xiaomimimo.com/v1',
    headers: [],
    requestBodyTemplateKeys: ['message-tool-image'],
    requestBodyRules: MIMO_EXAMPLE_RULESET,
  },
  {
    label: 'Agnes',
    baseUrl: 'https://apihub.agnes-ai.com/v1',
    headers: [],
  },
  {
    label: 'Zhipu',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    headers: [],
  },
]

const aggregatorPresets: ProviderPreset[] = [
  {
    label: 'SenseNova',
    baseUrl: 'https://token.sensenova.cn/v1',
    headers: [],
  },
  {
    label: 'Uumit',
    baseUrl: 'https://agent.uumit.com/v1',
    headers: [],
  },
  {
    label: 'Xunfei',
    baseUrl: 'https://maas-api.cn-huabei-1.xf-yun.com/v2',
    headers: [],
  },
  {
    label: 'WorkBuddy',
    baseUrl: 'https://copilot.tencent.com/v2',
    headers: [],
  },
]

const relayPresets: ProviderPreset[] = [
  {
    label: 'AgentRouter',
    baseUrl: 'https://agentrouter.org/v1',
    headers: [{ key: 'User-Agent', value: 'claude-cli/2.1.195 (external, cli)' }],
  },
  {
    label: 'FreeModel',
    baseUrl: 'https://api.freemodel.dev/v1',
    headers: [],
  },
]

const allPresets = [...officialPresets, ...aggregatorPresets, ...relayPresets]

/** 深拷贝规则集，避免预设常量被编辑器状态原地修改。 */
function cloneRuleSet(rules: RuleSet): RuleSet {
  return JSON.parse(JSON.stringify(rules)) as RuleSet
}

/** 选择预设时自动填充名称、地址、请求头、请求体模板和规则。 */
function applyPreset(label: string) {
  const preset = allPresets.find(p => p.label === label)
  if (preset) {
    customProviderName.value = preset.label
    customBaseUrl.value = preset.baseUrl
    customHeaders.value = preset.headers.map(h => ({ ...h }))
    const editorState = createDefaultRequestBodyEditorState()
    if (preset.requestBodyTemplateKeys && preset.requestBodyTemplateKeys.length > 0) {
      editorState.templateKeys = [...preset.requestBodyTemplateKeys]
      editorState.previewBody = composeRequestBodyTemplate(preset.requestBodyTemplateKeys)
    }
    if (preset.requestBodyRules) {
      editorState.rules = cloneRuleSet(preset.requestBodyRules)
    }
    requestBodyEditorState.value = editorState
    customAdvancedExpanded.value = true
  }
  showPresetModal.value = false
}

/** 清空自定义供应商表单所有内容 */
function clearCustomForm() {
  customProviderName.value = ''
  customBaseUrl.value = ''
  customHeaders.value = []
  requestBodyEditorState.value = createDefaultRequestBodyEditorState()
  customAdvancedExpanded.value = false
}

/** 高级设置 - 请求头覆盖列表 */
interface KeyValueEntry {
  key: string
  value: string
}
const customHeaders = ref<KeyValueEntry[]>([])

/** 请求体映射规则及编辑器预览状态。 */
const requestBodyEditorState = ref<RequestBodyEditorState>(createDefaultRequestBodyEditorState())
const showRequestBodyRuleEditor = ref(false)

function addCustomHeader() {
  customHeaders.value.push({ key: '', value: '' })
}

function removeCustomHeader(index: number) {
  customHeaders.value.splice(index, 1)
}

function resetCustomAdvanced() {
  customAdvancedExpanded.value = false
  customHeaders.value = []
  requestBodyEditorState.value = createDefaultRequestBodyEditorState()
  customBaseUrl.value = ''
  editingCustomKey.value = null
}

/** 打开编辑自定义供应商模态框 */
function openEditCustomModal(key: string) {
  editingCustomKey.value = key
  const provider = providerStore.providers[key]
  const displayName = providerMeta.value[key]?.displayName || key.replace('custom-', '').replace(/-/g, ' ')
  customProviderName.value = displayName
  customHeaders.value = []
  requestBodyEditorState.value = createDefaultRequestBodyEditorState()
  customBaseUrl.value = (provider as any)?.baseUrl || ''
  if (provider) {
    try {
      const headerRules = JSON.parse(provider.requestTransform?.headerRulesJson || '[]')
      if (Array.isArray(headerRules)) {
        customHeaders.value = headerRules.map((h: any) => ({ key: h.key || '', value: h.value || '' }))
      }
    } catch { /* ignore */ }
    try {
      const saved = provider.requestTransform
      if (saved) {
        requestBodyEditorState.value = {
          templateKeys: JSON.parse(saved.bodyTemplateKeysJson) as RequestBodyTemplateKey[],
          previewBody: JSON.parse(saved.bodyPreviewJson) as Record<string, unknown>,
          rules: JSON.parse(saved.bodyRulesJson),
        }
      }
    } catch {
      requestBodyEditorState.value = createDefaultRequestBodyEditorState()
    }
  }
  showAddModal.value = false
  showCustomAddModal.value = true
}

/** 构建请求头规则 JSON。 */
function buildHeaderRulesJson(): string {
  const headers = customHeaders.value.filter(h => h.key.trim())
  return JSON.stringify(headers.map(h => ({ key: h.key.trim(), value: h.value })))
}

/** 添加自定义供应商 */
async function addCustomProvider() {
  const name = customProviderName.value.trim()
  if (!name) {
    message.warning('请输入供应商名称')
    return
  }
  try {
    const headerRulesJson = buildHeaderRulesJson()
    const baseUrl = customBaseUrl.value.trim()
    const requestTransform = {
      bodyTemplateKeysJson: JSON.stringify(requestBodyEditorState.value.templateKeys),
      bodyPreviewJson: JSON.stringify(requestBodyEditorState.value.previewBody),
      bodyRulesJson: JSON.stringify(requestBodyEditorState.value.rules),
    }
    if (editingCustomKey.value) {
      // 编辑模式
      await providerStore.updateCustomProvider(
        editingCustomKey.value, name, headerRulesJson, baseUrl, requestTransform,
      )
      // 更新前端元数据
      const oldKey = editingCustomKey.value
      const newKey = 'custom-' + name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
      const metaUpdate = { displayName: name, apiUrlPlaceholder: baseUrl || 'https://api.example.com/v1' }
      if (newKey !== oldKey && providerMeta.value[oldKey]) {
        providerMeta.value[newKey] = { ...providerMeta.value[oldKey], ...metaUpdate }
        delete providerMeta.value[oldKey]
      } else {
        providerMeta.value[oldKey] = { ...providerMeta.value[oldKey], ...metaUpdate }
      }
      showCustomAddModal.value = false
      resetCustomAdvanced()
      message.success(`已修改自定义供应商「${name}」`)
    } else {
      // 新增模式
      const res = await providerStore.addCustomProvider(name, headerRulesJson, baseUrl, requestTransform)
      providerMeta.value[res.providerKey] = {
        displayName: name,
        colorClass: 'custom',
        apiUrlPlaceholder: baseUrl || 'https://api.example.com/v1',
        docsUrl: '',
      }
      showCustomAddModal.value = false
      customProviderName.value = ''
      resetCustomAdvanced()
      message.success(`已添加自定义供应商「${name}」`)
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error || '操作失败')
  }
}

/** 删除自定义供应商 */
async function removeCustomProvider(key: string) {
  const displayName = providerMeta.value[key]?.displayName || key
  try {
    await providerStore.deleteCustomProvider(key)
    delete providerMeta.value[key]
    if (editingKey.value === key) {
      closeEditPanel()
    }
    message.success(`已删除自定义供应商「${displayName}」`)
  } catch {
    message.error('删除失败')
  }
}

/** 判断是否为自定义供应商 */
function isCustomProvider(key: string) {
  return key.startsWith('custom-')
}

/** 内置供应商 key 列表 */
const builtInProviderKeys = new Set(Object.keys(providerMeta.value))

/** 判断是否可编辑/删除（自定义供应商 或 非内置供应商） */
function isEditableProvider(key: string) {
  return isCustomProvider(key) || !builtInProviderKeys.has(key)
}

const enabledProviderKeys = computed(() => {
  // 内置 + 自定义供应商的 key
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

/**判断是否已有至少一个自定义供应商被创建（用于模态框空状态提示判断） */
const hasCustomProviders = computed(() => {
  return Object.keys(providerStore.providers).some(k => k.startsWith('custom-'))
})

watch(editingKey, (key) => {
  if (docsWindow.value.visible && key) {
    docsWindow.value.providerKey = key
  }
})

async function enableProvider(key: string) {
  await providerStore.toggleProvider(key, true)
  message.success(`${providerMeta.value[key]?.displayName || key} 已启用`)
  showAddModal.value = false
}

onMounted(async () => {
  await providerStore.fetchAll()
  await providerStore.fetchFakeVersion()
  // 将 custom- 前缀的供应商注入 providerMeta
  for (const key of Object.keys(providerStore.providers)) {
    if (key.startsWith('custom-') && !providerMeta.value[key]) {
      // 从 key 生成可读的 displayName
      const displayName = key.replace('custom-', '').replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
      const actualBaseUrl = providerStore.providers[key]?.baseUrl || ''
      providerMeta.value[key] = {
        displayName,
        colorClass: 'custom',
        apiUrlPlaceholder: actualBaseUrl || 'https://api.example.com/v1',
        docsUrl: '',
      }
    }
  }
  if (providerStore.fakeVersion) {
    fakeVersion.value = providerStore.fakeVersion
    versionPlaceholder.value = providerStore.fakeVersion
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
      apiKeys,
      activeKeyUuid: activeEntry?.keyUuid || apiKeys[0]?.keyUuid || '',
      models: p.models.map(m => ({
        ...m,
        contextSize: String(m.contextSize ?? '0'),
        maxOutputTokens: String(m.maxOutputTokens ?? '128000'),
        reasoningEffort: typeof m.reasoningEffort === 'string' && m.reasoningEffort.trim()
          ? m.reasoningEffort.split(',')[0].trim()
          : 'Medium',
      })),
    }
  }
}

function buildEditableModel(modelName = '', source: Record<string, any> = {}) {
  return {
    ...source,
    modelName,
    enabled: source.enabled ?? true,
    contextSize: String(source.contextSize ?? '128000'),
    maxOutputTokens: String(source.maxOutputTokens ?? '128000'),
    capsTools: source.capsTools ?? true,
    capsVision: source.capsVision ?? false,
    reasoningEffort: typeof source.reasoningEffort === 'string' && source.reasoningEffort.trim()
      ? source.reasoningEffort.split(',')[0].trim()
      : 'Medium',
  }
}

function extractModelNames(payload: unknown) {
  let parsedPayload = payload
  if (typeof parsedPayload === 'string') {
    try {
      parsedPayload = JSON.parse(parsedPayload)
    } catch {
      return [] as string[]
    }
  }

  const modelNames = new Set<string>()

  const collect = (items: unknown) => {
    if (!Array.isArray(items)) return
    for (const item of items) {
      if (typeof item === 'string') {
        const value = item.trim()
        if (value) modelNames.add(value)
        continue
      }
      if (!item || typeof item !== 'object') continue
      for (const key of ['id', 'model', 'name']) {
        const value = (item as Record<string, unknown>)[key]
        if (typeof value === 'string' && value.trim()) {
          modelNames.add(value.trim())
          break
        }
      }
    }
  }

  if (Array.isArray(parsedPayload)) {
    collect(parsedPayload)
  } else if (parsedPayload && typeof parsedPayload === 'object') {
    const source = parsedPayload as Record<string, unknown>
    collect(source.data)
    collect(source.models)
  }

  return Array.from(modelNames)
}

function resolvePullModelsErrorMessage(error: any) {
  const status = error?.response?.status
  const data = error?.response?.data

  // 优先使用后端返回的友好错误信息
  if (data && typeof data === 'object' && typeof data.error === 'string' && data.error.trim()) {
    return '模型拉取失败：' + data.error
  }
  if (typeof data === 'string' && data.trim()) {
    try {
      const parsed = JSON.parse(data)
      if (typeof parsed.error === 'string' && parsed.error.trim()) {
        return '模型拉取失败：' + parsed.error
      }
    } catch { /* 非 JSON，使用原文 */ }
    return '模型拉取失败：' + data
  }
  // 前端兜底
  if (status === 401 || status === 403) {
    return '模型拉取失败：API Key 无效或无权限'
  }
  if (status === 404) {
    return '模型拉取失败：模型列表端点不存在'
  }
  return '拉取模型失败，请检查网络连接和 API 地址'
}

function closeEditPanel() {
  editingKey.value = null
}

async function saveEditPanel() {
  if (!editingKey.value) return
  const key = editingKey.value
  // 序列化 apiKeys：仅回传 keyUuid（未修改）或 keyUuid+apiKey（修改）或 apiKey（新增）
  const apiKeysPayload = editForm.value.apiKeys.map(k => {
    const entry: Record<string, string> = { name: k.name || '' }
    if (k.keyUuid) entry.keyUuid = k.keyUuid
    if (k.apiKey && k.apiKey.trim()) entry.apiKey = k.apiKey.trim()
    return entry
  })
  const params: Record<string, string> = {
    baseUrl: editForm.value.baseUrl,
    apiKeys: JSON.stringify(apiKeysPayload),
    activeKeyUuid: editForm.value.activeKeyUuid.startsWith('__new_') ? '' : editForm.value.activeKeyUuid,
  }
  editForm.value.models.forEach((m, i) => {
    params[`models[${i}].name`] = m.modelName
    params[`models[${i}].enabled`] = m.enabled ? 'on' : ''
    params[`models[${i}].contextSize`] = m.contextSize || '0'
    params[`models[${i}].maxOutputTokens`] = m.maxOutputTokens || '128000'
    params[`models[${i}].capsTools`] = m.capsTools ? 'on' : ''
    params[`models[${i}].capsVision`] = m.capsVision ? 'on' : ''
    if (m.reasoningEffort) {
      params[`models[${i}].reasoningEffort`] = m.reasoningEffort
    }
  })
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

async function saveFakeVersion() {
  await providerStore.saveFakeVersion(fakeVersion.value)
  versionPlaceholder.value = fakeVersion.value
  message.success('版本号已保存')
}

async function pullModels() {
  if (!editingKey.value) return
  const providerKey = editingKey.value

  // 解析拉取模型所需的 Key：
  // 1. 优先使用当前选中 Key 条目中新输入的明文（覆盖新增未保存 + 重新输入的场景）
  // 2. 其次使用任意一条有明文输入的 Key
  // 3. 若表单中完全没有明文，但当前选中 Key 已保存（有 keyUuid）→ 通过 UUID 让后端解密
  // 4. 以上都不满足 → 提示用户
  const activeUuid = editForm.value.activeKeyUuid
  const activeEntry = editForm.value.apiKeys.find(
    k => (k.keyUuid && k.keyUuid === activeUuid) || `__new_0` === activeUuid
  )
  let apiKey = activeEntry?.apiKey?.trim() || ''
  let keyUuid = ''
  if (!apiKey) {
    const anyPlain = editForm.value.apiKeys.find(k => k.apiKey && k.apiKey.trim())
    apiKey = anyPlain?.apiKey?.trim() || ''
  }
  if (!apiKey) {
    // 没有明文可用，尝试使用已保存 Key 的 UUID 让后端解密
    if (activeEntry?.keyUuid) {
      keyUuid = activeEntry.keyUuid
    } else {
      // 尝试任意一条已保存的 Key
      const anySaved = editForm.value.apiKeys.find(k => k.keyUuid)
      keyUuid = anySaved?.keyUuid || ''
    }
  }
  if (!apiKey && !keyUuid) {
    message.warning('拉取模型需要 API Key。请在"管理 API Key"中新增一条 Key 后再拉取。')
    return
  }

  pullingModels.value = true
  try {
    const resolvedBaseUrl = editForm.value.baseUrl.trim() || providerMeta.value[providerKey]?.apiUrlPlaceholder || ''
    const payload: Record<string, string> = { baseUrl: resolvedBaseUrl }
    if (apiKey) {
      payload.apiKey = apiKey
    } else {
      payload.keyUuid = keyUuid
    }
    const responsePayload = await providerStore.pullProviderModels(providerKey, payload)
    const modelNames = extractModelNames(responsePayload)
    if (modelNames.length === 0) {
      message.warning('未拉取到模型')
      return
    }

    // 计算差异
    const currentModelNames = new Set(editForm.value.models.map((m: any) => m.modelName))
    const pulledModelNames = new Set(modelNames)

    const entries: PullDiffEntry[] = []
    // 保留的 + 新增的
    for (const name of modelNames) {
      entries.push({
        modelName: name,
        status: currentModelNames.has(name) ? 'unchanged' : 'added',
        existingModel: currentModelNames.has(name)
          ? editForm.value.models.find((m: any) => m.modelName === name)
          : undefined,
      })
    }
    // 被删除的
    for (const m of editForm.value.models as any[]) {
      if (!pulledModelNames.has(m.modelName)) {
        entries.push({ modelName: m.modelName, status: 'removed', existingModel: m })
      }
    }

    pullDiffModal.value = {
      visible: true,
      entries,
      addedCount: entries.filter(e => e.status === 'added').length,
      removedCount: entries.filter(e => e.status === 'removed').length,
    }
  } catch (error: any) {
    message.error(resolvePullModelsErrorMessage(error))
  } finally {
    pullingModels.value = false
  }
}

function applyPulledModels() {
  const existingModels = new Map(editForm.value.models.map((model: any) => [model.modelName, model]))
  // 只保留 added 和 unchanged 的模型
  editForm.value.models = pullDiffModal.value.entries
    .filter(e => e.status !== 'removed')
    .map(e => buildEditableModel(e.modelName, e.existingModel ?? {}))
  pullDiffModal.value.visible = false
  message.success(`已应用：新增 ${pullDiffModal.value.addedCount} 个，移除 ${pullDiffModal.value.removedCount} 个`)
}

function cancelPulledModels() {
  pullDiffModal.value.visible = false
}

function revertPullDiff(index: number) {
  const entry = pullDiffModal.value.entries[index]
  if (!entry || entry.status === 'unchanged') return
  if (entry.status === 'added') {
    // 新增的撤销 → 从列表中移除
    pullDiffModal.value.entries.splice(index, 1)
  } else {
    // 移除的撤销 → 恢复为未变更
    entry.status = 'unchanged'
  }
  pullDiffModal.value.addedCount = pullDiffModal.value.entries.filter(e => e.status === 'added').length
  pullDiffModal.value.removedCount = pullDiffModal.value.entries.filter(e => e.status === 'removed').length
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
    <!-- 运行配置 -->
    <n-card title="运行配置" :bordered="true">
      <div class="field-group">
        <label class="field-label" for="fakeVersion">伪造版本号</label>
        <div class="fake-version-row">
          <n-input id="fakeVersion" v-model:value="fakeVersion" :placeholder="versionPlaceholder"
            @keyup.enter="saveFakeVersion" />
          <n-button type="primary" @click="saveFakeVersion">保存</n-button>
        </div>
      </div>
    </n-card>

    <!-- 供应商配置 -->
    <n-card title="供应商配置" :bordered="true" style="margin-top: 16px;">
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
              {{providerStore.providers[key].models.filter((m: any) => m.enabled).length}} 个模型
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
      <div v-if="disabledProviderKeys.length === 0 && !hasCustomProviders" class="add-modal-empty">
        所有内置供应商已启用
      </div>
      <div class="add-modal-grid">
        <div v-for="key in disabledProviderKeys" :key="key" class="add-modal-card"
          :class="{ 'add-modal-card--custom': isCustomProvider(key) }"
          @click="enableProvider(key)"
          @contextmenu="isCustomProvider(key) && openCustomProviderContextMenu($event, key)">
          <div class="add-modal-card-top" :class="providerMeta[key]?.colorClass || 'accent'"></div>
          <div class="add-modal-card-name">{{ providerMeta[key]?.displayName || key }}</div>
          <div class="add-modal-card-desc">{{ providerMeta[key]?.apiUrlPlaceholder || '' }}</div>
        </div>
        <!-- 自定义供应商入口 — 始终显示，不依赖 disabledProviderKeys -->
        <div class="add-modal-card add-modal-card--new" @click="showAddModal = false; showCustomAddModal = true">
          <div class="add-modal-card-top accent"></div>
          <div class="add-modal-card-name">自定义供应商</div>
          <div class="add-modal-card-desc">标准 OpenAI 兼容接口</div>
        </div>
      </div>
    </n-modal>

    <n-dropdown
      placement="bottom-start"
      trigger="manual"
      :show="customProviderContextMenu.visible"
      :x="customProviderContextMenu.x"
      :y="customProviderContextMenu.y"
      :options="customProviderContextOptions"
      @select="handleCustomProviderContextSelect"
      @clickoutside="customProviderContextMenu.visible = false"
    />

    <n-modal v-model:show="deleteCustomProviderModal.visible" preset="dialog" type="error"
      title="确认删除自定义供应商" positive-text="删除" negative-text="取消"
      @positive-click="confirmRemoveCustomProvider">
      删除自定义供应商「{{ deleteCustomProviderName }}」后，其 API Key、模型和请求转换配置将无法恢复。确定继续吗？
    </n-modal>

    <!-- 自定义供应商名称输入模态框 -->
    <n-modal v-model:show="showCustomAddModal" preset="card" :title="editingCustomKey ? '修改自定义供应商' : '添加自定义供应商'"
      :style="{ maxWidth: '480px' }" closable :mask-closable="true"
      @update:show="(val: boolean) => { if (!val) resetCustomAdvanced() }">
      <div class="field-group">
        <label class="field-label">自定义供应商名称</label>
        <div class="custom-name-row">
          <n-input v-model:value="customProviderName" placeholder="输入供应商名称" />
          <n-button size="small" @click="clearCustomForm">清空</n-button>
          <n-button size="small" @click="showPresetModal = true">预设</n-button>
        </div>
      </div>

      <!-- 高级设置折叠区域 -->
      <div class="advanced-toggle" @click="customAdvancedExpanded = !customAdvancedExpanded">
        <span class="advanced-toggle-label">高级设置</span>
        <svg class="advanced-toggle-arrow" :class="{ 'advanced-toggle-arrow--expanded': customAdvancedExpanded }"
          width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </div>

      <div v-if="customAdvancedExpanded" class="advanced-panel">
        <!-- API 地址 -->
        <div class="advanced-section">
          <div class="advanced-section-header">
            <span class="advanced-section-title">API 地址</span>
          </div>
          <n-input v-model:value="customBaseUrl" placeholder="https://api.example.com/v1" />
        </div>

        <!-- 请求头覆盖 -->
        <div class="advanced-section">
          <div class="advanced-section-header">
            <span class="advanced-section-title">额外覆写或修剪请求头</span>
            <n-button text size="tiny" class="advanced-add-btn" @click="addCustomHeader">
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
          <div v-if="customHeaders.length === 0" class="advanced-empty">暂无自定义请求头</div>
          <div v-for="(header, idx) in customHeaders" :key="idx" class="advanced-row">
            <n-input v-model:value="header.key" placeholder="Header 名称" class="advanced-input-key" />
            <n-input v-model:value="header.value" placeholder="值（/del/ 表示删除）" class="advanced-input-value" />
            <button class="advanced-delete-btn" title="删除" @click="removeCustomHeader(idx)">
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
            已配置 {{ requestBodyEditorState.rules.rules.length }} 条规则
            <span class="request-body-rules-hint">（保存供应商配置后生效）</span>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="custom-add-footer">
          <n-button @click="showCustomAddModal = false; resetCustomAdvanced()">取消</n-button>
          <n-button type="primary" @click="addCustomProvider">{{ editingCustomKey ? '应用' : '添加' }}</n-button>
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
            :disabled="!pullDiffModal.addedCount && !pullDiffModal.removedCount">
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
        <div class="field-group">
          <label class="field-label">API 地址</label>
          <n-input v-model:value="editForm.baseUrl"
            :placeholder="editingKey ? providerMeta[editingKey]?.apiUrlPlaceholder : ''" />
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

        <ProviderModelsSection v-model:models="editForm.models" :pulling-models="pullingModels" :has-docs="!!(editingKey && providerMeta[editingKey]?.docsUrl)"
          :compact="windowWidth <= DRAWER_MIN_WIDTH"
          @pull-models="pullModels" @open-docs="openOfficialDocs" @add-model="addModel" @remove-model="removeModel" />

        <template #footer>
          <n-button type="primary" block @click="saveEditPanel">保存</n-button>
        </template>
      </n-drawer-content>
    </n-drawer>

    <teleport to="body">
      <div v-if="docsWindow.visible && activeDocsUrl" class="docs-window-layer">
        <section class="docs-window" :style="{
          left: `${docsWindow.x}px`,
          top: `${docsWindow.y}px`,
          width: `${docsWindow.width}px`,
          height: `${docsWindow.height}px`,
        }">
          <div class="docs-window__header">
            <div class="docs-window__drag-handle" @pointerdown="startDocsWindowDrag">
              <div class="docs-window__eyebrow">官方文档</div>
              <div class="docs-window__title">{{ activeDocsTitle }}</div>
            </div>
            <div class="docs-window__actions">
              <a class="docs-window__link" :href="activeDocsUrl" target="_blank" rel="noreferrer" @click.stop>
                新标签
              </a>
              <button type="button" class="docs-window__close" @click.stop="closeOfficialDocs">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                  stroke-linecap="round" stroke-linejoin="round">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </button>
            </div>
          </div>
          <div class="docs-window__hint">窗口外可直接点击原页面控件；若官网禁止内嵌，可点“新标签”。</div>
          <div class="docs-window__body">
            <iframe class="docs-window__iframe" :src="activeDocsUrl" :title="activeDocsTitle"
              referrerpolicy="no-referrer" />
          </div>
          <button type="button" class="docs-window__resize-handle" aria-label="调整文档窗口大小"
            @pointerdown.stop="startDocsWindowResize"></button>
        </section>
      </div>
    </teleport>

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

.fake-version-row {
  display: flex;
  gap: $space-sm;
  align-items: center;
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

  &.custom {
    background: linear-gradient(90deg, $accent, $blue);
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

.docs-window-layer {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 2200;
}

.docs-window {
  position: fixed;
  display: flex;
  flex-direction: column;
  min-width: 360px;
  min-height: 320px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(232, 229, 222, 0.94);
  border-radius: $radius-lg;
  box-shadow: $shadow-lg;
  overflow: hidden;
  pointer-events: auto;
  backdrop-filter: blur(10px);
}

.docs-window__header {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: $space-md;
  padding: 14px 16px 12px;
  border-bottom: 1px solid $border;
  background: linear-gradient(135deg, rgba(194, 122, 62, 0.12), rgba(255, 255, 255, 0.92));
}

.docs-window__drag-handle {
  flex: 1;
  min-width: 0;
  cursor: move;
  user-select: none;
}

.docs-window__eyebrow {
  font-family: $font-mono;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: $text-muted;
}

.docs-window__title {
  margin-top: 4px;
  font-family: $font-display;
  font-size: 20px;
  font-weight: 600;
  color: $text-primary;
  line-height: 1.1;
}

.docs-window__actions {
  display: flex;
  align-items: center;
  gap: $space-sm;
  flex-shrink: 0;
}

.docs-window__link {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid $border;
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: $text-body;
  text-decoration: none;
  background: rgba(255, 255, 255, 0.72);
  transition: all 0.2s ease;

  &:hover {
    color: $accent;
    border-color: rgba(194, 122, 62, 0.35);
    background: rgba(194, 122, 62, 0.08);
  }
}

.docs-window__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: $text-muted;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    color: $danger;
    background: rgba(184, 74, 74, 0.08);
  }
}

.docs-window__hint {
  padding: 8px 16px;
  border-bottom: 1px solid $border-light;
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
  background: rgba(245, 243, 238, 0.82);
}

.docs-window__body {
  flex: 1;
  min-height: 0;
  background: $surface;
}

.docs-window__iframe {
  width: 100%;
  height: 100%;
  border: 0;
  background: $surface;
}

.docs-window__resize-handle {
  position: absolute;
  right: 0;
  bottom: 0;
  width: 18px;
  height: 18px;
  border: 0;
  background: transparent;
  cursor: nwse-resize;
}

.docs-window__resize-handle::before {
  content: '';
  position: absolute;
  right: 4px;
  bottom: 4px;
  width: 10px;
  height: 10px;
  border-right: 2px solid rgba(194, 122, 62, 0.5);
  border-bottom: 2px solid rgba(194, 122, 62, 0.5);
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

  &.custom {
    background: linear-gradient(90deg, $accent, $blue);
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

.add-modal-card--custom {
  position: relative;
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

.custom-add-footer {
  display: flex;
  justify-content: flex-end;
  gap: $space-sm;
}

.custom-name-row {
  display: flex;
  gap: $space-sm;
  align-items: center;
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

  .docs-window {
    min-width: 300px;
  }

  .docs-window__title {
    font-size: 18px;
  }

  .docs-window__hint {
    font-size: 12px;
  }
}
</style>