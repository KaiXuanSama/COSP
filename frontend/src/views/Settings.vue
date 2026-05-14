<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useProviderStore } from '@/stores/providers'

const providerStore = useProviderStore()
const fakeVersion = ref('')
const saveMsg = ref('')

const providerMeta: Record<string, { displayName: string; colorClass: string; apiUrlPlaceholder: string; docUrl: string }> = {
  longcat: { displayName: 'LongCat', colorClass: 'accent', apiUrlPlaceholder: 'https://api.longcat.chat', docUrl: 'https://longcat.chat/platform/docs/zh/' },
  mimo: { displayName: 'MiMo', colorClass: 'blue', apiUrlPlaceholder: 'https://token-plan-cn.xiaomimimo.com/', docUrl: 'https://platform.xiaomimimo.com/docs/zh-CN/pricing' },
  sensenova: { displayName: 'SenseNova', colorClass: 'success', apiUrlPlaceholder: 'https://token.sensenova.cn', docUrl: 'https://platform.sensenova.cn/docs' },
  deepseek: { displayName: 'DeepSeek', colorClass: 'warning', apiUrlPlaceholder: 'https://api.deepseek.com', docUrl: 'https://api-docs.deepseek.com/zh-cn/' },
}

const editingKey = ref<string | null>(null)
const editForm = ref({
  baseUrl: '',
  apiKey: '',
  models: [] as any[],
})

onMounted(async () => {
  await providerStore.fetchAll()
  // Load fake version from the settings page
  try {
    const res = await fetch('/config/settings')
    // We'll just load providers for now
  } catch {}
})

