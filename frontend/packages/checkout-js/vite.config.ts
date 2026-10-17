/// <reference types="vitest/config" />
import { defineConfig } from "vite";

// Library mode: one file for <script> tags (UMD, `window.Cutpay` is the function itself) and one for bundlers (ESM).
export default defineConfig({
  build: {
    lib: {
      entry: "src/standalone.ts",
      name: "Cutpay",
      formats: ["es", "umd"],
      fileName: (format) => (format === "es" ? "cutpay.esm.js" : "cutpay.umd.cjs"),
    },
    rollupOptions: { output: { exports: "default" } },
    sourcemap: true,
    minify: true,
  },
  test: { environment: "jsdom" },
});
