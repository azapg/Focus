import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL(".", import.meta.url));
export default defineConfig({
  root: "ui",
  base: "./",
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": `${root}ui` } },
  build: { outDir: `${root}ui-dist`, emptyOutDir: true },
});
