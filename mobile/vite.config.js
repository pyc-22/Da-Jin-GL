import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => ({
  plugins: [vue()],
  server: {
    port: 5175,
    host: true,
    allowedHosts: true,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true, headers: { origin: 'http://localhost:5175' } },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true, headers: { origin: 'http://localhost:5175' } }
    }
  },
  build: { outDir: mode === 'android' ? 'dist/build/android' : 'dist/build/h5', emptyOutDir: true }
}))
