<template>
  <div>
    <div class="page-title">你好，{{ me?.nickname || me?.username || '同学' }} 👋</div>
    <div class="page-sub">今天也保持学习节奏 —— 概览、推荐与快捷入口</div>

    <!-- 数据概览 -->
    <div class="card">
      <div class="row">
        <div class="stat" style="flex:1"><div class="num">{{ fmtHours(study?.totalMinutes) }}</div><div class="lab">学习时长</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ streak }} 天</div><div class="lab">连续学习</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ study?.activeDays ?? 0 }}</div><div class="lab">活跃天数</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ practiceCount }}</div><div class="lab">练习次数</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ favCount }}</div><div class="lab">收藏题目</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ unread }}</div><div class="lab">未读通知</div></div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="card">
      <div class="row quick-row">
        <button class="btn" @click="go('/practice')"><AppIcon name="practice" :size="14" /> 开始练习</button>
        <button class="btn" @click="go('/mistakes')"><AppIcon name="target" :size="14" /> 错题本{{ mistakeTotal ? '（' + mistakeTotal + '）' : '' }}</button>
        <button class="btn" @click="go('/favorites')"><AppIcon name="star" :size="14" /> 收藏夹{{ favCount ? '（' + favCount + '）' : '' }}</button>
        <button class="btn ghost" @click="go('/exam')"><AppIcon name="exam" :size="14" /> 进入考试</button>
        <button class="btn ghost" @click="go('/chat')"><AppIcon name="chat" :size="14" /> 智能答疑</button>
        <button class="btn ghost" @click="go('/progress')"><AppIcon name="progress" :size="14" /> 查看学情</button>
      </div>
    </div>

    <div class="grid2">
      <!-- 成绩曲线 -->
      <div class="card">
        <h3>最近成绩曲线</h3>
        <div v-if="seriesList.length" class="trend-wrap">
          <div ref="chartRef" class="d-chart"></div>
          <div class="trend-legend">
            <button
              v-for="s in seriesList"
              :key="s.id"
              class="tl-item"
              :class="{ active: activeId === s.id }"
              :title="activeId === s.id ? '取消高亮' : '高亮该课程'"
              @click="toggleSeries(s.id)"
            >
              <span class="tl-dot" :style="{ background: s.color }"></span>
              <span class="tl-name">{{ s.name }}</span>
            </button>
            <div v-if="activeId" class="tl-tip">再次点击取消高亮</div>
          </div>
        </div>
        <div v-if="!seriesList.length" class="empty">暂无练习数据</div>
      </div>

      <!-- 今日推荐 -->
      <div class="card">
        <h3>今日推荐</h3>
        <div v-if="!recommend.length" class="empty">暂无推荐，做一组练习后生成</div>
        <div v-for="(r, i) in recommend" :key="i" class="rc-item">
            <span class="rc-icon"><AppIcon :name="rcIcon(r.type)" :size="16" /></span>
          <div class="rc-body">
            <div class="rc-title">{{ r.title }}</div>
            <div class="rc-reason">{{ r.reason }}</div>
          </div>
        </div>
      </div>
    </div>
    <!-- 学习热力图 -->
    <div class="card">
      <div class="hm-top">
        <h3>学习热力图（近 12 周）</h3>
        <div v-if="heat" class="hm-stats">
          <span>累计 <b>{{ fmtHeat(heat.total) }}</b></span>
          <span>活跃 <b>{{ heat.activeDays }}</b> 天</span>
          <span>最长连续 <b>{{ heat.best }}</b> 天</span>
        </div>
      </div>
      <div v-if="heat" class="hm-wrap">
        <div class="hm-months">
          <span v-for="col in heat.columns" :key="col.i" :style="{ gridColumn: col.i + 1 }">{{ col.label }}</span>
        </div>
        <div class="hm-main">
          <div class="hm-dow">
            <span style="grid-row: 1">一</span>
            <span style="grid-row: 3">三</span>
            <span style="grid-row: 5">五</span>
          </div>
          <div class="heatmap">
            <template v-for="(w, wi) in heat.weeks" :key="wi">
              <span
                v-for="(c, ci) in w"
                :key="ci"
                class="hm-cell"
                :class="c ? hmLevel(c.minutes) : 'pad'"
                :title="c ? c.label : ''"
              ></span>
            </template>
          </div>
        </div>
        <div class="hm-legend">
          <span>少</span>
          <i class="hm-cell hm-0"></i><i class="hm-cell hm-1"></i><i class="hm-cell hm-2"></i><i class="hm-cell hm-3"></i><i class="hm-cell hm-4"></i>
          <span>多</span>
          <span class="hm-unit">（按当日学习分钟数分档）</span>
        </div>
      </div>
      <div v-else class="empty">暂无数据（使用系统期间每分钟自动记录）</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, onActivated, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api, unreadCount } from '../api'
import AppIcon from '../components/AppIcon.vue'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

defineOptions({ name: 'DashboardView' })

