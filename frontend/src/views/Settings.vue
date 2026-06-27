<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { NCard, NInput, NButton, NSwitch, NTag, NDrawer, NDrawerContent, NModal, useMessage } from 'naive-ui'
import ProviderModelsSection from '@/components/settings/ProviderModelsSection.vue'
import { useProviderStore } from '@/stores/providers'

const providerStore = useProviderStore()
const message = useMessage()
const fakeVersion = ref('')
const versionPlaceholder = ref('0.6.4')

const windowWidth = ref(window.innerWidth)
const drawerWidth = computed(() => Math.floor(windowWidth.value / 2))

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
  longcat: {
    displayName: 'LongCat',
    colorClass: 'accent',
    apiUrlPlaceholder: 'https://api.longcat.chat/openai/v1',
    docsUrl: 'https://longcat.chat/platform/docs/zh/#%E5%8D%95%E6%AC%A1%E8%AF%B7%E6%B1%82%E9%99%90%E5%88%B6',
  },
  mimo: {
    displayName: 'MiMo',
    colorClass: 'blue',
    apiUrlPlaceholder: 'https://api.xiaomimimo.com/v1',
    docsUrl: 'https://platform.xiaomimimo.com/docs/zh-CN/pricing',
  },
  sensenova: {
    displayName: 'SenseNova',
    colorClass: 'success',
    apiUrlPlaceholder: 'https://token.sensenova.cn/v1',
    docsUrl: 'https://platform.sensenova.cn/docs',
  },
  deepseek: {
    displayName: 'DeepSeek',
    colorClass: 'warning',
    apiUrlPlaceholder: 'https://api.deepseek.com/v1',
    docsUrl: 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing',
  },
  uumit: {
    displayName: 'Uumit',
    colorClass: 'accent',
    apiUrlPlaceholder: 'https://agent.uumit.com/v1',
    docsUrl: 'https://agent.uumit.com/docs',
  },
  agnes: {
    displayName: 'Agnes',
    colorClass: 'accent',
    apiUrlPlaceholder: 'https://apihub.agnes-ai.com/v1',
    docsUrl: 'https://agnes-ai.com/doc/overview',
  },
  zhipu: {
    displayName: 'Zhipu',
    colorClass: 'warning',
    apiUrlPlaceholder: 'https://open.bigmodel.cn/api/paas/v4',
    docsUrl: 'https://docs.bigmodel.cn/cn/guide/start/introduction',
  },
  xunfei: {
    displayName: 'Xunfei',
    colorClass: 'success',
    apiUrlPlaceholder: 'https://maas-api.cn-huabei-1.xf-yun.com/v2',
    docsUrl: 'https://www.xfyun.cn/doc/spark/%E6%8E%A8%E7%90%86%E6%9C%8D%E5%8A%A1-http.html',
  },
  kimi: {
    displayName: 'Kimi',
    colorClass: 'accent',
    apiUrlPlaceholder: 'https://api.moonshot.cn/v1',
    docsUrl: 'https://platform.kimi.com/docs/overview',
  },
})

