/// <reference types="vitest/config" />
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

/**
 * Framing rules. The card input is meant to be embedded by merchants, so it may be framed by anyone (the page
 * still only talks to the origin the SDK names). Every other page, the hosted checkout included, refuses to be
 * framed so it cannot be used for click-jacking.
 */
function framingHeaders(): Plugin {
  const apply = (middlewares: { use: (fn: (req: { url?: string }, res: { setHeader: (k: string, v: string) => void }, next: () => void) => void) => void }) =>
    middlewares.use((req, res, next) => {
      if (req.url?.startsWith("/elements/")) {
        res.setHeader("Content-Security-Policy", "frame-ancestors *");
      } else {
        res.setHeader("Content-Security-Policy", "frame-ancestors 'none'");
        res.setHeader("X-Frame-Options", "DENY");
      }
      next();
    });
  return {
    name: "cutpay-framing-headers",
    configureServer(server) {
      apply(server.middlewares);
    },
    configurePreviewServer(server) {
      apply(server.middlewares);
    },
  };
}

// The browser talks only to this dev server; checkout and card-vault calls are proxied to the gateway.
const gateway = process.env.GATEWAY_URL ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react(), tailwindcss(), framingHeaders()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      "/checkout": gateway,
      "/vault": gateway,
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    css: false,
  },
});
