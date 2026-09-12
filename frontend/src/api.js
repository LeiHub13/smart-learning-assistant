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

/** 解析 SSE 流：onDelta(chunk) onDone(sources|done) */
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
        if (d.saved !== undefined) onDone(d.saved)
        if (d.done) onDone('')
      } catch (e) { /* ignore */ }
    }
  }
}

/** 学习计划 */
export async function listPlans() { return api('/api/plans') }
export async function getPlan(id) { return api(`/api/plans/${id}`) }
export async function createPlan(body) { return api('/api/plans', { method: 'POST', body }) }
export async function checkInTask(taskId) { return api(`/api/plans/tasks/${taskId}/checkin`, { method: 'POST' }) }
export async function deletePlan(id) { return api(`/api/plans/${id}`, { method: 'DELETE' }) }

/** 学习报告 */
export async function listReports() { return api('/api/reports') }
export async function getReport(id) { return api(`/api/reports/${id}`) }
export async function generateWeeklyReport(courseId) { return api(`/api/reports/weekly?courseId=${courseId}`, { method: 'POST' }) }

/** 带认证头的文件下载：fetch blob 后触发浏览器保存 */
export async function downloadFile(path, filename) {
  const res = await fetch(path, { headers: { Authorization: 'Bearer ' + getToken() } })
  if (!res.ok) throw new Error('下载失败(' + res.status + ')')
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** 通知 */
export async function listNotifications() { return api('/api/notifications') }
export async function unreadCount() { return api('/api/notifications/unread-count') }
export async function markRead(id) { return api(`/api/notifications/${id}/read`, { method: 'POST' }) }
export async function markAllRead() { return api('/api/notifications/read-all', { method: 'POST' }) }

/** 流式讲义 */
export async function streamLecture(body, onDelta, onDone) {
  return sseStream('/api/generate/lecture/stream', body, onDelta, onDone)
}