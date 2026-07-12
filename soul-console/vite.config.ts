import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

const target = process.env.VITE_API_TARGET ?? 'http://localhost:7788';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 7787,
    strictPort: true,
    proxy: {
      '/api': { target, changeOrigin: true },
      '/actuator': { target, changeOrigin: true },
      '/ws': { target, ws: true },
    },
  },
  preview: { port: 7787, strictPort: true },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
