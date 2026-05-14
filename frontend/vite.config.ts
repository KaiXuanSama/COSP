import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  base: '/admin/',
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src'),
    },
  },
  // 构建输出到 Spring Boot 的静态资源目录
  build: {
    outDir: resolve(__dirname, '../src/main/resources/static/admin'),
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // 开发时代理 API 请求到 Spring Boot 后端
    proxy: {
      '/config/api': {
        target: 'http://localhost:11434',
        changeOrigin: true,
      },
    },
  },
})