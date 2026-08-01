<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NInput, NButton, NCheckbox, NForm, NFormItem, useMessage } from 'naive-ui'
import { auth } from '@/api'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const formRef = ref<InstanceType<typeof NForm> | null>(null)
const formValue = ref({ username: '', password: '' })
const loading = ref(false)

// 提示信息必须在挂载后触发：NMessageProvider 尚未挂载完成时，在 setup 阶段同步调用
// message API 会在渲染期间修改 provider 状态，Vue 会抛错导致整棵组件树渲染失败（白屏）。
// 这正是「?unauthorized=true」整页跳转后 100% 白屏的根因。
onMounted(() => {
  if (route.query.unauthorized) message.warning('无权限，请先登录。')
  if (route.query.login === 'logout') message.info('已成功退出登录。')
  if (route.query.login === 'error') message.error('用户名或密码错误。')
})

/**
 * 登录成功后的目标地址。
 *
 * 取 ?redirect= 里记录的原地址，让「浏览受保护页面时掉线」的用户登录后回到原处。
 * 只接受站内绝对路径：外部 URL 或协议相对地址（//evil.com）会被丢弃，
 * 否则构成开放重定向漏洞。同时排除 /login 自身，避免登录后又回到登录页。
 */
function resolveRedirect(): string {
  const raw = route.query.redirect
  const target = Array.isArray(raw) ? raw[0] : raw
  if (typeof target !== 'string') return '/overview'
  if (!target.startsWith('/') || target.startsWith('//')) return '/overview'
  if (target.startsWith('/login')) return '/overview'
  return target
}

async function handleSubmit() {
  loading.value = true
  try {
    const res = await fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: formValue.value.username,
        password: formValue.value.password,
      }),
    })
    if (res.ok) {
      const data = await res.json()
      auth.setToken(data.token)
      router.replace(resolveRedirect())
    } else {
      message.error('用户名或密码错误。')
    }
  } catch {
    message.error('网络错误，请稍后重试。')
  } finally {
    loading.value = false
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

  @media (max-width: 768px) {
    flex-direction: column;
  }
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
    min-height: 180px;
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

  @media (max-width: 768px) {
    font-size: 20px;
    margin-bottom: $space-md;
  }
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

  @media (max-width: 768px) {
    font-size: 24px;
  }
}

.brand-desc {
  font-family: $font-body;
  font-size: 15px;
  color: rgba(245, 243, 238, 0.6);
  line-height: 1.7;

  @media (max-width: 768px) {
    font-size: 13px;
  }
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

    @media (max-width: 768px) {
      font-size: 22px;
    }
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

  @media (max-width: 768px) {
    justify-content: stretch;
  }
}

.login-btn {
  padding-left: 32px;
  padding-right: 32px;

  @media (max-width: 768px) {
    width: 100%;
  }
}
</style>