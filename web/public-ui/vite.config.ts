import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const backendPort = process.env.E2E_BACKEND_PORT || '28080'
const httpTarget = process.env.VITE_API_TARGET || `http://127.0.0.1:${backendPort}`
const privatePrefix = (process.env.VITE_PRIVATE_API_BASE || '/__private__').replace(/\/+$/, '')

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5180,
    proxy: {
      [privatePrefix.startsWith('http') ? '/__private__' : privatePrefix]: {
        target: httpTarget,
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
