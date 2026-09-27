<template>
  <div>
    <div class="page-title">系统看板</div>
    <div class="page-sub">用户规模、活跃度与 LLM 用量趋势（仅管理员可见）</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <div class="stat-row">
        <div class="stat"><div class="num">{{ pick('users.total') }}</div><div class="lab">用户总数</div></div>
        <div class="stat"><div class="num">{{ pick('users.newToday') }}</div><div class="lab">今日注册</div></div>
        <div class="stat"><div class="num">{{ pick('users.new7d') }}</div><div class="lab">近 7 天注册</div></div>
        <div class="stat"><div class="num">{{ pick('dauToday') }}</div><div class="lab">今日活跃</div></div>
        <div class="stat"><div class="num">{{ pick('llmTodayCalls') }}</div><div class="lab">今日 LLM 调用</div></div>
        <div class="stat"><div class="num">{{ fmtTokens(pick('llmTodayTokens')) }}</div><div class="lab">今日 tokens</div></div>
      </div>
    </div>

    <div class="card">
      <h3>近 14 天 · 用户注册与活跃</h3>
      <div ref="uaRef" class="chart"></div>
    </div>

    <div class="card">
      <h3>近 14 天 · LLM 调用与 token 消耗</h3>
      <div ref="llmRef" class="chart"></div>
      <div v-if="d && d.llmAvailable === false" class="muted small">ai-service 观测不可达，LLM 数据暂缺</div>
    </div>

    <div class="card">
      <h3>按场景统计（近 14 天）</h3>
      <table v-if="d && d.byScene && d.byScene.length">
        <thead><tr><th>场景</th><th>调用</th><th>失败</th><th>平均耗时</th><th>tokens</th></tr></thead>
        <tbody>
          <tr v-for="s in d.byScene" :key="s.scene">
            <td>{{ sceneLabel(s.scene) }}</td>
            <td>{{ s.calls }}</td>
            <td>{{ s.failures }}</td>
            <td>{{ s.avgMs }} ms</td>
            <td>{{ fmtTokens(s.tokens) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">暂无数据</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api } from '../api'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

defineOptions({ name: 'AdminView' })

const d = ref(null)
const error = ref('')
const uaRef = ref(null)
const llmRef = ref(null)
let uaChart = null
let llmChart = null

const SCENE_LABELS = {
  rag_qa: '知识库答疑', free: '自由对话', agent: '智能答疑', lecture: '讲义生成',
  questions: 'AI 出题', review: '主观题批改', advice: '复习建议', rewrite: '查询改写',
  rerank: '相关性重排', plan: '学习计划', report: '学习报告', recommend: '今日推荐',
  doc_overview: '文档速览', unknown: '未知场景'
}
const sceneLabel = (s) => SCENE_LABELS[s] || s

const pick = (path) => {
  let v = d.value
  for (const k of path.split('.')) {
    if (v == null) return '—'
    v = v[k]
  }
  return v == null ? '—' : v
}

const fmtTokens = (t) => {
  if (t == null) return '—'
  const n = Number(t)
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

const render = () => {
  if (!d.value?.series?.length) return
  const dates = d.value.series.map((s) => s.date.slice(5))
  // 用户注册与活跃
  if (uaRef.value) {
    if (!uaChart) uaChart = echarts.init(uaRef.value)
    uaChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['注册用户', '活跃用户', '学习分钟'], top: 0 },
      grid: { left: 40, right: 20, top: 36, bottom: 28 },
      xAxis: { type: 'category', data: dates },
      yAxis: [
        { type: 'value', name: '人数', minInterval: 1 },
        { type: 'value', name: '分钟', show: false }
      ],
      series: [
        { name: '注册用户', type: 'bar', data: d.value.series.map((s) => s.registrations), itemStyle: { color: '#a5b4fc', borderRadius: [3, 3, 0, 0] } },
        { name: '活跃用户', type: 'line', smooth: true, data: d.value.series.map((s) => s.dau), itemStyle: { color: '#0969da' } },
        { name: '学习分钟', type: 'line', smooth: true, yAxisIndex: 1, data: d.value.series.map((s) => s.minutes), itemStyle: { color: '#1a7f37' }, lineStyle: { type: 'dashed' } }
      ]
    })
  }
  // LLM 调用与 tokens
  if (llmRef.value) {
    if (!llmChart) llmChart = echarts.init(llmRef.value)
    llmChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['LLM 调用', 'tokens'], top: 0 },
      grid: { left: 56, right: 56, top: 36, bottom: 28 },
      xAxis: { type: 'category', data: dates },
      yAxis: [
        { type: 'value', name: '调用', minInterval: 1 },
        { type: 'value', name: 'tokens', show: false }
      ],
      series: [
        { name: 'LLM 调用', type: 'bar', data: d.value.series.map((s) => s.llmCalls), itemStyle: { color: '#0969da', borderRadius: [3, 3, 0, 0] } },
        { name: 'tokens', type: 'line', smooth: true, yAxisIndex: 1, data: d.value.series.map((s) => s.llmTokens), itemStyle: { color: '#d97706' } }
      ]
    })
  }
}

onMounted(async () => {
  try {
    d.value = await api('/api/admin/dashboard')
  } catch (e) {
    error.value = e.message
    return
  }
  await nextTick()
  render()
})

const onResize = () => { uaChart?.resize(); llmChart?.resize() }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  uaChart?.dispose(); llmChart?.dispose()
})
</script>

<style scoped>
.stat-row { display: flex; gap: 10px; flex-wrap: wrap; }
.stat-row .stat { flex: 1; min-width: 130px; }
.chart { width: 100%; height: 300px; }
</style>
