<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import http from '@/api'

const router = useRouter()
const route = useRoute()

const sidebarOpen = ref(false)
const username = ref('')

const navItems = [
  { path: '/', label: '概览', icon: 'overview' },
  { path: '/settings', label: '配置', icon: 'settings' },
]

const isActive = (path: string) => route.path === path

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

function navigate(path: string) {
  router.push(path)
  sidebarOpen.value = false
}

async function fetchUsername() {
  try {
    const res = await http.get('/stats')
    // Username is not in stats, we'll get it from a different endpoint
  } catch {
    // ignore
  }
}

function handleResize() {
  if (window.innerWidth > 1024) {
    sidebarOpen.value = false
  }
}

onMounted(() => {
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
})
</script>

<template>
  <!-- 遮罩层 -->
  <div
    class="sidebar-overlay"
    :class="{ open: sidebarOpen }"
    @click="sidebarOpen = false"
  ></div>

  <!-- 侧边栏 -->
  <aside class="sidebar" :class="{ open: sidebarOpen }">
    <div class="sidebar-brand">
      <svg class="sidebar-logo" viewBox="0 0 28 28" fill="none">
        <circle cx="14" cy="14" r="12" stroke="currentColor" stroke-width="1.5" />
        <path d="M8 14h12M14 8v12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      </svg>
      COSP
    </div>

    <nav class="sidebar-nav">
      <div class="nav-section-label">管理</div>
      <a
        v-for="item in navItems"
        :key="item.path"
        class="nav-item"
        :class="{ active: isActive(item.path) }"
        @click="navigate(item.path)"
      >
        <svg v-if="item.icon === 'overview'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="3" width="7" height="7" />
          <rect x="14" y="3" width="7" height="7" />
          <rect x="3" y="14" width="7" height="7" />
          <rect x="14" y="14" width="7" height="7" />
        </svg>
        <svg v-else-if="item.icon === 'settings'" class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
        {{ item.label }}
      </a>
    </nav>

    <div class="sidebar-footer">
      <div class="sidebar-user">
        <div class="sidebar-avatar">R</div>
        <div class="sidebar-user-info">
          <div class="sidebar-user-name">root</div>
          <div class="sidebar-user-role">管理员</div>
        </div>
        <router-link to="/account" class="sidebar-user-edit" title="修改账号">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
            <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
          </svg>
        </router-link>
      </div>
    </div>
  </aside>

  <!-- 主内容区 -->
  <div class="main-area">
    <!-- 页头 -->
    <header class="header">
      <button class="hamburger-btn" @click="toggleSidebar" :class="{ open: sidebarOpen }">
        <span></span>
        <span></span>
        <span></span>
      </button>
      <div class="header-title">
        <router-link to="/">COSP 管理后台</router-link>
      </div>
    </header>

    <!-- 内容 -->
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style lang="scss">
@use '@/styles/variables' as *;

/* ── 侧边栏遮罩 ── */
.sidebar-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: 90;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.3s ease;

  &.open {
    opacity: 1;
    pointer-events: auto;
  }

  @media (min-width: 1025px) {
    display: none;
  }
}

/* ── 侧边栏 ── */
.sidebar {
  position: fixed;
  top: 0;
  left: 0;
  width: $sidebar-width;
  height: 100vh;
  background: $sidebar-bg;
  display: flex;
  flex-direction: column;
  z-index: 100;
  transition: transform 0.3s ease;

  @media (max-width: 1024px) {
    transform: translateX(-100%);

    &.open {
      transform: translateX(0);
    }
  }
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 24px;
  font-family: $font-display;
  font-size: 22px;
  font-weight: 600;
  color: $text-light;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.sidebar-logo {
  width: 28px;
  height: 28px;
  color: $accent;
}

.sidebar-nav {
  flex: 1;
  padding: 16px 0;
}

.nav-section-label {
  padding: 8px 28px 4px;
  font-family: $font-mono;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: rgba(245, 243, 238, 0.3);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 28px;
  color: rgba(245, 243, 238, 0.55);
  font-family: $font-body;
  font-size: 15px;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;

  &:hover {
    color: rgba(245, 243, 238, 0.85);
    background: rgba(255, 255, 255, 0.03);
  }

  &.active {
    color: $accent;
    background: $accent-mid;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 0;
      bottom: 0;
      width: 3px;
      background: $accent;
      border-radius: 0 2px 2px 0;
    }
  }
}

.nav-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.sidebar-footer {
  padding: 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sidebar-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: $accent;
  color: $text-light;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: $font-mono;
  font-size: 13px;
  font-weight: 500;
  flex-shrink: 0;
}

.sidebar-user-info {
  flex: 1;
  min-width: 0;
}

.sidebar-user-name {
  font-family: $font-body;
  font-size: 14px;
  color: $text-light;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-user-role {
  font-family: $font-mono;
  font-size: 10px;
  letter-spacing: 0.1em;
  color: rgba(245, 243, 238, 0.4);
}

.sidebar-user-edit {
  color: rgba(245, 243, 238, 0.3);
  transition: color 0.2s ease;

  svg {
    width: 16px;
    height: 16px;
  }

  &:hover {
    color: $accent;
  }
}

/* ── 主内容区 ── */
.main-area {
  margin-left: $sidebar-width;
  min-height: 100vh;

  @media (max-width: 1024px) {
    margin-left: 0;
  }
}

/* ── 页头 ── */
.header {
  position: sticky;
  top: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  height: $header-height;
  padding: 0 $space-lg;
  background: $surface;
  border-bottom: 1px solid $border;
  backdrop-filter: blur(8px);
}

.hamburger-btn {
  display: none;
  width: 44px;
  height: 44px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 5px;
  margin-right: $space-sm;
  -webkit-tap-highlight-color: transparent;
  touch-action: manipulation;

  @media (max-width: 1024px) {
    display: flex;
  }

  span {
    display: block;
    width: 18px;
    height: 2px;
    background: $text-primary;
    border-radius: 1px;
    transition: all 0.3s ease;
  }

  &.open {
    span:nth-child(1) {
      transform: rotate(45deg) translate(5px, 5px);
    }
    span:nth-child(2) {
      opacity: 0;
    }
    span:nth-child(3) {
      transform: rotate(-45deg) translate(5px, -5px);
    }
  }
}

.header-title {
  font-family: $font-display;
  font-size: 18px;
  font-weight: 600;
  color: $text-primary;

  a {
    color: inherit;
  }
}

/* ── 内容区 ── */
.content {
  padding: $space-lg;
  max-width: 960px;
  margin: 0 auto;
  animation: fadeUp 0.5s ease forwards;
}
</style>