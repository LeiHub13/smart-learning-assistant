<template>
  <div class="shm">
    <div class="shm-months">
      <span v-for="c in columns" :key="c.i + c.label" :style="{ gridColumn: c.i + 1 }">{{ c.label }}</span>
    </div>
    <div class="shm-main">
      <div class="shm-dow">
        <span style="grid-row: 1">周一</span>
        <span style="grid-row: 3">周三</span>
        <span style="grid-row: 5">周五</span>
      </div>
      <div class="shm-grid">
        <template v-for="(w, wi) in weeks" :key="wi">
          <span
            v-for="(c, ci) in w"
            :key="ci"
            class="shm-cell"
            :class="c ? level(c.minutes) : 'pad'"
            :title="c ? c.label : ''"
          ></span>
        </template>
      </div>
    </div>
    <div class="shm-legend">
      <span>少</span>
      <span v-for="l in LEVELS" :key="l.cls" class="shm-leg">
        <i class="shm-cell" :class="l.cls"></i><em>{{ l.text }}</em>
      </span>
      <span>多</span>
      <em class="shm-note">按当日学习分钟数分档</em>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/** 学习热力图（GitHub 贡献图风格）：列=周、行=星期；顶部中文月份、左侧周一/周三/周五，悬停看完整日期。 */
const props = defineProps({
  /** [{ date: 'YYYY-MM-DD', minutes: Number }]，按日期升序，近 12 周 */
  calendar: { type: Array, default: () => [] }
})

const DOW = ['一', '二', '三', '四', '五', '六', '日']
const LEVELS = [
  { cls: 'hm-0', text: '无' },
  { cls: 'hm-1', text: '<30m' },
  { cls: 'hm-2', text: '<1h' },
  { cls: 'hm-3', text: '<2h' },
  { cls: 'hm-4', text: '≥2h' }
]

const level = (m) => (!m ? 'hm-0' : m < 30 ? 'hm-1' : m < 60 ? 'hm-2' : m < 120 ? 'hm-3' : 'hm-4')

const grid = computed(() => {
  const cal = props.calendar || []
  if (!cal.length) return null
  const cells = cal.map((c) => {
    const d = new Date(c.date + 'T00:00:00')
    const dow = (d.getDay() + 6) % 7
    const p = (x) => String(x).padStart(2, '0')
    return {
      ...c,
      dow,
      label: `${d.getFullYear()} 年 ${Number(c.date.slice(5, 7))} 月 ${Number(c.date.slice(8, 10))} 日 周${DOW[dow]} · ${c.minutes || 0} 分钟`
    }
  })
  const weeks = []
  let week = Array(cells[0].dow).fill(null)
  for (const c of cells) {
    week.push(c)
    if (week.length === 7) { weeks.push(week); week = [] }
  }
  if (week.length) weeks.push(week.concat(Array(7 - week.length).fill(null)))
  // 月份标注：每周列首日跨月时标记一次
  const columns = []
  let lastM = null
  weeks.forEach((w, i) => {
    const first = w.find(Boolean)
    if (!first) return
    const m = Number(first.date.slice(5, 7))
    if (m !== lastM) { columns.push({ i, label: m + '月' }); lastM = m }
  })
  return { weeks, columns }
})

const weeks = computed(() => grid.value?.weeks || [])
const columns = computed(() => grid.value?.columns || [])
</script>

<style scoped>
.shm-months {
  display: grid; grid-auto-flow: column; grid-auto-columns: 13px; gap: 3px;
  margin: 0 0 4px 34px; font-size: 11px; color: var(--muted);
}
.shm-months span { white-space: nowrap; }
.shm-main { display: flex; gap: 6px; }
.shm-dow { display: grid; grid-template-rows: repeat(7, 13px); gap: 3px; width: 28px; font-size: 11px; color: var(--muted); }
.shm-dow span { line-height: 13px; }
.shm-grid { display: grid; grid-template-rows: repeat(7, 13px); grid-auto-flow: column; grid-auto-columns: 13px; gap: 3px; width: fit-content; }
.shm-cell { width: 13px; height: 13px; border-radius: 3px; display: inline-block; }
.shm-cell.pad { visibility: hidden; }
.shm-legend { display: flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 11px; color: var(--muted); flex-wrap: wrap; }
.shm-leg { display: inline-flex; align-items: center; gap: 3px; }
.shm-legend .shm-cell { width: 11px; height: 11px; }
.shm-leg em, .shm-note { font-style: normal; }
.shm-note { margin-left: 6px; }
.hm-0 { background: #ebedf0; }
.hm-1 { background: #9be9a8; }
.hm-2 { background: #40c463; }
.hm-3 { background: #30a14e; }
.hm-4 { background: #216e39; }
html.dark .hm-0 { background: #1c2128; }
html.dark .hm-1 { background: #033a16; }
html.dark .hm-2 { background: #196c2e; }
html.dark .hm-3 { background: #2ea043; }
html.dark .hm-4 { background: #56d364; }
</style>
