function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 表格分隔行（GFM）：| --- | :--: |，仅含 | - : 空格且至少一个 - */
function isTableSep(line) {
  const t = line.trim()
  return t.includes('-') && t.includes('|') && /^\|?[\s:|-]+\|?$/.test(t)
}

/** 拆表格行：| a | b | → ['a', 'b'] */
function splitRow(line) {
  let t = line.trim()
  if (t.startsWith('|')) t = t.slice(1)
  if (t.endsWith('|')) t = t.slice(0, -1)
  return t.split('|').map((c) => c.trim())
}

/** 管道表格 → HTML（支持 :--: 对齐；外层容器横向滚动，窄屏不撑破布局） */
function buildTable(header, sep, rows) {
  const aligns = splitRow(sep).map((c) => {
    const l = c.startsWith(':')
    const r = c.endsWith(':')
    return l && r ? 'center' : r ? 'right' : l ? 'left' : ''
  })
  const cell = (tag, v, i) =>
    '<' + tag + (aligns[i] ? ' style="text-align:' + aligns[i] + '"' : '') + '>' + v + '</' + tag + '>'
  let html = '<div class="md-table-wrap"><table class="md-table"><thead><tr>'
    + header.map((c, i) => cell('th', c, i)).join('')
    + '</tr></thead><tbody>'
  for (const r of rows) {
    html += '<tr>' + header.map((_, i) => cell('td', r[i] == null ? '' : r[i], i)).join('') + '</tr>'
  }
  return html + '</tbody></table></div>'
}

/** 极简 Markdown 渲染：标题/加粗/行内码/代码块/列表/引用/表格/段落 */
export function mdToHtml(text) {
  if (!text) return ''
  const lines = esc(text).split('\n')
  let html = ''
  let inCode = false
  const codeBuf = []
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i]
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
    // 表格：本行以 | 开头，且下一行是分隔行（GFM 判定规则）
    if (raw.trim().startsWith('|') && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      const rows = []
      let j = i + 2
      while (j < lines.length && lines[j].trim().startsWith('|')) {
        rows.push(splitRow(lines[j]))
        j++
      }
      html += buildTable(splitRow(raw), lines[i + 1], rows)
      i = j - 1
      continue
    }
    if (/^#{1,4}\s/.test(raw)) {
      const lv = raw.match(/^#+/)[0].length
      html += '<h' + lv + '>' + raw.replace(/^#+\s*/, '') + '</h' + lv + '>'
    } else if (/^\s*([-*_])\1{2,}\s*$/.test(raw)) {
      html += '<hr class="md-divider">'
    } else if (/^\s*[-*]\s+/.test(raw)) {
      html += '<p>· ' + raw.replace(/^\s*[-*]\s+/, '') + '</p>'
    } else if (raw.startsWith('&gt;')) {
      // 注意：esc() 已把 > 转义为 &gt;，此处需按转义后的形式判断（原写 '>' 导致引用从未生效）
      html += '<blockquote>' + raw.replace(/^&gt;\s?/, '') + '</blockquote>'
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