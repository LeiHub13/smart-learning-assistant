<template>
  <div>
    <div class="page-title">你好，{{ me?.nickname || '同学' }} 👋</div>
    <div class="page-sub">今天也保持学习节奏 —— 概览、推荐与快捷入口</div>

    <!-- 数据概览 -->
    <div class="card">
      <div class="row">
        <div class="stat" style="flex:1"><div class="num">{{ fmtHours(study?.totalMinutes) }}</div><div class="lab">学习时长</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ study?.activeDays ?? 0 }}</div><div class="lab">活跃天数</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ practiceCount }}</div><div class="lab">练习次数</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ favCount }}</div><div class="lab">收藏题目</div></div>
        <div class="stat" style="flex:1"><div class="num">{{ unread }}</div><div class="lab">未读通知</div></div>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="card">
      <div class="row quick-row">
        <button class="btn" @click="go('/practice')">📝 开始练习</button>
        <button class="btn ghost" @click="go('/exam')">📋 进入考试</button>
        <button class="btn ghost" @click="go('/chat')">💬 提问答疑</button>
        <button class="btn ghost" @click="go('/progress')">📈 查看学情</button>
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
          <span class="rc-icon">{{ rcIcon(r.type) }}</span>
          <div class="rc-body">
            <div class="rc-title">{{ r.title }}</div>
            <div class="rc-reason">{{ r.reason }}</div>
          </div>
        </div>
      </div>
    </div>
    <!-- 学习热力图 -->
    <div class="card">
      <h3>学习热力图（近 12 周）</h3>
      <div v-if="study?.calendar?.length" class="heatmap">
        <span v-for="c in study.calendar" :key="c.date" class="hm-cell" :class="hmLevel(c.minutes)" :title="c.date + '：' + c.minutes + ' 分钟'"></span>
      </div>
      <div v-else class="empty">暂无数据（使用系统期间每分钟自动记录）</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api, unreadCount } from '../api'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

defineOptions({ name: 'DashboardView' })

const router = useRouter()
const study = ref(null)
const recommend = ref([])
const seriesList = ref([])
const activeId = ref(null)
const practiceCount = ref(0)
const favCount = ref(0)
const unread = ref(0)
const chartRef = ref(null)
let chart = null

const PALETTE = ['#c98f4e', '#4a6cf7', '#3aa675', '#c05a4d', '#8b6fc0', '#4bacc6', '#d0a35c']

const fmtHours = (m) => {
  if (!m) return '0h'
  return m >= 60 ? Math.round(m / 6) / 10 + 'h' : m + 'min'
}
const rcIcon = (t) => ({ startup: '🚀', review_kp: '⏰', practice_kp: '📝', document: '📄' }[t] || '💡')
const hmLevel = (m) => (m >= 120 ? 'hm-4' : m >= 60 ? 'hm-3' : m >= 30 ? 'hm-2' : m >= 10 ? 'hm-1' : 'hm-0')

const go = (path) => router.push(path)

const renderChart = () => {
  if (!chartRef.value || !seriesList.value.length) return
  if (chart && chart.getDom() !== chartRef.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 20, bottom: 24 },
    xAxis: {
      type: 'time',
      axisLabel: {
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

onMounted(async () => {
  window.addEventListener('resize', onResize)
  // 并行拉取四类数据（课程取第一门作上下文）
  const courses = await api('/api/courses')
  const courseId = courses.length ? courses[0].id : null

  api('/api/study/summary').then((s) => { study.value = s }).catch(() => {})
  unreadCount().then((c) => { unread.value = c.count || 0 }).catch(() => {})
  api('/api/practice/history?size=1').then((h) => { practiceCount.value = h.total || 0 }).catch(() => {})
  api('/api/favorites/count').then((c) => { favCount.value = c.total || 0 }).catch(() => {})
  if (courseId) {
    api('/api/recommend?courseId=' + courseId).then((r) => { recommend.value = r }).catch(() => {})
    // 每门课程一条成绩曲线（无数据的课程不画）
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
.rc-icon { font-size: 18px; line-height: 1.4; }
.rc-title { font-weight: 700; font-size: 13px; }
.rc-reason { font-size: 12px; color: var(--muted); margin-top: 2px; line-height: 1.6; }
.heatmap { display: grid; grid-template-rows: repeat(7, 12px); grid-auto-flow: column; gap: 3px; width: fit-content; }
.hm-cell { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.hm-0 { background: #ece9e2; }
.hm-1 { background: #ecd9be; }
.hm-2 { background: #dcbc8a; }
.hm-3 { background: #c08c4e; }
.hm-4 { background: #8c6844; }
</style>
