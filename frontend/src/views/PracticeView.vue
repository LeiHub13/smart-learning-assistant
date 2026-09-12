<template>
  <div>
    <div class="page-title">题库练习</div>
    <div class="page-sub">客观题自动判分，主观题 AI 按参考答案打分并点评</div>

    <div v-if="error" class="err">{{ error }}</div>

    <template v-if="!paper.length && !report">
      <div class="card">
        <div class="row">
          <div>
            <span class="label">课程</span>
            <select v-model="courseId">
              <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div>
            <span class="label">题目数量</span>
            <input v-model="count" type="number" min="1" max="10" />
          </div>
          <div class="btns">
            <button class="btn" :disabled="loading" @click="start()">开始练习</button>
          </div>
        </div>
        <div style="margin-top:14px">
          <span class="label">最近练习</span>
          <table v-if="history.length">
            <thead><tr><th>时间</th><th>得分</th><th></th></tr></thead>
            <tbody>
              <tr v-for="p in history" :key="p.id">
                <td>{{ fmtTime(p.createdAt) }}</td>
                <td><b>{{ p.score }}</b> / {{ p.totalScore }}</td>
                <td><a class="link" @click="viewReport(p.id)">查看报告</a></td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">暂无练习记录</div>
          <div v-if="historyTotal" class="pager">
            <button class="btn ghost small" :disabled="historyPage <= 1" @click="loadHistory(historyPage - 1)">上一页</button>
            <span class="muted small">第 {{ historyPage }} / {{ historyPages }} 页 · 共 {{ historyTotal }} 条</span>
            <button class="btn ghost small" :disabled="historyPage >= historyPages" @click="loadHistory(historyPage + 1)">下一页</button>
          </div>
        </div>
        <div style="margin-top:14px" class="fav-bar">
          <span class="label">收藏夹（好题反复练）</span>
          <button class="btn small" :disabled="favCount === 0" @click="startFav">
            {{ favCount ? '重练收藏题（' + favCount + '）' : '本课程暂无收藏' }}
          </button>
          <span v-if="favTotal > favCount" class="muted small">全部 {{ favTotal }} 道，其余在别的课程</span>
        </div>
        <div style="margin-top:14px">
          <span class="label">成绩曲线（正确率 %，最近 50 次）</span>
          <div ref="chartRef" class="trend-chart"></div>
          <div v-if="!trend.length" class="empty">暂无曲线数据，完成一次练习后生成</div>
        </div>
      </div>
    </template>

    <template v-else-if="paper.length">
      <div class="card">
        <div class="row" style="justify-content:space-between">
          <h3 style="margin:0">{{ courseName }} · 本次 {{ paper.length }} 题 <span class="tag">作答后提交</span></h3>
          <button class="btn ghost small" @click="confirmExit = true">退出练习</button>
        </div>
        <div v-for="(q, i) in paper" :key="q.id" class="q">
          <div class="head">
            <span class="type">{{ i + 1 }}. 【{{ q.type }}】{{ q.stem }}</span>
            <span class="kp">{{ q.kpName }}</span>
          </div>
          <label v-for="o in parseOptions(q.options)" :key="o.k" class="opt" :class="{ on: isSel(q, o.k) }">
            <input :type="q.type === '多选' ? 'checkbox' : 'radio'"
                   :name="'q' + q.id" :value="o.k"
                   :checked="isSel(q, o.k)"
                   @change="toggle(q, o.k, q.type)" />
            {{ o.k }}. {{ o.v }}
          </label>
          <textarea v-if="q.type === '问答'" v-model="answers[q.id]" placeholder="请输入你的答案…" style="margin-top:8px"></textarea>
        </div>
        <button class="btn" :disabled="submitting" @click="submit">
          {{ submitting ? '批改中…' : '提交并 AI 批改' }}
        </button>
      </div>
    </template>

    <template v-else>
      <div class="card">
        <div class="row">
          <div class="stat" style="flex:1"><div class="num">{{ report.score }} / {{ report.totalScore }}</div><div class="lab">总分</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ okCount }} / {{ report.items.length }}</div><div class="lab">答对</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ rate }}%</div><div class="lab">正确率</div></div>
        </div>
        <div style="text-align:right"><button class="btn ghost small" @click="again">再做一组</button></div>
      </div>

      <div v-for="(it, i) in report.items" :key="it.pq.id" class="q" :class="it.pq.correct ? 'pass' : 'fail'">
        <div class="head">
          <span class="type">{{ i + 1 }}. 【{{ it.q.type }}】{{ it.q.stem }}</span>
          <span class="row" style="gap:10px">
            <a class="link" @click="toggleFav(it.q)" style="cursor:pointer">
              {{ it.q.favorited ? '★ 已收藏' : '☆ 收藏' }}
            </a>
            <span class="tag" :class="it.pq.correct ? 'ok' : 'bad'">{{ it.pq.correct ? '正确 +' + it.pq.score : '错误' }}</span>
          </span>
        </div>
        <div class="ans"><b>你的答案：</b>{{ it.pq.userAnswer || '（未作答）' }}<br />
          <b>参考答案：</b>{{ it.q.answer }}<br /><b>解析：</b>{{ it.q.analysis }}</div>
        <div v-if="it.pq.review" class="ans ai" style="margin-top:6px"><b>AI 点评：</b>{{ it.pq.review }}</div>
      </div>
    </template>

    <div v-if="confirmExit" class="modal-mask" @click.self="confirmExit = false">
      <div class="modal-box">
        <h3>退出练习</h3>
        <p>退出后本次作答不会保存，也不生成练习记录。确定退出吗？</p>
        <div class="modal-ops">
          <button class="btn ghost small" @click="confirmExit = false">继续作答</button>
          <button class="btn danger small" @click="doExit">确定退出</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { api, getCourses } from '../api'
