<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { NCard, NInput, NButton, NSwitch, NTag, NDrawer, NDrawerContent, NModal, NCheckbox, NForm, NFormItem, NPopselect, useMessage } from 'naive-ui'
import { useProviderStore } from '@/stores/providers'

const providerStore = useProviderStore()
const message = useMessage()
const fakeVersion = ref('')
const versionPlaceholder = ref('0.6.4')

const windowWidth = ref(window.innerWidth)
const drawerWidth = computed(() => Math.min(480, windowWidth.value - 16))

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
  return key ? providerMeta[key]?.docsUrl ?? '' : ''
})

const activeDocsTitle = computed(() => {
  const key = docsWindow.value.providerKey
  return key ? `${providerMeta[key]?.displayName ?? key} 官方文档` : '官方文档'
})

onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const providerMeta: Record<string, { displayName: string; colorClass: string; apiUrlPlaceholder: string; docsUrl: string }> = {
  longcat: {
    displayName: 'LongCat',
    colorClass: 'accent',
    apiUrlPlaceholder: 'https://api.longcat.chat',
    docsUrl: 'https://longcat.chat/platform/docs/zh/#%E5%8D%95%E6%AC%A1%E8%AF%B7%E6%B1%82%E9%99%90%E5%88%B6',
  },
  mimo: {
    displayName: 'MiMo',
    colorClass: 'blue',
    apiUrlPlaceholder: 'https://token-plan-cn.xiaomimimo.com/',
    docsUrl: 'https://platform.xiaomimimo.com/docs/zh-CN/pricing',
  },
  sensenova: {
    displayName: 'SenseNova',
    colorClass: 'success',
    apiUrlPlaceholder: 'https://token.sensenova.cn',
    docsUrl: 'https://platform.sensenova.cn/docs',
  },
  deepseek: {
    displayName: 'DeepSeek',
    colorClass: 'warning',
    apiUrlPlaceholder: 'https://api.deepseek.com',
    docsUrl: 'https://api-docs.deepseek.com/zh-cn/quick_start/pricing',
  },
}

const contextPresets = [
  { label: '1M', value: '1000000' },
  { label: '512K', value: '512000' },
  { label: '256K', value: '256000' },
  { label: '128K', value: '128000' },
  { label: '64K', value: '64000' },
]

const editingKey = ref<string | null>(null)
const editForm = ref({
  baseUrl: '',
  apiKey: '',
  models: [] as any[],
})

const showAddModal = ref(false)

const enabledProviderKeys = computed(() =>
  Object.keys(providerMeta).filter(k => providerStore.providers[k]?.enabled)
)

const disabledProviderKeys = computed(() =>
  Object.keys(providerMeta).filter(k => !providerStore.providers[k]?.enabled)
)

watch(editingKey, (key) => {
  if (docsWindow.value.visible && key) {
    docsWindow.value.providerKey = key
  }
})

async function enableProvider(key: string) {
  await providerStore.toggleProvider(key, true)
  message.success(`${providerMeta[key]?.displayName || key} 已启用`)
  showAddModal.value = false
}

onMounted(async () => {
  await providerStore.fetchAll()
  await providerStore.fetchFakeVersion()
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
      baseUrl: p.baseUrl || '',
      apiKey: p.apiKey || '',
      models: p.models.map(m => ({
        ...m,
        contextSize: String(m.contextSize ?? '0'),
      })),
    }
  }
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
  })
  try {
    await providerStore.saveProviderConfig(key, params)
    // 更新本地缓存
    if (providerStore.providers[key]) {
      providerStore.providers[key].baseUrl = editForm.value.baseUrl
      providerStore.providers[key].apiKey = editForm.value.apiKey
      providerStore.providers[key].models = editForm.value.models.map(m => ({
        ...m,
        contextSize: String(m.contextSize ?? '0'),
      }))
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
    message.success(`${providerMeta[key]?.displayName || key} 已启用`)
  } else {
    message.info(`${providerMeta[key]?.displayName || key} 已禁用`)
  }
}

async function saveFakeVersion() {
  await providerStore.saveFakeVersion(fakeVersion.value)
  versionPlaceholder.value = fakeVersion.value
  message.success('版本号已保存')
}

