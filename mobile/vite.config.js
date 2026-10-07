import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'

const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'))

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  // 版本号只保留 package.json 一处来源，避免「关于与版本」写死后和安装包对不上
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
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
