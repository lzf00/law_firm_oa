import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElDialog } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-overlay.css'
import 'element-plus/theme-chalk/el-dialog.css'
import 'element-plus/theme-chalk/el-message.css'
import './styles/base.css'
import App from './App.vue'
import router from './router'
import { initializeTenant } from './tenant'

void initializeTenant()
createApp(App)
  .use(createPinia())
  .use(router)
  .component('ElDialog', ElDialog)
  .mount('#app')