function addModel() {
  editForm.value.models.push({
    modelName: '',
    enabled: true,
    contextSize: '0',
    capsTools: false,
    capsVision: false,
  })
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
          <div class="provider-card-top" :class="providerMeta[key].colorClass"></div>
          <div class="provider-card-header">
            <span class="provider-card-name">{{ providerMeta[key].displayName }}</span>
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
        <div v-for="key in disabledProviderKeys" :key="key" class="add-modal-card" @click="enableProvider(key)">
          <div class="add-modal-card-top" :class="providerMeta[key].colorClass"></div>
          <div class="add-modal-card-name">{{ providerMeta[key].displayName }}</div>
          <div class="add-modal-card-desc">{{ providerMeta[key].apiUrlPlaceholder }}</div>
        </div>
      </div>
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

        <div class="field-group">
          <div class="field-label-row">
            <label class="field-label">模型列表</label>
            <div class="field-label-actions">
              <n-button text size="tiny" @click="openOfficialDocs" class="docs-window-btn">
                <template #icon>
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                    stroke-linecap="round" stroke-linejoin="round">
                    <path d="M6 3h9l3 3v15H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2" />
                    <path d="M15 3v4h4" />
                  </svg>
                </template>
                官方文档
              </n-button>
              <n-button text size="tiny" @click="addModel" class="add-model-btn">
                <template #icon>
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                    stroke-linecap="round" stroke-linejoin="round">
                    <line x1="12" y1="5" x2="12" y2="19" />
                    <line x1="5" y1="12" x2="19" y2="12" />
                  </svg>
                </template>
                添加模型
              </n-button>
            </div>
          </div>
          <div v-for="(m, i) in editForm.models" :key="i" class="model-card">
            <div class="model-card-inner">
              <div class="model-checkbox-col">
                <n-checkbox v-model:checked="m.enabled" />
              </div>
              <div class="model-divider"></div>
              <div class="model-content-col">
                <n-form :model="m" label-placement="left" :show-feedback="false" size="small">
                  <div class="model-form-row">
                    <n-form-item label="模型id" class="model-name-item">
                      <n-input v-model:value="m.modelName" placeholder="模型名称" />
                    </n-form-item>
                  </div>
                  <div class="model-form-row model-form-row--details">
                    <n-form-item label="上下文" class="model-detail-item model-detail-item--grow">
                      <n-input v-model:value="m.contextSize" placeholder="4096">
                        <template #suffix>
                          <n-popselect :options="contextPresets" size="small" trigger="click"
                            @update:value="(val: string) => m.contextSize = val">
                            <span class="context-preset-trigger">
                              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                <polyline points="6 9 12 15 18 9" />
                              </svg>
                            </span>
                          </n-popselect>
                        </template>
                      </n-input>
                    </n-form-item>
                    <n-form-item label="工具" class="model-detail-item model-detail-item--switch">
                      <n-switch v-model:value="m.capsTools" size="small" />
                    </n-form-item>
                    <n-form-item label="视觉" class="model-detail-item model-detail-item--switch">
                      <n-switch v-model:value="m.capsVision" size="small" />
                    </n-form-item>
                  </div>
                </n-form>
              </div>
              <div class="model-divider"></div>
              <div class="model-remove-col">
                <n-button tertiary circle size="small" @click="removeModel(i)" class="model-remove-btn" title="删除此模型">
                  <template #icon>
                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                      stroke-linecap="round" stroke-linejoin="round">
                      <polyline points="3 6 5 6 21 6" />
                      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                    </svg>
                  </template>
                </n-button>
              </div>
            </div>
          </div>
        </div>

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

.field-label-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.field-label-actions {
  display: flex;
  align-items: center;
  gap: $space-xs;
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

.model-card {
  background: $bg;
  border: 1px solid $border;
  border-radius: $radius;
  padding: $space-sm;
  margin-bottom: $space-sm;
}

.model-card-inner {
  display: flex;
  gap: $space-sm;
}

.model-checkbox-col {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 20px;
}

.model-divider {
  width: 1px;
  flex-shrink: 0;
  background: $border-light;
  align-self: stretch;
  margin: 2px 0;
}

.model-content-col {
  flex: 1;
  min-width: 0;
}

.model-remove-col {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 28px;
}

.model-form-row {
  display: flex;
  align-items: center;
  gap: $space-sm;

  &--details {
    margin-top: 2px;
  }
}

.model-name-item {
  flex: 1;
  margin-bottom: 0 !important;

  :deep(.n-form-item-label) {
    font-family: $font-mono;
    font-size: 10px;
    font-weight: 500;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: $text-muted;
    width: 45px;
    flex-shrink: 0;
    padding-right: 8px;
    text-align: left;
  }

  :deep(.n-form-item-blank) {
    flex: 1;
  }
}

.model-detail-item {
  margin-bottom: 0 !important;

  :deep(.n-form-item-label) {
    font-family: $font-mono;
    font-size: 10px;
    font-weight: 500;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: $text-muted;
    width: 45px;
    flex-shrink: 0;
    padding-right: 6px;
    text-align: left;
  }

  :deep(.n-form-item-blank) {
    flex: 1;
  }

  &--grow {
    flex: 1;
    min-width: 0;
  }

  &--switch {
    flex-shrink: 0;

    :deep(.n-form-item-label) {
      width: auto;
      padding-right: 4px;
    }

    :deep(.n-form-item-blank) {
      flex: 0;
    }
  }
}

.model-remove-btn {
  flex-shrink: 0;
  color: $text-muted !important;

  &:hover {
    color: $danger !important;
  }
}

.add-model-btn {
  flex-shrink: 0;
  color: $text-muted !important;

  &:hover {
    color: $accent !important;
  }
}

/* ── 上下文预设下拉指示器 ── */
.context-preset-trigger {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  cursor: pointer;
  color: $text-muted;
  border-radius: 4px;
  transition: all 0.2s ease;

  &:hover {
    color: $accent;
    background: $accent-light;
  }
}

.add-provider-btn {
  flex-shrink: 0;
  color: $text-muted !important;

  &:hover {
    color: $accent !important;
  }
}

.docs-window-btn {
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

/* ── 移动端抽屉适配 ── */
@media (max-width: 768px) {
  .edit-drawer {
    :deep(.n-drawer-content) {
      overflow-x: hidden;
    }
  }

  /* 模型行在小屏时换行排列 */
  .model-form-row--details {
    flex-wrap: wrap;

    .model-detail-item--grow {
      flex: 1 1 100%;
      min-width: 0;
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