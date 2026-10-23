import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      // The e2e walkthrough points this at its own backend (see playwright.config.ts). changeOrigin stays
      // false so the backend sees the browser's own host and treats /api calls as same-origin (it refuses
      // cross-origin requests, as in production behind nginx).
      '/api': { target: process.env.TURFUP_API ?? 'http://localhost:8080', changeOrigin: false },
    },
  },
})
