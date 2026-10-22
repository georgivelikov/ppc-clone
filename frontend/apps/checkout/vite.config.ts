/// <reference types="vitest/config" />
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { pathToFileURL } from "node:url";
import { build, defineConfig, type Plugin } from "vite";

/**
 * Framing rules. The card input is meant to be embedded by merchants, so it may be framed by anyone (the page
 * still only talks to the origin the SDK names). Every other page, the hosted checkout included, refuses to be
 * framed so it cannot be used for click-jacking.
 */
/**
 * What a page may load and talk to. Everything comes from this origin: no third-party script can run on a page that
 * handles card data, and nothing can be sent anywhere else (connect-src), so an injected script could neither load a
 * skimmer nor send what it reads. The dev server needs inline scripts (React's refresh preamble) and a WebSocket;
 * the production build and its preview do not, and get the strict version. docs/security/threat-model.md.
 */
function contentSecurityPolicy(dev: boolean, framing: string): string {
  return [
    "default-src 'self'",
    dev ? "script-src 'self' 'unsafe-inline'" : "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    dev ? "connect-src 'self' ws: wss:" : "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    framing,
  ].join("; ");
}

function framingHeaders(): Plugin {
  type Middlewares = { use: (fn: (req: { url?: string }, res: { setHeader: (k: string, v: string) => void }, next: () => void) => void) => void };
  const apply = (middlewares: Middlewares, dev: boolean) =>
    middlewares.use((req, res, next) => {
      if (req.url?.startsWith("/elements/")) {
        res.setHeader("Content-Security-Policy", contentSecurityPolicy(dev, "frame-ancestors *"));
      } else {
        res.setHeader("Content-Security-Policy", contentSecurityPolicy(dev, "frame-ancestors 'none'"));
        res.setHeader("X-Frame-Options", "DENY");
      }
      if (req.url?.startsWith("/v1/")) {
        res.setHeader("Access-Control-Allow-Origin", "*"); // a script with an integrity attribute is fetched with CORS
      }
      res.setHeader("X-Content-Type-Options", "nosniff");
      // The payment's secret is in the page address: it must not leak in a Referer header to anything the page links to.
      res.setHeader("Referrer-Policy", "no-referrer");
      res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(self)");
      next();
    });
  return {
    name: "cutpay-framing-headers",
    configureServer(server) {
      apply(server.middlewares, true);
    },
    configurePreviewServer(server) {
      apply(server.middlewares, false);
    },
  };
}

/**
 * The SDK is served from this app as /v1/cutpay.js. Building it when the server starts keeps the generated file
 * (git-ignored) in step with the source, with no separate command to remember.
 */
function cutpaySdk(): Plugin {
  const sdkRoot = new URL("../../packages/checkout-js/", import.meta.url);
  return {
    name: "cutpay-sdk",
    async buildStart() {
      if (process.env.VITEST) return;
      await build({ root: sdkRoot.pathname.replace(/^\/([A-Za-z]:)/, "$1"), logLevel: "warn" });
      const publish = new URL("scripts/publish.mjs", sdkRoot).href;
      await import(/* @vite-ignore */ pathToFileURL(new URL(publish).pathname.replace(/^\/([A-Za-z]:)/, "$1")).href);
    },
  };
}

// The browser talks only to this dev server; checkout and card-vault calls are proxied to the gateway.
const gateway = process.env.GATEWAY_URL ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react(), tailwindcss(), framingHeaders(), cutpaySdk()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      "/checkout": gateway,
      "/links": gateway,
      "/invoices": gateway,
      "/vault": gateway,
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
    css: false,
  },
});
