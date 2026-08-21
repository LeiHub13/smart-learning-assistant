const TOKEN_KEY = 'la_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}
export function setToken(t) {
  localStorage.setItem(TOKEN_KEY, t)
}
export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export async function api(path, options = {}) {
  const isForm = options.body instanceof FormData
  const headers = { ...(options.headers || {}) }
  if (!isForm) headers['Content-Type'] = 'application/json'
  const token = getToken()
  if (token) headers.Authorization = 'Bearer ' + token
  const res = await fetch(path, {
    method: options.method || 'GET',
    headers,
    body: isForm ? options.body : options.body ? JSON.stringify(options.body) : undefined
  })
  const json = await res.json().catch(() => null)
  if (!res.ok || (json && json.code !== 0)) {
    const msg = (json && json.message) || '请求失败(' + res.status + ')'
    if (res.status === 401 || (json && json.code === 401)) {
      clearToken()
      location.reload()
    }
    throw new Error(msg)
  }
  return json.data
}

/** 课程数据模块级缓存：登录后加载一次，切换页面不再重复请求 */
let coursesCache = null

export async function getCourses(force) {
  if (!force && coursesCache) return coursesCache
  coursesCache = await api('/api/courses')
  return coursesCache
}

export function resetApiCache() {
  coursesCache = null
}

/** 解析 SSE 流：onDelta(chunk) onDone(sources) */
export async function sseStream(path, body, onDelta, onDone) {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + getToken() },
    body: JSON.stringify(body)
  })
  if (!res.ok) throw new Error('请求失败')
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const event = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const line = event.split('\n').find((l) => l.startsWith('data:'))
      if (!line) continue
      try {
        const d = JSON.parse(line.slice(5).trim())
        if (d.delta !== undefined) onDelta(d.delta)
        if (d.sources !== undefined) onDone(d.sources || '')
      } catch (e) { /* ignore */ }
    }
  }
}