function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 极简 Markdown 渲染：标题/加粗/行内码/代码块/列表/引用/段落 */
export function mdToHtml(text) {
  if (!text) return ''
  let html = ''
  let inCode = false
  const codeBuf = []
  for (const raw of esc(text).split('\n')) {
    if (raw.startsWith('```')) {
      if (inCode) {
        html += '<pre>' + codeBuf.join('\n') + '</pre>'
        codeBuf.length = 0
        inCode = false
      } else {
        html += '<p></p>'
        inCode = true
      }
      continue
    }
    if (inCode) {
      codeBuf.push(raw)
      continue
    }
    if (/^#{1,4}\s/.test(raw)) {
      const lv = raw.match(/^#+/)[0].length
      html += '<h' + lv + '>' + raw.replace(/^#+\s*/, '') + '</h' + lv + '>'
    } else if (/^\s*[-*]\s+/.test(raw)) {
      html += '<p>· ' + raw.replace(/^\s*[-*]\s+/, '') + '</p>'
    } else if (raw.startsWith('>')) {
      html += '<blockquote>' + raw.replace(/^>\s?/, '') + '</blockquote>'
    } else if (raw.trim() === '') {
      html += '<p></p>'
    } else {
      html += '<p>' + raw + '</p>'
    }
  }
  if (inCode) html += '<pre>' + codeBuf.join('\n') + '</pre>'
  return html
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`(.+?)`/g, '<code>$1</code>')
}

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