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
        <div ref="chartRef" class="d-chart"></div>
        <div v-if="!trend.length" class="empty">暂无练习数据</div>
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
const trend = ref([])
const practiceCount = ref(0)
const favCount = ref(0)
const unread = ref(0)
const chartRef = ref(null)
let chart = null

const fmtHours = (m) => {
  if (!m) return '0h'
  return m >= 60 ? Math.round(m / 6) / 10 + 'h' : m + 'min'
}
const rcIcon = (t) => ({ startup: '🚀', review_kp: '⏰', practice_kp: '📝', document: '📄' }[t] || '💡')

const go = (path) => router.push(path)

const renderChart = () => {
  if (!chartRef.value || !trend.value.length) return
  if (chart && chart.getDom() !== chartRef.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 20, bottom: 24 },
    xAxis: { type: 'category', data: trend.value.map((t) => t.date) },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    series: [{
      name: '正确率', type: 'line', smooth: true, symbolSize: 6,
      data: trend.value.map((t) => t.rate),
      lineStyle: { width: 2.5 }, areaStyle: { opacity: 0.12 },
      itemStyle: { color: '#c98f4e' }
    }]
  })
}

onMounted(async () => {
  // 并行拉取四类数据（课程取第一门作上下文）
  const courses = await api('/api/courses')
  const courseId = courses.length ? courses[0].id : null

  api('/api/study/summary').then((s) => { study.value = s }).catch(() => {})
  unreadCount().then((c) => { unread.value = c.count || 0 }).catch(() => {})
  api('/api/practice/history?size=1').then((h) => { practiceCount.value = h.total || 0 }).catch(() => {})
  if (courseId) {
    api('/api/recommend?courseId=' + courseId).then((r) => { recommend.value = r }).catch(() => {})
    api('/api/practice/trend?courseId=' + courseId).then((t) => {
      trend.value = t
      nextTick(renderChart)
    }).catch(() => {})
    api('/api/favorites/count?courseId=' + courseId).then((c) => { favCount.value = c.count || 0 }).catch(() => {})
  }
})

onBeforeUnmount(() => {
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
.grid2 { display: grid; grid-template-columns: 3fr 2fr; gap: 16px; align-items: start; }
@media (max-width: 900px) { .grid2 { grid-template-columns: 1fr; } }
.d-chart { width: 100%; height: 240px; }
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
