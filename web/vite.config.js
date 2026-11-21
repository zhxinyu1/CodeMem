import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

const proxy = {
  '/api': {
    target: 'http://127.0.0.1:8080',
    changeOrigin: true
  },
  '/actuator': {
    target: 'http://127.0.0.1:8080',
    changeOrigin: true
  }
}

export default defineConfig({
  // 构建产物由后端 /web/ 静态托管，开发模式仍走 5173 + 代理
  base: '/web/',
  plugins: [
    vue(),
    // Element Plus 按需引入：组件与样式用到才打包
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] })
  ],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 5173, strictPort: true, proxy }
})