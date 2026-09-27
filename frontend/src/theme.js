// 主题状态唯一来源：index.html 启动脚本负责首帧前的 class 初始化，
// 这里负责切换、持久化和通知订阅方（echarts 图表等）重绘。
const KEY = 'theme'
const listeners = new Set()

export const isDarkTheme = () => document.documentElement.classList.contains('dark')

export const applyTheme = (dark, persist) => {
  document.documentElement.classList.toggle('dark', dark)
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', dark ? '#0d1117' : '#0969da')
  if (persist) {
    try { localStorage.setItem(KEY, dark ? 'dark' : 'light') } catch (e) { /* ignore */ }
  }
  listeners.forEach((cb) => cb(dark))
}

export const toggleTheme = () => applyTheme(!isDarkTheme(), true)

// 图表等非 CSS 渲染物订阅主题变化，切换时重绘
export const onThemeChange = (cb) => {
  listeners.add(cb)
  return () => listeners.delete(cb)
}

// echarts 取不到 CSS 变量，轴/浮层颜色由这里按主题显式给出
export const chartTheme = () => isDarkTheme()
  ? { text: '#9198a1', split: '#21262d', tipBg: '#1c2128', tipLine: '#3d444d', tipText: '#e6edf3', accent: '#4493f8', success: '#3fb950' }
  : { text: '#57606a', split: '#eaeef2', tipBg: '#ffffff', tipLine: '#d1d9e0', tipText: '#1f2328', accent: '#0969da', success: '#1a7f37' }

