import { defineConfig } from "vite";

// The pay button for plain web pages: one self-running script. A second build because it is a different file
// with a different job from the SDK (no iframe, no card field).
export default defineConfig({
  build: {
    emptyOutDir: false,
    lib: { entry: "src/button.ts", name: "CutpayButton", formats: ["iife"], fileName: () => "cutpay-button.js" },
    sourcemap: true,
    minify: true,
  },
});
