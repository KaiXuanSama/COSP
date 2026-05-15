<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NCard, NInput, NButton, NSwitch, NTag, NDrawer, NDrawerContent, NIcon, NCheckbox, NForm, NFormItem, useMessage } from 'naive-ui'
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
        <div v-for="(meta, key) in providerMeta" :key="key" class="provider-card" @click="openEditPanel(key)">
          <div class="provider-card-top" :class="meta.colorClass"></div>
          <div class="provider-card-header">
            <span class="provider-card-name">{{ meta.displayName }}</span>
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

    <!-- 侧滑面板 -->
    <n-drawer :show="!!editingKey" :width="480" placement="right" @mask-click="closeEditPanel" @esc="closeEditPanel">
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
                      <n-input v-model:value="m.contextSize" placeholder="4096" />
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
</style>