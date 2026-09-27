// 与 Web 端 frontend/src/utils.js 对应的通用格式化函数（同源同逻辑）

export function fmtTime(t) {
  if (!t) return ''
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  return p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes())
}

export function parseOptions(s) {
  if (!s) return []
  try {
    return JSON.parse(s)
  } catch (e) {
    return []
  }
}

/**
 * AI 点评兼容解析：新版为结构化 JSON（评分点逐项批改，summary + points），
 * 旧版/兜底为纯文本。统一返回 { summary, points }，非 JSON 时 points 为空数组。
 */
export function parseReview(raw) {
  if (!raw) return { summary: '', points: [] }
  const s = String(raw).trim()
  if (s.startsWith('{') && s.includes('"points"')) {
    try {
      const o = JSON.parse(s)
      if (o && Array.isArray(o.points) && o.points.length) {
        return { summary: o.summary || '', points: o.points }
      }
    } catch (e) { /* 落入纯文本 */ }
  }
  return { summary: s, points: [] }
}
