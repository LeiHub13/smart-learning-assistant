/**
 * 后端地址配置（移动端唯一需要改的地方）
 *
 * 真机调试：手机和电脑连同一个 Wi-Fi，把下面改成电脑的局域网 IP
 * （Windows 下 cmd 运行 ipconfig 看「IPv4 地址」），例如：
 *   http://192.168.1.5:8080
 *
 * 也可以不改这里，直接在 App「我的」页里在线修改（存本机，改完立即生效）。
 */
export const DEFAULT_BASE_URL = 'http://192.168.1.5:8080'

export const BASE_URL_KEY = 'la_base_url'

export function getBaseUrl() {
  return uni.getStorageSync(BASE_URL_KEY) || DEFAULT_BASE_URL
}

export function setBaseUrl(url) {
  uni.setStorageSync(BASE_URL_KEY, String(url || '').replace(/\/+$/, ''))
}
