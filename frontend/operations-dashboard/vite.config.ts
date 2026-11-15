/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Local dev: the browser talks only to Vite; Vite proxies to the services (no CORS needed).
// Cloud: serve the build behind the same-origin ingress/gateway (Phase 11) instead of these proxies.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/auth": { target: "http://localhost:8087", changeOrigin: true },
      "/ops-api": { target: "http://localhost:8083", changeOrigin: true, rewrite: (p) => p.replace(/^\/ops-api/, "") },
      "/orders-api": { target: "http://localhost:8081", changeOrigin: true, rewrite: (p) => p.replace(/^\/orders-api/, "") },
      "/assistant-api": { target: "http://localhost:8085", changeOrigin: true, rewrite: (p) => p.replace(/^\/assistant-api/, "") },
      "/remediation-api": { target: "http://localhost:8086", changeOrigin: true, rewrite: (p) => p.replace(/^\/remediation-api/, "") },
      "/inventory-api": { target: "http://localhost:8082", changeOrigin: true, rewrite: (p) => p.replace(/^\/inventory-api/, "") },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/setupTests.ts"],
  },
});
