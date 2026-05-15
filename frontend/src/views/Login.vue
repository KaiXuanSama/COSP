<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { NInput, NButton, NCheckbox, NForm, NFormItem, useMessage } from 'naive-ui'

const route = useRoute()
const message = useMessage()
const formRef = ref<InstanceType<typeof NForm> | null>(null)
const formValue = ref({ username: '', password: '' })
const loading = ref(false)

const unauthorized = !!route.query.unauthorized
const logout = route.query.login === 'logout'
const loginError = route.query.login === 'error'

if (unauthorized) message.warning('无权限，请先登录。')
if (logout) message.info('已成功退出登录。')
if (loginError) message.error('用户名或密码错误。')

async function handleSubmit() {
  loading.value = true
  try {
    const body = new URLSearchParams({
      username: formValue.value.username,
      password: formValue.value.password,
    })
    // 直接使用 fetch 发送登录请求，避免 Axios 自动跟随 Spring Security 的 302 重定向
    const res = await fetch('/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
      redirect: 'manual',
    })
    if (res.type === 'opaqueredirect' || res.status === 302) {
      // Spring Security 登录成功 → 302 到 /overview
      window.location.href = '/overview'
    } else {
      // 登录失败 → 重定向到登录页带 error 参数
      window.location.href = '/login?login=error'
    }
  } catch {
    window.location.href = '/login?login=error'
  }
}
</script>

<template>
  <div class="login-page">
    <section class="brand-panel">
      <div class="brand-logo">
        <svg class="brand-logo-icon" viewBox="0 0 28 28" fill="none">
          <circle cx="14" cy="14" r="12" stroke="currentColor" stroke-width="1.5" />
          <path d="M8 14h12M14 8v12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        </svg>
        COSP
      </div>
      <div class="brand-text">
        <h2 class="brand-tagline">管理后台</h2>
        <p class="brand-desc">
          专为 GitHub Copilot 设计的 Ollama 代理中转服务。<br>
          让 Copilot 轻松对接多种 AI 模型！
        </p>
      </div>
      <div class="dot-grid">
        <span v-for="i in 25" :key="i"></span>
      </div>
    </section>

    <section class="form-panel">
      <div class="form-header">
        <h1>欢迎回来</h1>
        <p>请输入管理员账号密码以访问配置页面。</p>
      </div>

      <n-form ref="formRef" :model="formValue" label-placement="top" :show-label="false" :show-feedback="false"
        size="large" @submit.prevent="handleSubmit">
        <n-form-item path="username">
          <n-input v-model:value="formValue.username" type="text" placeholder="请输入用户名" autofocus :input-props="{
            name: 'username',
            autocomplete: 'username',
            required: true,
          }">
            <template #prefix>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                stroke-linecap="round" stroke-linejoin="round">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="password">
          <n-input v-model:value="formValue.password" type="password" placeholder="请输入密码" show-password-on="click"
            :input-props="{
              name: 'password',
              autocomplete: 'current-password',
              required: true,
            }">
            <template #prefix>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
                <path d="M7 11V7a5 5 0 0 1 10 0v4" />
              </svg>
            </template>
          </n-input>
        </n-form-item>

        <div class="form-options">
          <n-checkbox name="remember-me">记住我</n-checkbox>
        </div>

        <div class="form-actions">
          <n-button type="primary" attr-type="submit" :loading="loading" size="large" class="login-btn">
            登录
          </n-button>
        </div>
      </n-form>
    </section>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.login-page {
  display: flex;
  min-height: 100vh;
}

.brand-panel {
  flex: 0 0 48%;
  background: $sidebar-bg;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 64px 56px;
  position: relative;
  overflow: hidden;

  @media (max-width: 768px) {
    flex: none;
    padding: $space-lg;
    min-height: 200px;
    justify-content: flex-start;
  }
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: $font-display;
  font-size: 24px;
  font-weight: 600;
  color: $text-light;
  margin-bottom: $space-xl;
}

.brand-logo-icon {
  width: 28px;
  height: 28px;
  color: $accent;
}

.brand-text {
  position: relative;
  z-index: 1;
}

.brand-tagline {
  font-family: $font-display;
  font-size: 32px;
  font-weight: 300;
  color: $text-light;
  margin-bottom: $space-md;
}

.brand-desc {
  font-family: $font-body;
  font-size: 15px;
  color: rgba(245, 243, 238, 0.6);
  line-height: 1.7;
}

.dot-grid {
  position: absolute;
  bottom: 40px;
  right: 40px;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  opacity: 0.15;

  span {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: $accent;
  }

  @media (max-width: 768px) {
    display: none;
  }
}

.form-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 64px 56px;
  max-width: 520px;
  margin: 0 auto;

  @media (max-width: 768px) {
    padding: $space-lg;
    max-width: 100%;
  }
}

.form-header {
  margin-bottom: 40px;

  h1 {
    font-family: $font-display;
    font-size: 28px;
    font-weight: 600;
    color: $text-primary;
    margin-bottom: $space-sm;
  }

  p {
    font-family: $font-body;
    font-size: 15px;
    color: $text-muted;
  }
}

/* n-form-item 默认间距加大 */
:deep(.n-form-item) {
  margin-bottom: 24px;
}

.form-options {
  margin-top: 8px;
  margin-bottom: 32px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
}

.login-btn {
  padding-left: 32px;
  padding-right: 32px;
}
</style>