/// <reference types="vitest/config" />
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// In development the browser talks only to this dev server; API calls are proxied to the gateway.
// Same origin means the HttpOnly refresh cookie (Path=/auth, SameSite=Strict) just works, with no CORS.
const gateway = process.env.GATEWAY_URL ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/auth": gateway,
      "/me": gateway,
      "/v1": gateway,
      "/.well-known": gateway,
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    css: false,
  },
});
