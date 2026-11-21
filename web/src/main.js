import { createApp } from 'vue'
// Element Plus 组件与样式由 unplugin-vue-components 按需注入；
// 程序式调用的 ElMessage / ElMessageBox 不经过模板编译，样式必须单独引入，
// 否则确认弹窗会失去居中容器与边框，内容直接铺在页面上。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import './style.css'
import App from './App.vue'

createApp(App).mount('#app')