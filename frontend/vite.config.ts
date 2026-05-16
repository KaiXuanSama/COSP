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
    proxy: {
      '/config/api': {
        target: 'http://localhost:80',
        changeOrigin: true,
      },
      '/login': {
        target: 'http://localhost:80',
        changeOrigin: true,
      },
    },
  },
})