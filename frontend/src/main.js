import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { copyText } from './utils'
import 'element-plus/theme-chalk/dark/css-vars.css'

createApp(App).use(router).mount('#app')

// 代码块复制按钮：mdToHtml 输出的 .md-code 出现在各视图的 v-html 内，
// 无法逐个绑事件，统一走 document 级事件委托（按钮反馈文案短驻后复原）
document.addEventListener('click', async (e) => {
  const btn = e.target.closest('.md-code-copy')
  if (!btn || btn.dataset.busy) return
  const pre = btn.closest('.md-code')?.querySelector('pre')
  if (!pre || !pre.textContent) return
  btn.dataset.busy = '1'
  if (await copyText(pre.textContent)) {
    btn.textContent = '已复制'
    setTimeout(() => { btn.textContent = '复制'; delete btn.dataset.busy }, 1200)
  } else {
    delete btn.dataset.busy
  }
})
