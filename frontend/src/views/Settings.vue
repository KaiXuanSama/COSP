<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NCard, NInput, NButton, NSwitch, NTag, NDrawer, NDrawerContent, NIcon, useMessage } from 'naive-ui'
import { useProviderStore } from '@/stores/providers'

const providerStore = useProviderStore()
const message = useMessage()
const fakeVersion = ref('')

const providerMeta: Record<string, { displayName: string; colorClass: string; apiUrlPlaceholder: string }> = {
  longcat: { displayName: 'LongCat', colorClass: 'accent', apiUrlPlaceholder: 'https://api.longcat.chat' },
  mimo: { displayName: 'MiMo', colorClass: 'blue', apiUrlPlaceholder: 'https://token-plan-cn.xiaomimimo.com/' },
  sensenova: { displayName: 'SenseNova', colorClass: 'success', apiUrlPlaceholder: 'https://token.sensenova.cn' },
  deepseek: { displayName: 'DeepSeek', colorClass: 'warning', apiUrlPlaceholder: 'https://api.deepseek.com' },
}

const editingKey = ref<string | null>(null)
const editForm = ref({
  baseUrl: '',
  apiKey: '',
  models: [] as any[],
})

onMounted(async () => {
  await providerStore.fetchAll()
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
    message.success('保存成功')
    closeEditPanel()
  } catch {
    message.error('保存失败')
  }
}

async function toggleProvider(key: string, val: boolean) {
  await providerStore.toggleProvider(key, val)
}

async function saveFakeVersion() {
  await providerStore.saveFakeVersion(fakeVersion.value)
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
          <n-input id="fakeVersion" v-model:value="fakeVersion" placeholder="0.6.4" @keyup.enter="saveFakeVersion" />
          <n-button type="primary" @click="saveFakeVersion">保存</n-button>
        </div>
      </div>
    </n-card>

    <!-- 服务商配置 -->
    <n-card title="服务商配置" :bordered="true" style="margin-top: 16px;">
      <div class="provider-grid">
        <div
          v-for="(meta, key) in providerMeta"
          :key="key"
          class="provider-card"
          @click="openEditPanel(key)"
        >
          <div class="provider-card-top" :class="meta.colorClass"></div>
          <div class="provider-card-header">
            <span class="provider-card-name">{{ meta.displayName }}</span>
            <n-switch
              :value="providerStore.providers[key]?.enabled"
              @click.stop
              @update:value="(val) => toggleProvider(key, val)"
            />
          </div>
          <div class="provider-card-status">
            <n-tag v-if="providerStore.providers[key]?.enabled" type="success" size="small" :bordered="false">已启用</n-tag>
            <n-tag v-else size="small" :bordered="false">已禁用</n-tag>
          </div>
          <div class="provider-card-models">
            <span v-if="providerStore.providers[key]?.models?.length">
              {{ providerStore.providers[key].models.filter((m: any) => m.enabled).length }} 个模型
            </span>
            <span v-else class="text-muted">未配置</span>
          </div>
        </div>
      </div>
    </n-card>

    <!-- 侧滑面板 -->
    <n-drawer :show="!!editingKey" :width="480" placement="right" @mask-click="closeEditPanel" @esc="closeEditPanel">
      <n-drawer-content :title="editingKey ? providerMeta[editingKey]?.displayName : ''" closable @close="closeEditPanel">
        <div class="field-group">
          <label class="field-label">API 地址</label>
          <n-input v-model:value="editForm.baseUrl" :placeholder="editingKey ? providerMeta[editingKey]?.apiUrlPlaceholder : ''" />
        </div>
        <div class="field-group">
          <label class="field-label">API Key</label>
          <n-input v-model:value="editForm.apiKey" type="password" placeholder="请输入 API Key" show-password-on="click" />
        </div>

        <div class="field-group">
          <label class="field-label">模型列表</label>
          <div v-for="(m, i) in editForm.models" :key="i" class="model-row">
            <n-input v-model:value="m.modelName" placeholder="模型名称" class="model-input" />
            <n-switch v-model:value="m.enabled" class="model-toggle" />
            <n-button quaternary circle size="small" @click="removeModel(i)" class="model-remove-btn">
              <template #icon>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <line x1="18" y1="6" x2="6" y2="18" />
                  <line x1="6" y1="6" x2="18" y2="18" />
                </svg>
              </template>
            </n-button>
          </div>
          <n-button dashed size="small" @click="addModel" class="add-model-btn">
            <template #icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19" />
                <line x1="5" y1="12" x2="19" y2="12" />
              </svg>
            </template>
            添加模型
          </n-button>
        </div>

        <template #footer>
          <n-button type="primary" block @click="saveEditPanel">保存</n-button>
        </template>
      </n-drawer-content>
    </n-drawer>
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
  margin-bottom: 6px;
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

.model-remove-btn {
  flex-shrink: 0;
}

.add-model-btn {
  margin-top: $space-sm;
}
</style>