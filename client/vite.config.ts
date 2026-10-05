import { defineConfig } from "vite";

// 開發時把 API 與 WebSocket 轉到 Spring Boot（localhost:8080），前端就能用相對路徑。
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
});
