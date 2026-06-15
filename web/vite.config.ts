import { defineConfig } from "vite";
import UnoCSS from 'unocss/vite'
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  plugins: [react(), UnoCSS()],
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) {
            return undefined;
          }
          if (id.includes("/react/") || id.includes("/react-dom/") || id.includes("/scheduler/")) {
            return "vendor-react";
          }
          if (id.includes("/@xyflow/")) {
            return "vendor-graph";
          }
          return undefined;
        },
      },
    },
  },
  test: {
    include: ["src/test/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    globals: true,
    css: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});
