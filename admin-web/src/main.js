import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn })
app.directive('permission', (el, binding) => { const auth = useAuthStore(); if (!auth.can(binding.value)) el.remove() })
app.mount('#app')