const router = useRouter()
const me = ref(null)
const study = ref(null)
const recommend = ref([])
const seriesList = ref([])
const activeId = ref(null)
const practiceCount = ref(0)
const favCount = ref(0)
const unread = ref(0)
const mistakeTotal = ref(0)
const chartRef = ref(null)
let chart = null

const PALETTE = ['#4f46e5', '#4a6cf7', '#3aa675', '#c05a4d', '#8b6fc0', '#4bacc6', '#f59e0b']

const fmtHours = (m) => {
  if (!m) return '0h'
  return m >= 60 ? Math.round(m / 6) / 10 + 'h' : m + 'min'
}

/** 连续学习天数：从今天往回数有学习记录的天数（今天还没学不打断，从昨天起算） */
const streak = computed(() => {
  const map = new Map((study.value?.calendar || []).map((c) => [c.date, c.minutes || 0]))
  const fmt = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
  const cur = new Date()
  if (!(map.get(fmt(cur)) > 0)) cur.setDate(cur.getDate() - 1)
  let n = 0
  while (map.get(fmt(cur)) > 0) {
    n++
    cur.setDate(cur.getDate() - 1)
  }
  return n
})

const rcIcon = (t) => ({ startup: 'zap', review_kp: 'alarm', practice_kp: 'practice', document: 'report' }[t] || 'lightbulb')
const hmLevel = (m) => (m >= 120 ? 'hm-4' : m >= 60 ? 'hm-3' : m >= 30 ? 'hm-2' : m >= 10 ? 'hm-1' : 'hm-0')
const DOW = ['一', '二', '三', '四', '五', '六', '日']

/** 把 84 天日历整理成周对齐热力图：列=周、行=星期，附月份标注与统计 */
const heat = computed(() => {
  const cal = study.value?.calendar || []
  if (!cal.length) return null
  const cells = cal.map((c) => {
    const dow = (new Date(c.date + 'T00:00:00').getDay() + 6) % 7
    return {
      ...c,
      dow,
      label: `${Number(c.date.slice(5, 7))}月${Number(c.date.slice(8, 10))}日 周${DOW[dow]} · ${c.minutes} 分钟`,
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
  const total = cal.reduce((s, c) => s + (c.minutes || 0), 0)
  const activeDays = cal.filter((c) => c.minutes > 0).length
  let run = 0
  let best = 0
  for (const c of cal) {
    run = c.minutes > 0 ? run + 1 : 0
    if (run > best) best = run
  }
  return { weeks, columns, total, activeDays, best }
})

const fmtHeat = (m) => (m >= 60 ? Math.round(m / 6) / 10 + ' 小时' : m + ' 分钟')

const go = (path) => router.push(path)

const renderChart = () => {
  if (!chartRef.value || !seriesList.value.length) return
  if (chart && chart.getDom() !== chartRef.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartRef.value)
  // 刻度对齐数据日期，且始终包含今天（当日没做题只显示日期、不画点）
  const dateSet = new Set()
  seriesList.value.forEach((s) => s.points.forEach((p) => dateSet.add(Array.isArray(p) ? p[0] : p.value[0])))
  const now = new Date()
  const pad = (x) => String(x).padStart(2, '0')
  dateSet.add(now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()))
  let tickDates = [...dateSet].sort()
  if (tickDates.length > 12) {
    const step = Math.ceil(tickDates.length / 12)
    tickDates = tickDates.filter((d, i) => i % step === 0 || i === tickDates.length - 1)
  }
  const ticks = tickDates.map((d) => new Date(d + 'T00:00:00'))
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 20, bottom: 24 },
    xAxis: {
      type: 'time',
      max: new Date(),
      axisTick: { customValues: ticks },
      axisLabel: {
        customValues: ticks,
        rotate: 90,
        fontSize: 10,
        margin: 11,
        formatter: (v) => { const d = new Date(v); return (d.getMonth() + 1) + '-' + d.getDate() }
      }
    },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    series: seriesList.value.map((s) => {
      const active = activeId.value === s.id
      const dim = activeId.value != null && !active
      return {
        name: s.name,
        type: 'line',
        smooth: true,
        symbolSize: active ? 8 : 6,
        data: s.points,
        itemStyle: { color: s.color, opacity: dim ? 0.15 : 1 },
        lineStyle: { width: active ? 3.5 : 2, opacity: dim ? 0.12 : 1 },
        areaStyle: active ? { opacity: 0.1 } : { opacity: dim ? 0 : 0.06 },
        emphasis: { focus: 'series' },
        z: active ? 10 : 2
      }
    })
  }, { notMerge: true })
}

const toggleSeries = (id) => {
  activeId.value = activeId.value === id ? null : id
  renderChart()
}

const onResize = () => { if (chart) chart.resize() }

// KeepAlive 下每次回到首页都会触发，昵称改完回来即刷新
onActivated(() => {
  api('/api/auth/me').then((m) => { me.value = m }).catch(() => {})
  // KeepAlive 回到首页时刷新错题/收藏数（错题重练出本、收藏增减都会变）
  api('/api/mistakes?size=1').then((m) => { mistakeTotal.value = m.total || 0 }).catch(() => {})
  api('/api/favorites/count').then((c) => { favCount.value = c.total || 0 }).catch(() => {})
})

