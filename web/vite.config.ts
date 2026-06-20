import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Proxies both HTTP requests and WebSocket upgrades (/api/v1/ws/*) to the
      // backend. ws: true is required, otherwise the WS upgrade is dropped and
      // live-sync stays dead in dev (issue #376).
      '/api': { target: 'http://localhost:8080', changeOrigin: true, ws: true },
    },
  },
})
