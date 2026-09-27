import { getBaseUrl } from '../config'

const TOKEN_KEY = 'la_token'
const USER_KEY = 'la_user'

export function getToken() {
  return uni.getStorageSync(TOKEN_KEY) || ''
}
export function setToken(t) {
  uni.setStorageSync(TOKEN_KEY, t)
}
export function clearToken() {
  uni.removeStorageSync(TOKEN_KEY)
  coursesCache = null
}

/** 课程数据模块级缓存：登录后加载一次，切页不重复请求（与 Web 端 api.js 同约定） */
let coursesCache = null

export async function getCourses(force) {
  if (!force && coursesCache) return coursesCache
  coursesCache = await api('/api/courses')
  return coursesCache
}

export function getUser() {
  return uni.getStorageSync(USER_KEY) || {}
}
export function setUser(u) {
  uni.setStorageSync(USER_KEY, u || {})
}
export function clearUser() {
  uni.removeStorageSync(USER_KEY)
}

function toLogin() {
  clearToken()
  clearUser()
  uni.reLaunch({ url: '/pages/login/login' })
}

/** 通用请求：自动拼 BASE_URL、带 JWT，返回业务 data（后端包裹为 {code, message, data}） */
export async function api(path, options = {}) {
  const header = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) header.Authorization = 'Bearer ' + token

  let res
  try {
    res = await uni.request({
      url: getBaseUrl() + path,
      method: options.method || 'GET',
      data: options.body,
      header,
      timeout: 30000
    })
  } catch (e) {
    const err = Array.isArray(e) ? e[0] : e
    throw new Error('网络错误：无法连接 ' + getBaseUrl() + (err && err.errMsg ? '\n' + err.errMsg : ''))
  }
  // 兼容部分平台 Promise 返回 [err, res] 数组风格
  if (Array.isArray(res)) res = res[1] || res[0]

  const json = res && res.data
  if (!res || res.statusCode >= 400 || (json && typeof json === 'object' && json.code !== 0 && json.code !== undefined)) {
    const msg = (json && json.message) || '请求失败(' + (res ? res.statusCode : '??') + ')'
    if (res && res.statusCode === 401) toLogin()
    throw new Error(msg)
  }
  return json && typeof json === 'object' && json.data !== undefined ? json.data : json
}
