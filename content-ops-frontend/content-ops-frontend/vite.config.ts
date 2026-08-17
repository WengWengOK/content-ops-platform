import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    // 与项目文档保持一致（http://localhost:5173）
    port: 5173,
    host: true,
    proxy: {
      // 单体后端（content-ops-server，端口 8080）：
      // 前端 baseURL 为 /orchestrator/api/v1，这里去掉 /orchestrator 前缀后转发，
      // 否则后端收到 /orchestrator/api/v1/... 会 404。
      '/orchestrator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/orchestrator/, ''),
      },
      // 兼容 VITE_ORCHESTRATOR_URL 留空时直接使用 /api 前缀的场景
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
