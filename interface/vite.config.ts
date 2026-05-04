import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
  },
  server: {
    allowedHosts: ['.ngrok-free.app', '.ngrok-free.app:3000'],
  },
})
