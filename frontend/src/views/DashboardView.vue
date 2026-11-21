<template>
  <div>
    <div class="page-title">你好，{{ me?.nickname || me?.username || '同学' }} 👋</div>
    <div class="page-sub">今天也保持学习节奏 —— 概览、推荐与快捷入口</div>

    <!-- 数据概览 -->
    <div class="card rv">
      <div class="stat-grid">
        <div v-for="(s, i) in statItems" :key="s.label" class="stat-item" :style="{ '--d': (i * .05) + 's' }">
          <span class="stat-ico" :class="s.tone"><AppIcon :name="s.icon" :size="15" /></span>
          <div class="stat-num">{{ s.value }}</div>
          <div class="stat-lab">{{ s.label }}</div>
        </div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="card rv" style="--d:.08s">
      <div class="row quick-row">
        <button class="btn" @click="go('/practice')"><AppIcon name="practice" :size="14" /> 开始练习</button>
        <button class="btn" @click="go('/mistakes')"><AppIcon name="target" :size="14" /> 错题本{{ mistakeTotal ? '（' + mistakeTotal + '）' : '' }}</button>
        <button class="btn" @click="go('/favorites')"><AppIcon name="star" :size="14" /> 收藏夹{{ favCount ? '（' + favCount + '）' : '' }}</button>
        <button class="btn" @click="go('/flashcards')"><AppIcon name="zap" :size="14" /> 闪卡复习{{ dueCards ? '（今日 ' + dueCards + ' 张到期）' : '' }}</button>
        <button class="btn ghost" @click="go('/exam')"><AppIcon name="exam" :size="14" /> 进入考试</button>
        <button class="btn ghost" @click="go('/chat')"><AppIcon name="chat" :size="14" /> 智能答疑</button>
        <button class="btn ghost" @click="go('/progress')"><AppIcon name="progress" :size="14" /> 查看学情</button>
      </div>
    </div>

    <div class="grid2">
      <div class="col-left">
        <!-- 成绩曲线 -->
        <div class="card rv" style="--d:.16s">
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
        <EmptyState v-else icon="progress" title="暂无练习数据" hint="完成一组练习后，成绩曲线会从这里开始生长" />
        </div>

        <!-- 学习热力图 -->
        <div class="card rv" style="--d:.22s">
          <div class="hm-top">
            <h3>学习热力图（近 12 周）</h3>
            <div v-if="heat" class="hm-stats">
              <span>累计 <b>{{ fmtHeat(heat.total) }}</b></span>
              <span>活跃 <b>{{ heat.activeDays }}</b> 天</span>
              <span>最长连续 <b>{{ heat.best }}</b> 天</span>
            </div>
          </div>
          <StudyHeatmap v-if="heat" :calendar="study?.calendar || []" />
          <EmptyState v-else compact icon="clock" title="暂无学习记录" hint="使用系统期间每分钟自动记录学习时长" />
        </div>
      </div>

      <!-- 今日推荐 -->
      <div class="card rv" style="--d:.2s">
        <h3>今日推荐</h3>
        <EmptyState v-if="!recommend.length" icon="lightbulb" title="暂无推荐"
                    hint="做一组练习后，AI 会根据错题与薄弱知识点生成今日推荐" />
        <div v-for="(r, i) in recommend" :key="i" class="rc-item rv" :style="{ '--d': (.26 + i * .06) + 's' }"
             :class="{ clickable: r.action }" @click="r.action && router.push(r.action)">
          <span class="rc-icon"><AppIcon :name="rcIcon(r.type)" :size="16" /></span>
          <div class="rc-body">
            <div class="rc-title">{{ r.title }}</div>
            <div class="rc-reason">{{ r.reason }}</div>
            <details v-if="r.snippet" class="rc-snippet" @click.stop>
              <summary>展开资料原文</summary>
              <div class="rc-snippet-txt">{{ r.snippet }}</div>
            </details>
          </div>
          <span v-if="r.action" class="rc-go">{{ goLabel(r.type) }}</span>
        </div>
      </div>
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
import StudyHeatmap from '../components/StudyHeatmap.vue'
import EmptyState from '../components/EmptyState.vue'
import { chartTheme, onThemeChange } from '../theme'

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
const dueCards = ref(0)
const chartRef = ref(null)
let chart = null

const PALETTE = ['#0969da', '#4a6cf7', '#3aa675', '#c05a4d', '#8b6fc0', '#4bacc6', '#f59e0b']

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
const goLabel = (t) => (t === 'startup' ? '去摸底 →' : '去做题 →')

