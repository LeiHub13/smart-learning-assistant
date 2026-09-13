import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  build: {
    // 大依赖拆稳定 vendor chunk：业务代码改动不再打爆 echarts/vue 的长缓存
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts/core', 'echarts/charts', 'echarts/components', 'echarts/renderers'],
          vendor: ['vue', 'vue-router'],
        },
      },
    },
  },
  server: {
    port: 5173,
    host: "0.0.0.0", // 允许外部访问
    allowedHosts: ["42104654.nat123.top"],
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
      "/files": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
