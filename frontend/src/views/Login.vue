<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const error = ref('')
const unauthorized = ref(false)
const logout = ref(false)

unauthorized.value = !!route.query.unauthorized
logout.value = route.query.login === 'logout'
error.value = route.query.login === 'error' ? '用户名或密码错误。' : ''

async function handleSubmit() {
  // Form submission handled by Spring Security's form login
  // This is a placeholder - actual login uses standard form POST
  const form = document.querySelector('form') as HTMLFormElement
  if (form) form.submit()
}
</script>

<template>
  <div class="login-page">
    <!-- 品牌面板 -->
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

    <!-- 表单面板 -->
    <section class="form-panel">
      <div class="form-header">
        <h1>欢迎回来</h1>
        <p>请输入管理员账号密码以访问配置页面。</p>
      </div>

      <div v-if="unauthorized" class="message info">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        <span>无权限，请先登录。</span>
      </div>

      <div v-if="logout" class="message info">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        <span>已成功退出登录。</span>
      </div>

      <div v-if="error" class="message error">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <line x1="15" y1="9" x2="9" y2="15" />
          <line x1="9" y1="9" x2="15" y2="15" />
        </svg>
        <span>{{ error }}</span>
      </div>

      <form class="login-form" action="/login" method="post" @submit="handleSubmit">
        <div class="field-group">
          <label class="field-label" for="username">用户名</label>
          <div class="field-input-wrap">
            <input class="field-input field-input--with-icon" id="username" name="username" type="text" placeholder="请输入用户名" autocomplete="username" required autofocus v-model="username">
            <svg class="field-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
              <circle cx="12" cy="7" r="4" />
            </svg>
          </div>
        </div>

        <div class="field-group">
          <label class="field-label" for="password">密码</label>
          <div class="field-input-wrap">
            <input class="field-input field-input--with-icon" id="password" name="password" type="password" placeholder="请输入密码" autocomplete="current-password" required v-model="password">
            <svg class="field-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          </div>
        </div>

        <div class="form-options">
          <label class="checkbox-wrap">
            <input class="checkbox-input" type="checkbox" name="remember-me">
            <span class="checkbox-custom">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="20 6 9 17 4 12"></polyline>
              </svg>
            </span>
            <span>记住我</span>
          </label>
        </div>

        <div class="form-actions">
          <button class="submit-btn" type="submit">
            <span>登录</span>
          </button>
        </div>
      </form>
    </section>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.login-page {
  display: flex;
  min-height: 100vh;
}

/* ── 品牌面板 ── */
.brand-panel {
  flex: 0 0 48%;
  background: $sidebar-bg;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: $space-2xl;
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

/* ── 表单面板 ── */
.form-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: $space-2xl;
  max-width: 480px;
  margin: 0 auto;

  @media (max-width: 768px) {
    padding: $space-lg;
    max-width: 100%;
  }
}

.form-header {
  margin-bottom: $space-xl;

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

.form-options {
  margin-top: $space-md;
}

.login-form {
  animation: fadeUp 0.5s ease forwards;
}
</style>