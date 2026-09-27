// 生成 tabBar 图标：SVG 设计 → 两态 PNG（uni-app 原生 tabBar 不支持 SVG，需转 PNG）
// 运行：先 `npm i @resvg/resvg-js`（任意目录），再 `node scripts/gen-tabbar-icons.mjs`
// 产物已提交在 static/tabbar/，只有改图标样式时才需要重跑
import { writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const pkg = (await import('@resvg/resvg-js')).default ?? (await import('@resvg/resvg-js'))
const Resvg = pkg.Resvg

const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'static', 'tabbar')
mkdirSync(OUT, { recursive: true })

// 简洁线性风格图标（24 viewBox，stroke 1.8）
const ICONS = {
  // 答疑：对话气泡
  chat: '<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/>',
  // 练习：书写中的笔
  practice: '<path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/>',
  // 错题本：书 + 叉
  mistake: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/><line x1="10" y1="7.5" x2="14" y2="11.5"/><line x1="14" y1="7.5" x2="10" y2="11.5"/>',
  // 我的：人形
  profile: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
  // 计划：日历 + 对勾
  plan: '<rect x="3" y="4" width="18" height="17" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="9.5" x2="21" y2="9.5"/><path d="M9 14.5l2.2 2.2 4.2-4.2"/>'
}

const COLORS = { on: '#4f6ef7', off: '#9aa0ab' }

const svgOf = (paths, color) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${paths}</svg>`

for (const [name, paths] of Object.entries(ICONS)) {
  for (const [state, color] of Object.entries(COLORS)) {
    const svg = svgOf(paths, color)
    const png = new Resvg(svg, { fitTo: { mode: 'width', value: 162 } }).render().asPng()
    const file = join(OUT, `${name}${state === 'on' ? '-on' : ''}.png`)
    writeFileSync(file, png)
    console.log('written', file, png.length, 'bytes')
  }
}
