<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NCard, NInput, NInputNumber, NButton, NSwitch, NModal, useMessage } from 'naive-ui'
import { useProviderStore } from '@/stores/providers'
import { copyToClipboard } from '@/utils/clipboard'
import { DEFAULT_PROXY_PORT, formatProxyAddress, parseProxyAddress } from '@/features/proxy-address/proxyAddress'

const providerStore = useProviderStore()
const message = useMessage()

const fakeVersion = ref('')
const versionPlaceholder = ref('0.6.4')

// ── 上游重试次数 ──
// 语义：-1 无限重试，0 不重试，正数为具体次数（都不含首次请求）。
// 上下限由后端下发，前端不自己维护一份常量 —— 否则改一侧不报错，
// 只会让用户在 UI 上选到一个被后端静默拒绝的值。
const retryMaxAttempts = ref<number | null>(5)
const retryDefaultValue = ref(5)
const retryMaxConfigurable = ref(100)
const retrySaving = ref(false)

// ── 出站代理 ──
// 后端用一个 host:port 字符串持久化，界面拆成两个输入框降低填写门槛。
// 主机留空后保存 = 清空代理地址，所有供应商直连。
const proxyHost = ref('')
const proxyPort = ref<number | null>(DEFAULT_PROXY_PORT)
const proxySaving = ref(false)

/** 把当前取值翻译成人话，避免用户对着 -1 / 0 猜语义。 */
const retryHint = computed(() => {
  const value = retryMaxAttempts.value
  if (value === -1) return '无限重试：上游持续失败时会一直重试，直到成功或你手动断连'
  if (value === 0) return '不重试：上游首次失败即直接返回错误'
  return `首次请求失败后最多再试 ${value} 次，退避 2 秒起、上限 30 秒`
})

// ── 下游鉴权管理 ──
const gatewayEnabled = ref(false)
const gatewayMaskedKey = ref('')
const gatewayConfigured = ref(false)
const gatewayToggling = ref(false)
const gatewayCopying = ref(false)
const gatewayRegenerating = ref(false)
const showGatewayHelp = ref(false)

onMounted(async () => {
  // 一次聚合调用拿全部运行时配置：伪造版本号 + 重试策略 + 出站代理 + 下游鉴权状态。
  try {
    const config = await providerStore.fetchRuntimeConfig()
    if (config.fakeVersion) {
      fakeVersion.value = config.fakeVersion
      versionPlaceholder.value = config.fakeVersion
    }
    gatewayEnabled.value = config.gatewayAuth.enabled
    gatewayMaskedKey.value = config.gatewayAuth.maskedKey
    gatewayConfigured.value = config.gatewayAuth.configured
    retryMaxAttempts.value = config.retryPolicy.maxAttempts
    retryDefaultValue.value = config.retryPolicy.defaultValue
    retryMaxConfigurable.value = config.retryPolicy.maxConfigurable
    const proxyAddress = parseProxyAddress(config.upstreamProxyAddress)
    proxyHost.value = proxyAddress.host
    proxyPort.value = proxyAddress.port
  } catch {
    // 读取失败时保持默认值
  }
})

async function saveRetryMaxAttempts() {
  const value = retryMaxAttempts.value
  if (value === null) {
    message.warning('请先填写重试次数')
    return
  }
  retrySaving.value = true
  try {
    await providerStore.saveRetryMaxAttempts(value)
    message.success(value === -1 ? '已设为无限重试' : `重试次数已设为 ${value}`)
  } catch {
    message.error('保存失败，请检查取值是否合法')
  } finally {
    retrySaving.value = false
  }
}

async function saveFakeVersion() {
  await providerStore.saveFakeVersion(fakeVersion.value)
  versionPlaceholder.value = fakeVersion.value
  message.success('版本号已保存')
}

async function saveProxyAddress() {
  const host = proxyHost.value.trim()
  const port = proxyPort.value
  if (host && (port === null || !Number.isInteger(port) || port < 1 || port > 65535)) {
    message.warning('代理端口必须是 1 到 65535 的整数')
    return
  }

  proxySaving.value = true
  try {
    const address = formatProxyAddress(host, port ?? DEFAULT_PROXY_PORT)
    await providerStore.saveProxyAddress(address)
    proxyHost.value = host
    message.success(address ? `代理地址已保存为 ${address}` : '代理地址已清空')
  } catch {
    message.error('代理地址保存失败，请重试')
  } finally {
    proxySaving.value = false
  }
}

