import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { ElMessage } from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'

const app = createApp(App)
const pinia = createPinia()

// Register Element Plus icons
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus)

app.config.errorHandler = (error, instance, info) => {
  console.error('Vue runtime error:', error, info, instance)
  ElMessage.error('页面发生异常，请刷新后重试')
}

router.onError((error) => {
  console.error('Router error:', error)
  ElMessage.error('页面加载失败，请稍后重试')
})

app.mount('#app')