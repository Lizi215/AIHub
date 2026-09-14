import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/index.css'

// Element Plus 组件由 unplugin 按需引入，这里不 app.use(ElementPlus)。
// 但命令式 API 的样式要显式引入：ElMessage / ElMessageBox 以函数方式调用，
// 模板里不出现对应组件，按需解析看不到它们，样式不会被带进来。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
