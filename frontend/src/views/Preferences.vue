<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NCard, NInput, NButton, useMessage } from 'naive-ui'
import { useProviderStore } from '@/stores/providers'

const providerStore = useProviderStore()
const message = useMessage()

const fakeVersion = ref('')
const versionPlaceholder = ref('0.6.4')

onMounted(async () => {
  await providerStore.fetchFakeVersion()
  if (providerStore.fakeVersion) {
    fakeVersion.value = providerStore.fakeVersion
    versionPlaceholder.value = providerStore.fakeVersion
  }
})

async function saveFakeVersion() {
  await providerStore.saveFakeVersion(fakeVersion.value)
  versionPlaceholder.value = fakeVersion.value
  message.success('版本号已保存')
}
</script>

<template>
  <div class="preferences-page">
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
</style>