function openEditPanel(key: string) {
  editingKey.value = key
  const p = providerStore.providers[key]
  if (p) {
    editForm.value = {
      baseUrl: p.baseUrl || '',
      apiKey: p.apiKey || '',
      models: p.models.map(m => ({ ...m })),
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
    saveMsg.value = '保存成功'
    setTimeout(() => saveMsg.value = '', 2000)
    closeEditPanel()
  } catch {
    saveMsg.value = '保存失败'
  }
}

async function toggleProvider(key: string) {
  const p = providerStore.providers[key]
  if (p) {
    await providerStore.toggleProvider(key, !p.enabled)
  }
}

async function saveFakeVersion() {
  await providerStore.saveFakeVersion(fakeVersion.value)
  saveMsg.value = '版本号已保存'
  setTimeout(() => saveMsg.value = '', 2000)
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
    <div v-if="saveMsg" class="message success">{{ saveMsg }}</div>

    <!-- 运行配置 -->
    <div class="card">
      <div class="card-title">运行配置</div>
      <div class="card-body">
        <div class="field-group">
          <label class="field-label" for="fakeVersion">伪造版本号</label>
          <div class="field-input-wrap">
            <input class="field-input" id="fakeVersion" type="text" placeholder="0.6.4" v-model="fakeVersion" @keyup.enter="saveFakeVersion">
          </div>
        </div>
      </div>
    </div>

    <!-- 服务商配置 -->
    <div class="card" style="margin-top: 16px;">
      <div class="card-title">服务商配置</div>
      <div class="card-body">
        <div class="provider-grid">
          <div v-for="(meta, key) in providerMeta" :key="key" class="provider-card" @click="openEditPanel(key)">
            <div class="provider-card-top" :class="meta.colorClass"></div>
            <div class="provider-card-header">
              <span class="provider-card-name">{{ meta.displayName }}</span>
              <label class="toggle-switch" @click.stop @change="toggleProvider(key)">
                <input type="checkbox" :checked="providerStore.providers[key]?.enabled" @change="toggleProvider(key)">
                <span class="toggle-slider"></span>
              </label>
            </div>
            <div class="provider-card-status">
              <span v-if="providerStore.providers[key]?.enabled" class="status-badge success">已启用</span>
              <span v-else class="status-badge">已禁用</span>
            </div>
            <div class="provider-card-models">
              <span v-if="providerStore.providers[key]?.models?.length">
                {{ providerStore.providers[key].models.filter(m => m.enabled).length }} 个模型
              </span>
              <span v-else class="text-muted">未配置</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 侧滑面板 -->
    <Transition name="panel">
      <div v-if="editingKey" class="slide-panel-overlay" @click="closeEditPanel"></div>
    </Transition>
    <Transition name="panel">
      <div v-if="editingKey" class="slide-panel">
        <div class="slide-panel-header">
          <button class="slide-panel-back" @click="closeEditPanel">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="15 18 9 12 15 6"></polyline>
            </svg>
            返回
          </button>
          <span class="slide-panel-title">{{ providerMeta[editingKey]?.displayName }}</span>
          <button class="submit-btn slide-panel-save" @click="saveEditPanel">
            <span>保存</span>
          </button>
        </div>
        <div class="slide-panel-body">
          <div class="field-group">
            <label class="field-label">API 地址</label>
            <div class="field-input-wrap">
              <input class="field-input" type="text" v-model="editForm.baseUrl" :placeholder="providerMeta[editingKey]?.apiUrlPlaceholder">
            </div>
          </div>
          <div class="field-group">
            <label class="field-label">API Key</label>
            <div class="field-input-wrap">
              <input class="field-input" type="password" v-model="editForm.apiKey" placeholder="请输入 API Key">
            </div>
          </div>

          <div class="field-group">
            <label class="field-label">模型列表</label>
            <div v-for="(m, i) in editForm.models" :key="i" class="model-row">
              <input class="field-input model-input" type="text" v-model="m.modelName" placeholder="模型名称">
              <label class="toggle-switch model-toggle" title="启用">
                <input type="checkbox" v-model="m.enabled">
                <span class="toggle-slider"></span>
              </label>
              <button class="model-remove" @click="removeModel(i)" title="删除">×</button>
            </div>
            <button class="add-model-btn" @click="addModel">+ 添加模型</button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

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

  &.accent { background: $accent; }
  &.blue { background: $blue; }
  &.success { background: $success; }
  &.warning { background: $warning; }
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

/* ── 开关 ── */
.toggle-switch {
  position: relative;
  display: inline-block;
  width: 36px;
  height: 20px;
  cursor: pointer;

  input {
    position: absolute;
    opacity: 0;
    width: 0;
    height: 0;
  }
}

.toggle-slider {
  position: absolute;
  inset: 0;
  background: $border;
  border-radius: 10px;
  transition: background 0.25s ease;

  &::before {
    content: '';
    position: absolute;
    left: 2px;
    top: 2px;
    width: 16px;
    height: 16px;
    background: $surface;
    border-radius: 50%;
    transition: transform 0.25s ease;
  }
}

.toggle-switch input:checked + .toggle-slider {
  background: $accent;

  &::before {
    transform: translateX(16px);
  }
}

/* ── 状态徽章 ── */
.status-badge {
  display: inline-block;
  padding: 2px 10px;
  font-family: $font-mono;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  border-radius: 20px;
  background: rgba($text-muted, 0.1);
  color: $text-muted;

  &.success {
    background: rgba($success, 0.1);
    color: $success;
  }
}

/* ── 侧滑面板 ── */
.slide-panel-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: 200;
}

.slide-panel {
  position: fixed;
  top: 0;
  right: 0;
  width: 480px;
  max-width: 100vw;
  height: 100vh;
  background: $surface;
  z-index: 210;
  display: flex;
  flex-direction: column;
  box-shadow: $shadow-lg;
  overflow-y: auto;
}

/* ── Vue Transition 动画 ── */
.panel-enter-active {
  transition: all 0.3s ease;
}
.panel-leave-active {
  transition: all 0.2s ease;
}

/* 遮罩层：淡入淡出 */
.slide-panel-overlay.panel-enter-from,
.slide-panel-overlay.panel-leave-to {
  opacity: 0;
}
.slide-panel-overlay.panel-enter-to {
  opacity: 1;
}

/* 面板：从右侧滑入 */
.slide-panel.panel-enter-from,
.slide-panel.panel-leave-to {
  transform: translateX(100%);
}
.slide-panel.panel-enter-to {
  transform: translateX(0);
}

.slide-panel-header {
  display: flex;
  align-items: center;
  padding: $space-md $space-lg;
  border-bottom: 1px solid $border;
  gap: $space-sm;
}

.slide-panel-back {
  display: flex;
  align-items: center;
  gap: 4px;
  font-family: $font-body;
  font-size: 14px;
  color: $text-muted;
  padding: 4px 8px;
  border-radius: $radius;
  transition: all 0.2s ease;

  svg {
    width: 16px;
    height: 16px;
  }

  &:hover {
    color: $text-primary;
    background: $accent-light;
  }
}

.slide-panel-title {
  flex: 1;
  font-family: $font-display;
  font-size: 18px;
  font-weight: 600;
  color: $text-primary;
}

.slide-panel-save {
  padding: 8px 20px;
  font-size: 15px;
}

.slide-panel-body {
  flex: 1;
  overflow-y: auto;
  padding: $space-lg;
}

/* ── 模型行 ── */
.model-row {
  display: flex;
  align-items: center;
  gap: $space-sm;
  margin-bottom: $space-sm;
}

.model-input {
  flex: 1;
}

.model-toggle {
  flex-shrink: 0;
}

.model-remove {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: $text-muted;
  border-radius: 50%;
  transition: all 0.2s ease;
  flex-shrink: 0;

  &:hover {
    color: $danger;
    background: rgba($danger, 0.1);
  }
}

.add-model-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  font-family: $font-mono;
  font-size: 12px;
  color: $accent;
  border: 1px dashed $accent;
  border-radius: $radius;
  transition: all 0.2s ease;

  &:hover {
    background: $accent-light;
  }
}
</style>