import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地 APK/H5 测试专用配置：仅连接电脑局域网中的本地后端，不修改正式配置。
export default defineConfig({
  plugins: [vue()],
  define: {
    'import.meta.env.VITE_API_BASE': JSON.stringify(''),
    'import.meta.env.VITE_WS_URL': JSON.stringify(''),
    'import.meta.env.VITE_NATIVE_APP': JSON.stringify('true')
  },
  server: {
    port: 5175,
    host: true,
    allowedHosts: true,
    proxy: {
      '/api': { target: 'http://127.0.0.1:18080', changeOrigin: true },
      '/uploads': { target: 'http://127.0.0.1:18080', changeOrigin: true }
    }
  },
  build: { outDir: 'dist/build/android', emptyOutDir: true }
})