import { fmtTime, parseOptions } from '../utils'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

defineOptions({ name: 'PracticeView' })

const courses = ref([])
const courseId = ref(null)
const count = ref(5)
const paper = ref([])
const report = ref(null)
const history = ref([])
const historyPage = ref(1)
const historyTotal = ref(0)
const PAGE_SIZE = 5
const trend = ref([])
const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const answers = ref({})
const chartRef = ref(null)
const favCount = ref(0)
const favTotal = ref(0)
let chart = null

const courseName = computed(() => {
  const c = courses.value.find((x) => x.id === courseId.value)
  return c ? c.name : ''
})
const okCount = computed(() => (report.value ? report.value.items.filter((i) => i.pq.correct).length : 0))
const historyPages = computed(() => Math.max(1, Math.ceil(historyTotal.value / PAGE_SIZE)))
const rate = computed(() => {
  if (!report.value || !report.value.items.length) return 0
  return Math.round((okCount.value / report.value.items.length) * 100)
})

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) courseId.value = courses.value[0].id
  await loadHistory(1)
  await refreshTrend()
  refreshFavCount()
})

const loadHistory = async (page = 1) => {
  const d = await api('/api/practice/history?page=' + page + '&size=' + PAGE_SIZE)
  history.value = d.records || []
  historyTotal.value = d.total || 0
  historyPage.value = page
}

const refreshFavCount = async () => {
  if (!courseId.value) return
  try {
    const c = await api('/api/favorites/count?courseId=' + courseId.value)
    favCount.value = c.count || 0
    favTotal.value = c.total || 0
  } catch (e) { /* 忽略 */ }
}

const refreshTrend = async () => {
  if (!courseId.value) return
  try {
    trend.value = await api('/api/practice/trend?courseId=' + courseId.value)
  } catch (e) { /* 曲线失败不影响主流程 */ trend.value = [] }
  nextTick(renderChart)
}

const renderChart = () => {
  if (!chartRef.value || !trend.value.length) return
  // 切换到报告页会销毁图表 DOM，返回时需重新绑定
  if (chart && chart.getDom() !== chartRef.value) {
    chart.dispose()
    chart = null
  }
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (ps) => {
        const t = trend.value[ps[0].dataIndex]
        return `${t.date}<br/>${t.title}<br/>得分：${t.score} / ${t.totalScore}<br/>正确率：${t.rate}%`
      }
    },
    grid: { left: 44, right: 24, top: 24, bottom: 28 },
    xAxis: { type: 'category', data: trend.value.map((t) => t.date) },
    yAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
    series: [{
      name: '正确率',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 7,
      data: trend.value.map((t) => t.rate),
      lineStyle: { width: 2.5 },
      areaStyle: { opacity: 0.12 },
      itemStyle: { color: '#1a1a1a' }
    }]
  })
}

watch(courseId, () => {
  refreshTrend()
  refreshFavCount()
})

onBeforeUnmount(() => {
  if (chart) { chart.dispose(); chart = null }
})

const isSel = (q, k) => {
  const v = answers.value[q.id] || ''
  if (q.type === '多选') return v.split('').includes(k)
  return v === k
}

const toggle = (q, k, type) => {
  if (type === '多选') {
    let v = (answers.value[q.id] || '').split('').filter((c) => c !== k).join('')
    if (!v.includes(k)) v = (v + k).split('').sort().join('')
    answers.value[q.id] = v
  } else {
    answers.value[q.id] = k
  }
}

const start = async (favMode = false) => {
  loading.value = true
  error.value = ''
  answers.value = {}
  try {
    const url = '/api/practice/paper?courseId=' + courseId.value + '&count=' + count.value + '&favorite=' + favMode
    paper.value = await api(url)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const startFav = () => start(true)

const confirmExit = ref(false)

const doExit = () => {
  confirmExit.value = false
  paper.value = []
  answers.value = {}
  error.value = ''
}

const toggleFav = async (q) => {
  try {
    const r = await api('/api/favorites/toggle', { method: 'POST', body: { questionId: q.id } })
    q.favorited = r.favorited
    refreshFavCount()
  } catch (e) { /* 静默失败 */ }
}

const submit = async () => {
  submitting.value = true
  error.value = ''
  try {
    const items = paper.value.map((q) => ({ questionId: q.id, answer: answers.value[q.id] || '' }))
    const p = await api('/api/practice/submit', { method: 'POST', body: { courseId: courseId.value, items } })
    paper.value = []
    loadHistory(1)
    refreshTrend()
    refreshFavCount()
    report.value = await api('/api/practice/' + p.id)
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

const viewReport = async (id) => {
  error.value = ''
  try {
    report.value = await api('/api/practice/' + id)
  } catch (e) {
    error.value = e.message
  }
}

const again = () => {
  report.value = null
  paper.value = []
  answers.value = {}
  refreshTrend()
  refreshFavCount()
}
</script>

<style scoped>
.trend-chart { width: 100%; height: 260px; margin-top: 6px; }
.pager { margin-top: 8px; display: flex; align-items: center; gap: 10px; }
</style>