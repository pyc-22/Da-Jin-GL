import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    port: 5175,
    host: true,
    allowedHosts: true,
    // 不要监听原生产物目录：Gradle 打 APK 时会锁住 android/app/build 里的文件，
    // 监听器会抛 EBUSY 直接把 dev server 干崩。
    watch: { ignored: ['**/android/**', '**/dist/**', '**/.gradle/**'] },
    proxy: {
      '/api': { target: 'http://127.0.0.1:18080', changeOrigin: true, headers: { origin: 'http://localhost:5175' } },
      '/uploads': { target: 'http://127.0.0.1:18080', changeOrigin: true, headers: { origin: 'http://localhost:5175' } },
      '/actuator': { target: 'http://127.0.0.1:18080', changeOrigin: true, headers: { origin: 'http://localhost:5175' } },
      '/ws': { target: 'ws://127.0.0.1:18080', changeOrigin: true, ws: true, headers: { origin: 'http://localhost:5175' } }
    }
  },
  build: { outDir: mode === 'android' ? 'dist/build/android' : 'dist/build/h5', emptyOutDir: true }
}))