onMounted(async () => {
  window.addEventListener('resize', onResize)
  // 并行拉取四类数据（课程取第一门作上下文）
  const courses = await api('/api/courses')
  const courseId = courses.length ? courses[0].id : null

  api('/api/study/summary').then((s) => { study.value = s }).catch(() => {})
  unreadCount().then((c) => { unread.value = c.count || 0 }).catch(() => {})
  api('/api/practice/history?size=1').then((h) => { practiceCount.value = h.total || 0 }).catch(() => {})
  api('/api/favorites/count').then((c) => { favCount.value = c.total || 0 }).catch(() => {})
  api('/api/mistakes?size=1').then((m) => { mistakeTotal.value = m.total || 0 }).catch(() => {})
  if (courseId) {
    api('/api/recommend?courseId=' + courseId).then((r) => { recommend.value = r }).catch(() => {})
    // 每门课程一条成绩曲线（无数据的课程不画）；当日没做题就没有点，只保留轴上的今天刻度
    Promise.all(courses.map(async (c, i) => {
      try {
        const t = await api('/api/practice/trend?courseId=' + c.id)
        if (!t.length) return null
        return { id: c.id, name: c.name, color: PALETTE[i % PALETTE.length], points: t.map((x) => [x.date, x.rate]) }
      } catch { return null }
    })).then((list) => {
      seriesList.value = list.filter(Boolean)
      nextTick(renderChart)
    })
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
.grid2 { display: grid; grid-template-columns: 3fr 2fr; gap: 16px; align-items: start; }
@media (max-width: 900px) { .grid2 { grid-template-columns: 1fr; } }
.d-chart { width: 100%; height: 240px; flex: 1; min-width: 0; }
.trend-wrap { display: flex; align-items: stretch; }
.trend-legend {
  display: flex; flex-direction: column; justify-content: center; gap: 4px;
  min-width: 128px; max-width: 160px; padding: 6px 0 6px 12px;
  border-left: 1px solid var(--border);
}
.tl-item {
  display: flex; align-items: center; gap: 8px; width: 100%;
  padding: 6px 8px; background: none; border: none; border-radius: 8px;
  cursor: pointer; font-family: inherit; font-size: 12px; color: inherit;
  text-align: left; transition: background 0.2s ease;
}
.tl-item:hover { background: rgba(0,0,0,0.04); }
.tl-item.active { background: rgba(0,0,0,0.07); font-weight: 700; }
.tl-dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.tl-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tl-tip { font-size: 10px; color: var(--muted); margin-top: 2px; padding-left: 8px; }
@media (max-width: 480px) {
  .trend-wrap { flex-direction: column; }
  .trend-legend { flex-direction: row; flex-wrap: wrap; max-width: none; border-left: none; padding: 8px 0 0; border-top: 1px solid var(--border); }
}
.quick-row { gap: 12px; flex-wrap: wrap; }
.rc-item { display: flex; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--border); }
.rc-item:last-child { border-bottom: none; }
.rc-icon { color: var(--accent); display: inline-flex; align-items: center; }
.rc-title { font-weight: 700; font-size: 13px; }
.rc-reason { font-size: 12px; color: var(--muted); margin-top: 2px; line-height: 1.6; }
.heatmap { display: grid; grid-template-rows: repeat(7, 13px); grid-auto-flow: column; grid-auto-columns: 13px; gap: 3px; }
.hm-cell { width: 13px; height: 13px; border-radius: 3px; display: inline-block; }
.hm-cell.pad { visibility: hidden; }
.hm-top { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 8px; }
.hm-top h3 { margin-bottom: 0; }
.hm-stats { display: flex; gap: 14px; font-size: 12px; color: var(--muted); }
.hm-stats b { color: var(--text); font-weight: 700; }
.hm-wrap { margin-top: 8px; }
.hm-months {
  display: grid; grid-auto-flow: column; grid-auto-columns: 13px; gap: 3px;
  margin: 0 0 4px 22px; font-size: 11px; color: var(--muted);
}
.hm-months span { white-space: nowrap; }
.hm-main { display: flex; gap: 6px; }
.hm-dow { display: grid; grid-template-rows: repeat(7, 13px); gap: 3px; width: 16px; font-size: 10px; color: var(--muted); }
.hm-dow span { line-height: 13px; }
.hm-legend { display: flex; align-items: center; gap: 4px; margin-top: 10px; font-size: 11px; color: var(--muted); }
.hm-legend .hm-cell { width: 11px; height: 11px; }
.hm-unit { margin-left: 4px; }
.hm-0 { background: #e8eaf2; }
.hm-1 { background: #c7d2fe; }
.hm-2 { background: #a5b4fc; }
.hm-3 { background: #818cf8; }
.hm-4 { background: #4f46e5; }
</style>