/** 概览统计：图标 + 数值 + 标签（彩色图标区分指标类别） */
const statItems = computed(() => [
  { icon: 'clock', tone: 't-blue', value: fmtHours(study.value?.totalMinutes), label: '学习时长' },
  { icon: 'zap', tone: 't-green', value: streak.value + ' 天', label: '连续学习' },
  { icon: 'progress', tone: 't-orange', value: study.value?.activeDays ?? 0, label: '活跃天数' },
  { icon: 'practice', tone: 't-purple', value: practiceCount.value, label: '练习次数' },
  { icon: 'star', tone: 't-yellow', value: favCount.value, label: '收藏题目' },
  { icon: 'bell', tone: 't-red', value: unread.value, label: '未读通知' },
])

/** 热力图统计（网格渲染在 StudyHeatmap 组件内） */
const heat = computed(() => {
  const cal = study.value?.calendar || []
  if (!cal.length) return null
  const total = cal.reduce((sum, c) => sum + (c.minutes || 0), 0)
  const activeDays = cal.filter((c) => c.minutes > 0).length
  let run = 0
  let best = 0
  for (const c of cal) {
    run = c.minutes > 0 ? run + 1 : 0
    if (run > best) best = run
  }
  return { total, activeDays, best }
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
  // 类目轴：横轴为「所有课程出现过的日期 + 今天」的并集，按天均匀分布，
  // 标签由 ECharts 自动抽样避免挤叠（time 轴 + customValues 在数据扎堆时会把标签挤成一团）
  const dateSet = new Set()
  seriesList.value.forEach((s) => s.points.forEach((p) => dateSet.add(Array.isArray(p) ? p[0] : p.value[0])))
  const now = new Date()
  const pad = (x) => String(x).padStart(2, '0')
  // 始终包含今天（当日没做题只占一个刻度、不画点）
  dateSet.add(now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()))
  const allDates = [...dateSet].sort()
  const fmtDay = (d) => { const dt = new Date(d + 'T00:00:00'); return (dt.getMonth() + 1) + '-' + dt.getDate() }
  // 面积填充用低透明度 rgba 字符串。echarts 6.1 的 LinearGradient 渐变实例在部分
  // 渲染路径会被吞掉、回落成默认 0.7 实色（浏览器像素实测），字符串全路径稳定。
  const hexToRgba = (hex, a) => {
    const n = parseInt(hex.slice(1), 16)
    return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')'
  }
  const th = chartTheme()
  const buildSeries = () => seriesList.value.map((s) => {
    // 把各课程的散点对齐到统一横轴：没练到的日期补 null，配合 connectNulls 保持连线
    const byDate = new Map(s.points.map((p) => [Array.isArray(p) ? p[0] : p.value[0], Array.isArray(p) ? p[1] : p.value[1]]))
    const active = activeId.value === s.id
    const dim = activeId.value != null && !active
    return {
      name: s.name,
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: active ? 8 : 6,
      connectNulls: true,
      data: allDates.map((d) => byDate.get(d) ?? null),
      itemStyle: { color: s.color, opacity: dim ? 0.15 : 1 },
      lineStyle: { width: active ? 3.5 : 2, opacity: dim ? 0.12 : 1 },
      areaStyle: { color: hexToRgba(s.color, dim ? 0 : (active ? 0.2 : 0.08)) },
      emphasis: { focus: 'series' },
      z: active ? 10 : 2
    }
  })
  chart.setOption({
    tooltip: { trigger: 'axis', backgroundColor: th.tipBg, borderColor: th.tipLine, textStyle: { color: th.tipText } },
    grid: { left: 40, right: 16, top: 20, bottom: 24 },
    animationDuration: 700,
    xAxis: {
      type: 'category',
      data: allDates,
      boundaryGap: false,
      axisTick: { show: false },
      axisLabel: {
        interval: 'auto', hideOverlap: true,
        showMinLabel: true, showMaxLabel: true,
        fontSize: 10, margin: 12, color: th.text,
        formatter: fmtDay
      },
      axisLine: { lineStyle: { color: th.split } }
    },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%', color: th.text }, splitLine: { lineStyle: { color: th.split } } },
    series: buildSeries()
  })
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
  // 复习义务随复习动作实时变化，回首页时同样刷新
  api('/api/flashcards/due').then((r) => { dueCards.value = r.due || 0 }).catch(() => {})
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

const offTheme = onThemeChange(() => renderChart())

onBeforeUnmount(() => {
  offTheme()
  window.removeEventListener('resize', onResize)
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
.grid2 { display: grid; grid-template-columns: 3fr 2fr; gap: 16px; align-items: start; }
.col-left { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
@media (max-width: 900px) { .grid2 { grid-template-columns: 1fr; } }

/* ===== 入场级联（--d 控制延迟，backwards 保证动画开始前隐藏） ===== */
.rv { animation: rv-rise .55s cubic-bezier(.22, .61, .36, 1) backwards; animation-delay: var(--d, 0s); }
@keyframes rv-rise { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: none; } }

/* ===== 概览统计：彩色图标 + 数值 + 标签 ===== */
.stat-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; }
.stat-item {
  display: flex; flex-direction: column; align-items: center; text-align: center;
  padding: 12px 8px 10px; border-radius: 8px;
  animation: rv-rise .5s cubic-bezier(.22, .61, .36, 1) backwards; animation-delay: var(--d, 0s);
  transition: background .15s ease, transform .2s ease;
}
.stat-item:hover { background: var(--soft); transform: translateY(-2px); }
.stat-ico { width: 30px; height: 30px; border-radius: 8px; display: flex; align-items: center; justify-content: center; margin-bottom: 8px; }
.t-blue { background: #ddf4ff; color: #0969da; }
.t-green { background: #dafbe1; color: #1a7f37; }
.t-orange { background: #fff1e5; color: #bc4c00; }
.t-purple { background: #fbefff; color: #8250df; }
.t-yellow { background: #fff8c5; color: #9a6700; }
.t-red { background: #ffebe9; color: #cf222e; }
.stat-num { font-size: 22px; font-weight: 700; color: var(--text); letter-spacing: -.01em; }
.stat-lab { margin-top: 3px; font-size: 12px; color: var(--muted); }
@media (max-width: 900px) { .stat-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 480px) { .stat-grid { grid-template-columns: repeat(2, 1fr); } }
html.dark .t-blue { background: rgba(56, 139, 253, .16); color: #4493f8; }
html.dark .t-green { background: rgba(63, 185, 80, .16); color: #3fb950; }
html.dark .t-orange { background: rgba(240, 136, 62, .16); color: #f0883e; }
html.dark .t-purple { background: rgba(163, 113, 247, .16); color: #d2a8ff; }
html.dark .t-yellow { background: rgba(187, 128, 9, .2); color: #e3b341; }
html.dark .t-red { background: rgba(248, 81, 73, .16); color: #f85149; }
@media (prefers-reduced-motion: reduce) { .rv, .stat-item { animation: none !important; } }

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
.tl-item:hover { background: var(--hover); }
.tl-item.active { background: var(--active); font-weight: 700; }
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
.rc-item.clickable { cursor: pointer; border-radius: 8px; margin: 0 -8px; padding: 9px 8px; transition: background .15s ease; }
.rc-item.clickable:hover { background: var(--soft); }
.rc-icon { color: var(--accent); display: inline-flex; align-items: center; }
.rc-title { font-weight: 700; font-size: 13px; }
.rc-reason { font-size: 12px; color: var(--muted); margin-top: 2px; line-height: 1.6; }
.rc-body { flex: 1; min-width: 0; }
.rc-go { flex-shrink: 0; align-self: center; font-size: 12px; font-weight: 600; color: var(--primary); white-space: nowrap; }
.rc-snippet { margin-top: 6px; }
.rc-snippet summary { cursor: pointer; user-select: none; list-style: none; font-size: 12px; font-weight: 600; color: var(--primary); }
.rc-snippet summary::-webkit-details-marker { display: none; }
.rc-snippet[open] summary { margin-bottom: 4px; }
.rc-snippet-txt { font-size: 12px; color: var(--muted); line-height: 1.7; background: var(--soft); border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; }
.heatmap { display: grid; grid-template-rows: repeat(7, 13px); grid-auto-flow: column; grid-auto-columns: 13px; gap: 3px; }
.hm-top { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 8px; }
.hm-top h3 { margin-bottom: 0; }
.hm-stats { display: flex; gap: 14px; font-size: 12px; color: var(--muted); }
.hm-stats b { color: var(--text); font-weight: 700; }
.hm-wrap { margin-top: 8px; }

</style>
