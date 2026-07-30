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
    port: 3000,
    host: true,
    proxy: {
      '/orchestrator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/topic-agent': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/content-agent': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/image-agent': {
        target: 'http://localhost:8083',
        changeOrigin: true,
      },
      '/publish-agent': {
        target: 'http://localhost:8084',
        changeOrigin: true,
      },
      '/analysis-agent': {
        target: 'http://localhost:8085',
        changeOrigin: true,
      },
      '/optimize-agent': {
        target: 'http://localhost:8086',
        changeOrigin: true,
      },
    },
  },
})
