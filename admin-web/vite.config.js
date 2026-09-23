import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(() => {
  const backend = process.env.VITE_DEV_BACKEND_URL || 'http://localhost:8080'
  return {
    plugins: [vue()],
    server: {
      port: 5174,
      strictPort: true,
      host: true,
      allowedHosts: true,
      proxy: {
        '/api': { target: backend, changeOrigin: true, headers: { origin: 'http://localhost:5174' } },
        '/uploads': { target: backend, changeOrigin: true, headers: { origin: 'http://localhost:5174' } },
        '/ws': { target: backend, changeOrigin: true, ws: true, headers: { origin: 'http://localhost:5174' } }
      }
    },
    build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 1100, rollupOptions: { output: { manualChunks: { 'element-plus': ['element-plus'], 'echarts': ['echarts'], 'xlsx': ['xlsx'] } } } },
    test: { environment: 'jsdom' }
  }
})
