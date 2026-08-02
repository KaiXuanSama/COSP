<script setup lang="ts">
/**
 * 前端 404 页面。
 *
 * 后端对所有「面向浏览器且非 API」的路径统一回退返回 index.html（见 SpaRoutingConfig），
 * 所以拼错的地址不会得到服务端 404，而是进入 Vue 路由。若路由表没有 catch-all，
 * 这类地址会渲染成空白页 —— 与真正的故障白屏无法区分。本页面就是那个兜底。
 */
import { useRouter } from 'vue-router'
import { NButton } from 'naive-ui'
import { auth } from '@/api'

const router = useRouter()

function goHome() {
  router.replace(auth.isAuthenticated() ? '/overview' : '/login')
}
</script>

<template>
  <div class="not-found">
    <div class="card">
      <p class="code">404</p>
      <h1>页面不存在</h1>
      <p class="hint">地址可能拼写有误，或该页面已被移除。</p>
      <n-button type="primary" size="large" @click="goHome">返回首页</n-button>
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.not-found {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: $bg;
  padding: 1.5rem;
}

.card {
  max-width: 26rem;
  padding: 3rem 2.5rem;
  background: $surface;
  border: 1px solid $border;
  border-radius: 12px;
  text-align: center;
}

.code {
  margin: 0 0 0.5rem;
  font-size: 3.5rem;
  font-weight: 700;
  line-height: 1;
  color: $accent;
}

h1 {
  margin: 0 0 0.75rem;
  font-size: 1.25rem;
  color: $text-primary;
}

.hint {
  margin: 0 0 2rem;
  color: $text-muted;
  line-height: 1.7;
}
</style>
