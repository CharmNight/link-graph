import { defineConfig } from "vite";
import UnoCSS from 'unocss/vite'
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  plugins: [react(), UnoCSS()],
  test: {
    include: ["src/test/**/*.test.{ts,tsx}"],
    environment: "jsdom",
    globals: true,
    css: true,
    setupFiles: ["./src/test/setup.ts"],
  },
});
