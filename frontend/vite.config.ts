import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  base: '/',
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  // 构建输出到 Spring Boot 的静态资源根目录
  build: {
    outDir: resolve(__dirname, '../src/main/resources/static'),
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // 只代理真正的后端 API。
    // 注意：绝不要代理 /login —— 它是 Vue Router 的前端路由，必须由 Vite 的
    // history fallback 返回 dev 版 index.html。一旦代理到后端，拿到的是
    // src/main/resources/static/ 里的旧构建产物（引用带 hash 的 /assets/*.js），
    // dev server 上不存在这些文件 → 404 → 整页白屏。
    // 登录接口是 POST /auth/login，已由下面的 /auth 规则覆盖。
    proxy: {
      '/config/api': {
        target: 'http://localhost:11434',
        changeOrigin: true,
      },
      '/auth': {
        target: 'http://localhost:11434',
        changeOrigin: true,
      },
    },
  },
})