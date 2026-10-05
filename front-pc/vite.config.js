import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  // Electron loads dist/index.html via file://, so bundled assets must be relative.
  base: './',
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    host: true,
    allowedHosts: true,
    watch: { ignored: ['**/release/**'] },
    proxy: {
      '/api': { target: 'http://127.0.0.1:18080', changeOrigin: true, headers: { origin: 'http://localhost:5173' } },
      '/uploads': { target: 'http://127.0.0.1:18080', changeOrigin: true, headers: { origin: 'http://localhost:5173' } },
      '/ws': { target: 'ws://127.0.0.1:18080', changeOrigin: true, ws: true, headers: { origin: 'http://localhost:5173' } }
    }
  },
  build: { outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 1200 }
})