const editingKey = ref<string | null>(null)
const editForm = ref({
  baseUrl: '',
  apiKey: '',
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

// ==================== 自定义供应商 ====================

const showCustomAddModal = ref(false)
const customProviderName = ref('')
const customAdvancedExpanded = ref(false)
const editingCustomKey = ref<string | null>(null)
const customBaseUrl = ref('')

/** 高级设置 - 请求头覆盖列表 */
interface KeyValueEntry {
  key: string
  value: string
}
const customHeaders = ref<KeyValueEntry[]>([])

/** 高级设置 - 请求体修剪列表 */
const customBodyTransforms = ref<KeyValueEntry[]>([])

function addCustomHeader() {
  customHeaders.value.push({ key: '', value: '' })
}

function removeCustomHeader(index: number) {
  customHeaders.value.splice(index, 1)
}

function addCustomBodyTransform() {
  customBodyTransforms.value.push({ key: '', value: '' })
}

function removeCustomBodyTransform(index: number) {
  customBodyTransforms.value.splice(index, 1)
}

function resetCustomAdvanced() {
  customAdvancedExpanded.value = false
  customHeaders.value = []
  customBodyTransforms.value = []
  customBaseUrl.value = ''
  editingCustomKey.value = null
}

/** 打开编辑自定义供应商模态框 */
function openEditCustomModal(key: string) {
  editingCustomKey.value = key
  const provider = providerStore.providers[key]
  const displayName = providerMeta.value[key]?.displayName || key.replace('custom-', '').replace(/-/g, ' ')
  customProviderName.value = displayName
  // 解析已有 customTransforms
  customHeaders.value = []
  customBodyTransforms.value = []
  customBaseUrl.value = (provider as any)?.baseUrl || ''
  if (provider) {
    try {
      const raw = (provider as any).customTransforms
      const transforms = typeof raw === 'string' ? JSON.parse(raw) : (raw || {})
      if (Array.isArray(transforms.custom_headers)) {
        customHeaders.value = transforms.custom_headers.map((h: any) => ({ key: h.key || '', value: h.value || '' }))
      }
      if (Array.isArray(transforms.body_transforms)) {
        customBodyTransforms.value = transforms.body_transforms.map((t: any) => ({ key: t.key || '', value: t.value || '' }))
      }
    } catch { /* ignore */ }
  }
  showAddModal.value = false
  showCustomAddModal.value = true
}

/** 构建高级设置 JSON */
function buildCustomTransformsJson(): string {
  const headers = customHeaders.value.filter(h => h.key.trim())
  const transforms = customBodyTransforms.value.filter(t => t.key.trim())
  if (headers.length === 0 && transforms.length === 0) {
    return '{}'
  }
  const result: Record<string, any> = {}
  if (headers.length > 0) {
    result.custom_headers = headers.map(h => ({ key: h.key.trim(), value: h.value }))
  }
  if (transforms.length > 0) {
    result.body_transforms = transforms.map(t => ({ key: t.key.trim(), value: t.value }))
  }
  return JSON.stringify(result)
}

/** 添加自定义供应商 */
async function addCustomProvider() {
  const name = customProviderName.value.trim()
  if (!name) {
    message.warning('请输入供应商名称')
    return
  }
  try {
    const customTransforms = buildCustomTransformsJson()
    const baseUrl = customBaseUrl.value.trim()
    if (editingCustomKey.value) {
      // 编辑模式
      await providerStore.updateCustomProvider(editingCustomKey.value, name, customTransforms, baseUrl)
      // 更新前端元数据
      const oldKey = editingCustomKey.value
      const newKey = 'custom-' + name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
      if (newKey !== oldKey && providerMeta.value[oldKey]) {
        providerMeta.value[newKey] = { ...providerMeta.value[oldKey], displayName: name }
        delete providerMeta.value[oldKey]
      } else {
        providerMeta.value[oldKey] = { ...providerMeta.value[oldKey], displayName: name }
      }
      showCustomAddModal.value = false
      resetCustomAdvanced()
      message.success(`已修改自定义供应商「${name}」`)
    } else {
      // 新增模式
      const res = await providerStore.addCustomProvider(name, customTransforms, baseUrl)
      providerMeta.value[res.providerKey] = {
        displayName: name,
        colorClass: 'custom',
        apiUrlPlaceholder: 'https://api.example.com/v1',
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
      providerMeta.value[key] = {
        displayName,
        colorClass: 'custom',
        apiUrlPlaceholder: 'https://api.example.com/v1',
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
    editForm.value = {
      baseUrl: p.baseUrl || providerMeta.value[key]?.apiUrlPlaceholder || '',
      apiKey: p.apiKey || '',
      models: p.models.map(m => ({
        ...m,
        contextSize: String(m.contextSize ?? '0'),
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

  if (data && typeof data === 'object' && typeof data.error === 'string' && data.error.trim()) {
    return data.error
  }
  if (typeof data === 'string' && data.trim()) {
    return data
  }
  if (status === 401 || status === 403) {
    return '模型拉取失败：API Key 无效或无权限'
  }
  if (status === 404) {
    return '模型拉取失败：模型列表端点不存在'
  }
  return '拉取模型失败'
}

function closeEditPanel() {
  editingKey.value = null
}

async function saveEditPanel() {
  if (!editingKey.value) return
  const key = editingKey.value
  const params: Record<string, string> = {
    baseUrl: editForm.value.baseUrl,
    apiKey: editForm.value.apiKey,
  }
  editForm.value.models.forEach((m, i) => {
    params[`models[${i}].name`] = m.modelName
    params[`models[${i}].enabled`] = m.enabled ? 'on' : ''
    params[`models[${i}].contextSize`] = m.contextSize || '0'
    params[`models[${i}].capsTools`] = m.capsTools ? 'on' : ''
    params[`models[${i}].capsVision`] = m.capsVision ? 'on' : ''
    if (m.reasoningEffort) {
      params[`models[${i}].reasoningEffort`] = m.reasoningEffort
    }
  })
  try {
    await providerStore.saveProviderConfig(key, params)
    // 更新本地缓存
    if (providerStore.providers[key]) {
      providerStore.providers[key].baseUrl = editForm.value.baseUrl
      providerStore.providers[key].apiKey = editForm.value.apiKey
      providerStore.providers[key].models = editForm.value.models.map((model: any) => buildEditableModel(model.modelName, model))
    }
    message.success('保存成功')
    // 延迟关闭，让通知可见
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
  const apiKey = editForm.value.apiKey.trim()
  if (!apiKey) {
    message.warning('请先填写 API Key')
    return
  }

  pullingModels.value = true
  try {
    const resolvedBaseUrl = editForm.value.baseUrl.trim() || providerMeta.value[providerKey]?.apiUrlPlaceholder || ''
    const responsePayload = await providerStore.pullProviderModels(providerKey, {
      baseUrl: resolvedBaseUrl,
      apiKey,
    })
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

    <!-- 服务商配置 -->
    <n-card title="服务商配置" :bordered="true" style="margin-top: 16px;">
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
        <div v-for="key in enabledProviderKeys" :key="key" class="provider-card" @click="openEditPanel(key)">
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

    <!-- 添加服务商模态框 -->
    <n-modal v-model:show="showAddModal" preset="card" title="添加服务商" :style="{ maxWidth: '480px' }" closable
      :mask-closable="true">
      <div v-if="disabledProviderKeys.length === 0" class="add-modal-empty">
        所有服务商已启用
      </div>
      <div v-else class="add-modal-grid">
        <div v-for="key in disabledProviderKeys" :key="key" class="add-modal-card"
          :class="{ 'add-modal-card--custom': isCustomProvider(key) }"
          @click="enableProvider(key)">
          <div class="add-modal-card-top" :class="providerMeta[key]?.colorClass || 'accent'"></div>
          <button v-if="isCustomProvider(key)" class="add-modal-card-edit" title="修改自定义供应商"
            @click.stop="openEditCustomModal(key)">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
          </button>
          <button v-if="isCustomProvider(key)" class="add-modal-card-delete" title="删除自定义供应商"
            @click.stop="removeCustomProvider(key)">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18" />
              <line x1="6" y1="6" x2="18" y2="18" />
            </svg>
          </button>
          <div class="add-modal-card-name">{{ providerMeta[key]?.displayName || key }}</div>
          <div class="add-modal-card-desc">{{ providerMeta[key]?.apiUrlPlaceholder || '' }}</div>
        </div>
        <!-- 自定义供应商入口 -->
        <div class="add-modal-card add-modal-card--new" @click="showAddModal = false; showCustomAddModal = true">
          <div class="add-modal-card-top accent"></div>
          <div class="add-modal-card-name">自定义供应商</div>
          <div class="add-modal-card-desc">标准 OpenAI 兼容接口</div>
        </div>
      </div>
    </n-modal>

    <!-- 自定义供应商名称输入模态框 -->
    <n-modal v-model:show="showCustomAddModal" preset="card" :title="editingCustomKey ? '修改自定义供应商' : '添加自定义供应商'"
      :style="{ maxWidth: '480px' }" closable :mask-closable="true"
      @update:show="(val: boolean) => { if (!val) resetCustomAdvanced() }">
      <div class="field-group">
        <label class="field-label">自定义供应商名称</label>
        <n-input v-model:value="customProviderName" placeholder="例如：MyAPI"
          @keyup.enter="addCustomProvider" />
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

        <!-- 请求体修剪 -->
        <div class="advanced-section">
          <div class="advanced-section-header">
            <span class="advanced-section-title">额外覆写或修剪请求体</span>
            <n-button text size="tiny" class="advanced-add-btn" @click="addCustomBodyTransform">
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
          <div v-if="customBodyTransforms.length === 0" class="advanced-empty">暂无自定义请求体修剪</div>
          <div v-for="(entry, idx) in customBodyTransforms" :key="idx" class="advanced-row">
            <n-input v-model:value="entry.key" placeholder="字段路径" class="advanced-input-key" />
            <n-input v-model:value="entry.value" placeholder="值（/del/ 表示删除）" class="advanced-input-value" />
            <button class="advanced-delete-btn" title="删除" @click="removeCustomBodyTransform(idx)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
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
          <n-input v-model:value="editForm.apiKey" type="password" placeholder="请输入 API Key" show-password-on="click" />
        </div>

        <ProviderModelsSection v-model:models="editForm.models" :pulling-models="pullingModels"
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

/* ── 添加服务商模态框 ── */
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

.add-modal-card-edit {
  position: absolute;
  top: 8px;
  right: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 999px;
  background: rgba(194, 122, 62, 0.08);
  color: $text-muted;
  cursor: pointer;
  transition: all 0.2s ease;
  z-index: 2;

  &:hover {
    color: $accent;
    background: rgba(194, 122, 62, 0.15);
  }
}

.add-modal-card-delete {
  position: absolute;
  top: 8px;
  right: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
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

  /* 添加服务商模态框网格单列 */
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