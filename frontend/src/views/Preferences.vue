<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NCard, NInput, NButton, NSwitch, useMessage } from 'naive-ui'
import { useProviderStore } from '@/stores/providers'
import { copyToClipboard } from '@/utils/clipboard'

const providerStore = useProviderStore()
const message = useMessage()

const fakeVersion = ref('')
const versionPlaceholder = ref('0.6.4')

// ── 下游鉴权管理 ──
const gatewayEnabled = ref(false)
const gatewayMaskedKey = ref('')
const gatewayConfigured = ref(false)
const gatewayToggling = ref(false)
const gatewayCopying = ref(false)
const gatewayRegenerating = ref(false)

onMounted(async () => {
  await providerStore.fetchFakeVersion()
  if (providerStore.fakeVersion) {
    fakeVersion.value = providerStore.fakeVersion
    versionPlaceholder.value = providerStore.fakeVersion
  }
  await loadGatewayAuth()
})

async function saveFakeVersion() {
  await providerStore.saveFakeVersion(fakeVersion.value)
  versionPlaceholder.value = fakeVersion.value
  message.success('版本号已保存')
}

async function loadGatewayAuth() {
  try {
    const status = await providerStore.fetchGatewayAuth()
    gatewayEnabled.value = status.enabled
    gatewayMaskedKey.value = status.maskedKey
    gatewayConfigured.value = status.configured
  } catch {
    // 读取失败时保持默认关闭态
  }
}

async function onGatewayToggle(value: boolean) {
  gatewayToggling.value = true
  try {
    await providerStore.setGatewayAuthEnabled(value)
    gatewayEnabled.value = value
    message.success(value ? '下游鉴权已开启' : '下游鉴权已关闭')
  } catch {
    message.error('切换失败，请重试')
  } finally {
    gatewayToggling.value = false
  }
}

async function copyGatewayKey() {
  if (!gatewayConfigured.value) {
    message.warning('尚未生成 API Key')
    return
  }
  gatewayCopying.value = true
  try {
    const plaintext = await providerStore.revealGatewayApiKey()
    if (!plaintext) {
      message.warning('尚未生成 API Key')
      return
    }
    const ok = await copyToClipboard(plaintext)
    message[ok ? 'success' : 'error'](ok ? 'API Key 已复制到剪贴板' : '复制失败')
  } catch {
    message.error('复制失败，请重试')
  } finally {
    gatewayCopying.value = false
  }
}

async function regenerateGatewayKey() {
  gatewayRegenerating.value = true
  try {
    const result = await providerStore.regenerateGatewayApiKey()
    gatewayMaskedKey.value = result.maskedKey
    gatewayConfigured.value = true
    const ok = await copyToClipboard(result.apiKey)
    message[ok ? 'success' : 'warning'](
      ok ? '新 API Key 已生成并复制到剪贴板' : '新 API Key 已生成，但自动复制失败'
    )
  } catch {
    message.error('生成失败，请重试')
  } finally {
    gatewayRegenerating.value = false
  }
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

    <!-- 下游鉴权管理 -->
    <n-card title="下游鉴权管理" :bordered="true" style="margin-top: 16px;">
      <div class="gateway-auth-row">
        <n-switch :value="gatewayEnabled" :loading="gatewayToggling" @update:value="onGatewayToggle" />
        <n-input class="gateway-key-input" :value="gatewayConfigured ? gatewayMaskedKey : ''"
          placeholder="尚未生成 API Key" readonly />
        <n-button class="key-action-btn" quaternary circle :loading="gatewayCopying" title="复制明文到剪贴板"
          @click="copyGatewayKey">
          <template #icon>
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8"
              stroke-linecap="round" stroke-linejoin="round">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
            </svg>
          </template>
        </n-button>
        <n-button class="key-action-btn" quaternary circle :loading="gatewayRegenerating" title="重新生成 API Key"
          @click="regenerateGatewayKey">
          <template #icon>
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8"
              stroke-linecap="round" stroke-linejoin="round">
              <polyline points="23 4 23 10 17 10" />
              <polyline points="1 20 1 14 7 14" />
              <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
            </svg>
          </template>
        </n-button>
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

.gateway-auth-row {
  display: flex;
  gap: $space-sm;
  align-items: center;
}

.gateway-key-input {
  flex: 1;
}

/* 复制 / 刷新控件：naive-ui 把 --n-text-color 作为内联样式写在按钮上，
   优先级高于 scoped class 里的同名变量覆盖，导致图标一直是浅色。
   这里直接用 :deep(svg) 强制 stroke（CSS stroke 覆盖 SVG 的 currentColor 呈现属性），
   彻底绕开 naive 变量链；再加淡边框 + 背景让按钮读起来像可点控件。 */
.key-action-btn {
  border: 1px solid $border;
  background: $bg;
  transition: border-color 0.2s ease, background 0.2s ease;

  :deep(svg) {
    stroke: $text-body;
    transition: stroke 0.2s ease;
  }

  &:hover {
    border-color: $accent;
    background: $accent-mid;

    :deep(svg) {
      stroke: $accent;
    }
  }
}
</style>