async function onGatewayToggle(value: boolean) {
  // 未生成 Key 时禁止开启：即使后端按 1A 放行，开着没有 Key 的开关也毫无意义，
  // 这里前置拦截并 toast 提示，引导用户先生成 Key。
  if (value && !gatewayConfigured.value) {
    message.warning('请先生成 API Key 再开启下游鉴权')
    return
  }
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
    <!-- 伪造版本号 -->
    <n-card title="伪造版本号" :bordered="true">
      <div class="fake-version-row">
        <n-input id="fakeVersion" v-model:value="fakeVersion" :placeholder="versionPlaceholder"
          @keyup.enter="saveFakeVersion" />
        <n-button type="primary" @click="saveFakeVersion">保存</n-button>
      </div>
    </n-card>

    <!-- 出站代理 -->
    <n-card title="出站代理" :bordered="true" style="margin-top: 16px;">
      <div class="proxy-address-row">
        <n-input class="proxy-host-input" v-model:value="proxyHost" placeholder="代理 IP 或主机名，例如 127.0.0.1"
          clearable @keyup.enter="saveProxyAddress" />
        <n-input-number class="proxy-port-input" v-model:value="proxyPort" :min="1" :max="65535"
          :step="1" :precision="0" placeholder="端口" @keyup.enter="saveProxyAddress" />
        <n-button type="primary" :loading="proxySaving" @click="saveProxyAddress">保存</n-button>
      </div>
      <div class="proxy-address-note">
        只对已开启「使用代理」的供应商生效；未开启的供应商仍然直连。清空代理 IP 并保存可停用代理。
      </div>
    </n-card>

    <!-- 上游重试次数 -->
    <n-card title="上游重试次数" :bordered="true" style="margin-top: 16px;">
      <div class="retry-policy-row">
        <n-input-number class="retry-attempts-input" v-model:value="retryMaxAttempts" :min="-1"
          :max="retryMaxConfigurable" :step="1" :precision="0" placeholder="重试次数"
          @keyup.enter="saveRetryMaxAttempts" />
        <n-button type="primary" :loading="retrySaving" @click="saveRetryMaxAttempts">保存</n-button>
      </div>
      <div class="retry-policy-hint">{{ retryHint }}</div>
      <div class="retry-policy-note">
        对上游限速（429）、服务端错误（5xx）、网络中断与空响应兜底生效；
        401/403 这类确定性错误仍然立即失败。默认 {{ retryDefaultValue }} 次。
      </div>
    </n-card>
    <!-- 下游鉴权管理 -->
    <n-card :bordered="true" style="margin-top: 16px;">
      <template #header>
        <div class="gateway-card-header">
          <span class="gateway-card-title">下游鉴权管理</span>
          <button type="button" class="gateway-help-button" aria-label="查看下游鉴权说明"
            title="了解下游鉴权功能与 Copilot 接入方法" @click="showGatewayHelp = true">
            ?
          </button>
        </div>
      </template>
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

    <!-- 下游鉴权说明模态框 -->
    <n-modal :show="showGatewayHelp" @update:show="showGatewayHelp = $event" preset="card" title="下游鉴权管理说明"
      :style="{ width: '90vw', maxWidth: '640px', maxHeight: '85vh', display: 'flex', 'flex-direction': 'column' }"
      content-style="overflow: auto; flex: 1; min-height: 0" closable :mask-closable="true">
      <div class="gateway-help">
        <section class="gateway-help-section">
          <h3 class="gateway-help-title">这个功能是做什么的</h3>
          <p>
            下游鉴权为本服务对外的聊天接口设置一把「网关 API Key」，用于在把服务部署到公网时防止被他人盗用你配置的上游供应商额度。
          </p>
          <p>
            开启后，来自 Copilot 的聊天请求必须携带正确的 API Key 才会被放行；模型发现类请求（Ollama 的
            <code>/api/tags</code>、<code>/api/show</code> 等）不受影响，仍可正常探测。
          </p>
          <p class="gateway-help-note">
            提示：API Key 明文只在生成的那一刻显示并复制到剪贴板，之后仅保留脱敏形式。点击刷新会生成一把全新的
            Key 并使旧 Key 立即失效，请及时更新客户端配置。
          </p>
        </section>

        <section class="gateway-help-section">
          <h3 class="gateway-help-title">如何在 VS Code Copilot 的 Ollama 供应商中启用鉴权</h3>
          <p>
            VS Code 的 Ollama 供应商界面本身没有 API Key 输入框，但底层会把配置里的
            <code>apiKey</code> 字段作为 <code>Authorization: Bearer</code> 头发送。因此需要手动编辑配置文件：
          </p>
          <ol class="gateway-help-steps">
            <li>
              打开命令面板（<code>Ctrl+Shift+P</code>），运行
              <strong>Chat: Open Language Models (JSON)</strong>，
              或直接编辑 <code>chatLanguageModels.json</code>。
            </li>
            <li>
              找到指向本服务的 Ollama 供应商条目，添加 <code>apiKey</code> 字段（注意是驼峰拼写）：
              <pre class="gateway-help-code">{
  "name": "COSP",
  "vendor": "ollama",
  "apiKey": "在此粘贴复制到的 API Key",
  "url": "http://你的服务地址:11434"
}</pre>
            </li>
            <li>保存后重新加载 VS Code 窗口（<strong>Developer: Reload Window</strong>），让配置生效。</li>
            <li>之后选中本服务的模型发起聊天，请求会自动带上 Key，鉴权通过即可正常使用。</li>
          </ol>
          <p class="gateway-help-note">
            字段名必须是 <code>apiKey</code>（驼峰），写成 <code>apikey</code> 等其它形式不会被识别，会导致发送空的鉴权头。
          </p>
        </section>
      </div>
    </n-modal>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.fake-version-row {
  display: flex;
  gap: $space-sm;
  align-items: center;
}

.proxy-address-row {
  display: flex;
  gap: $space-sm;
  align-items: center;
}

.proxy-host-input {
  flex: 1;
}

.proxy-port-input {
  width: 132px;
}

.proxy-address-note {
  margin-top: $space-sm;
  font-size: 12px;
  color: $text-muted;
  line-height: 1.6;
}

.retry-policy-row {
  display: flex;
  gap: $space-sm;
  align-items: center;
}

/* 与 .fake-version-row 里的 n-input 对齐：输入框吃掉剩余宽度，
   「保存」因此落在与版本号卡片同一列的右缘上。n-input-number 默认
   宽度由内容决定，不撑开就会让两张卡片的按钮错位。 */
.retry-attempts-input {
  flex: 1;
}

/* 取值语义提示：随输入实时变化，比让用户对着 -1 / 0 猜含义更直接。 */
.retry-policy-hint {
  margin-top: $space-sm;
  font-size: 13px;
  color: $text-body;
}

/* 生效范围说明：静态文案，字号更小以区分于随值变化的提示。 */
.retry-policy-note {
  margin-top: 4px;
  font-size: 12px;
  color: $text-muted;
  line-height: 1.6;
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

/* 标题右侧问号：样式参考请求体规则帮助按钮 */
.gateway-card-header {
  display: flex;
  align-items: center;
  gap: $space-sm;
}

.gateway-card-title {
  font-size: 18px;
}

.gateway-help-button {
  display: inline-flex;
  width: 19px;
  height: 19px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid rgba($accent, 0.42);
  border-radius: 50%;
  background: $accent-light;
  color: $accent;
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease, transform 0.16s ease;
}

.gateway-help-button:hover {
  border-color: $accent;
  background: $accent-mid;
  transform: translateY(-1px);
}

.gateway-help-button:focus-visible {
  outline: 2px solid $accent-glow;
  outline-offset: 2px;
}

/* 帮助模态框内容排版 */
.gateway-help-section + .gateway-help-section {
  margin-top: $space-lg;
}

.gateway-help-title {
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
  margin-bottom: $space-sm;
}

.gateway-help p {
  color: $text-body;
  line-height: 1.7;
  margin-bottom: $space-sm;
}

.gateway-help-note {
  font-size: 13px;
  color: $text-muted;
}

.gateway-help-steps {
  padding-left: 1.4em;
  color: $text-body;
  line-height: 1.7;

  li {
    margin-bottom: $space-sm;
  }
}

.gateway-help code {
  font-family: $font-mono;
  font-size: 0.88em;
  padding: 1px 5px;
  border-radius: 4px;
  background: $accent-light;
  color: $accent;
}

.gateway-help-code {
  display: block;
  margin-top: $space-xs;
  padding: $space-sm $space-md;
  border-radius: $radius;
  background: $sidebar-bg;
  color: $text-light;
  font-family: $font-mono;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
  overflow-x: auto;
}
</style>
