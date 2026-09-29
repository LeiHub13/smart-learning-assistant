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
      <h3>发布公告</h3>
      <div class="ann-form">
        <input v-model="aTitle" type="text" maxlength="100" placeholder="公告标题（≤100 字）" @keyup.enter="publish" />
        <textarea v-model="aContent" rows="4" maxlength="5000" placeholder="公告内容，发布后将推送到全部用户的铃铛通知…"></textarea>
        <label class="ann-email">
          <input v-model="aEmail" type="checkbox" />
          <span>同时发送邮件通知</span>
          <span class="muted small">勾选后公告会同步发邮件给绑定邮箱的用户；邮件无法随撤回收回，不勾则仅进铃铛、撤回零残留</span>
        </label>
        <div class="row" style="justify-content:flex-end;gap:8px">
          <span v-if="aMsg" class="muted small">{{ aMsg }}</span>
          <button class="btn" :disabled="aPublishing || !aTitle.trim() || !aContent.trim()" @click="publish">
            {{ aPublishing ? '发布中…' : '发布公告' }}
          </button>
        </div>
      </div>
      <div v-if="aList.length" class="ann-history">
        <div class="muted small" style="margin:6px 0 4px">最近公告</div>
        <div v-for="a in aList" :key="a.id" class="ann-item">
          <div class="ann-head">
            <b>{{ a.title }}</b>
            <span class="row" style="gap:8px">
              <span v-if="a.emailSent" class="tag">已发邮件</span>
              <span class="muted small">{{ (a.createdAt || '').replace('T', ' ').slice(0, 16) }} · {{ a.adminName || '管理员' }}</span>
              <button class="btn danger small" :disabled="aRecalling === a.id" @click="recall(a)">撤回</button>
            </span>
          </div>
          <div class="ann-body">{{ a.content }}</div>
        </div>
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
      <div class="row" style="justify-content:space-between;margin-bottom:10px">
        <h3 style="margin:0">用户列表（{{ uTotal }}）</h3>
        <div class="row" style="gap:8px">
          <input v-model="uKeyword" type="text" placeholder="搜索用户名 / 昵称 / 邮箱…" style="max-width:260px" @keyup.enter="loadUsers(1)" />
          <button class="btn ghost small" @click="loadUsers(1)">搜索</button>
        </div>
      </div>
      <table v-if="uRecords.length">
        <thead><tr><th>用户名</th><th>昵称</th><th>邮箱</th><th>练习次数</th><th>最近学习</th><th>注册时间</th><th>角色</th></tr></thead>
        <tbody>
          <tr v-for="u in uRecords" :key="u.id">
            <td>
              <div class="u-cell">
                <img v-if="u.avatar" :src="u.avatar" class="u-av" alt="" />
                <span v-else class="u-av">{{ (u.nickname || u.username || '?').slice(0, 1).toUpperCase() }}</span>
                <span>{{ u.username }}</span>
                <span v-if="u.admin" class="tag">管理员</span>
              </div>
            </td>
            <td>{{ u.nickname || '—' }}</td>
            <td>{{ u.email || '—' }}</td>
            <td>{{ u.practiceCount }}</td>
            <td>{{ u.lastStudy || '—' }}</td>
            <td>{{ (u.createdAt || '').replace('T', ' ').slice(0, 16) }}</td>
            <td>{{ u.admin ? '管理员' : '普通用户' }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">无匹配用户</div>
      <div class="u-pager" v-if="uTotal > uSize">
        <button class="btn ghost small" :disabled="uPage <= 1" @click="loadUsers(uPage - 1)">上一页</button>
        <span class="muted small">第 {{ uPage }} 页 · 共 {{ Math.ceil(uTotal / uSize) }} 页</span>
        <button class="btn ghost small" :disabled="uPage >= Math.ceil(uTotal / uSize)" @click="loadUsers(uPage + 1)">下一页</button>
      </div>
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
import { chartTheme, onThemeChange } from '../theme'

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
  doc_overview: '文档速览', followup: '追问推荐', unknown: '未知场景'
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
  const th = chartTheme()
  const tip = { backgroundColor: th.tipBg, borderColor: th.tipLine, textStyle: { color: th.tipText } }
  const axis = (name) => ({ type: 'value', name, minInterval: 1, nameTextStyle: { color: th.text }, axisLabel: { color: th.text }, splitLine: { lineStyle: { color: th.split } } })
  const dates = d.value.series.map((s) => s.date.slice(5))
  // 用户注册与活跃
  if (uaRef.value) {
    if (!uaChart) uaChart = echarts.init(uaRef.value)
    uaChart.setOption({
      tooltip: { trigger: 'axis', ...tip },
      legend: { data: ['注册用户', '活跃用户', '学习分钟'], top: 0, textStyle: { color: th.text } },
      grid: { left: 40, right: 20, top: 36, bottom: 28 },
      xAxis: { type: 'category', data: dates, axisLabel: { color: th.text }, axisLine: { lineStyle: { color: th.split } } },
      yAxis: [axis('人数'), { type: 'value', name: '分钟', show: false }],
      series: [
        { name: '注册用户', type: 'bar', data: d.value.series.map((s) => s.registrations), itemStyle: { color: '#a5b4fc', borderRadius: [3, 3, 0, 0] } },
        { name: '活跃用户', type: 'line', smooth: true, data: d.value.series.map((s) => s.dau), itemStyle: { color: th.accent } },
        { name: '学习分钟', type: 'line', smooth: true, yAxisIndex: 1, data: d.value.series.map((s) => s.minutes), itemStyle: { color: th.success }, lineStyle: { type: 'dashed' } }
      ]
    })
  }
  // LLM 调用与 tokens
  if (llmRef.value) {
    if (!llmChart) llmChart = echarts.init(llmRef.value)
    llmChart.setOption({
      tooltip: { trigger: 'axis', ...tip },
      legend: { data: ['LLM 调用', 'tokens'], top: 0, textStyle: { color: th.text } },
      grid: { left: 56, right: 56, top: 36, bottom: 28 },
      xAxis: { type: 'category', data: dates, axisLabel: { color: th.text }, axisLine: { lineStyle: { color: th.split } } },
      yAxis: [axis('调用'), { type: 'value', name: 'tokens', show: false }],
      series: [
        { name: 'LLM 调用', type: 'bar', data: d.value.series.map((s) => s.llmCalls), itemStyle: { color: th.accent, borderRadius: [3, 3, 0, 0] } },
        { name: 'tokens', type: 'line', smooth: true, yAxisIndex: 1, data: d.value.series.map((s) => s.llmTokens), itemStyle: { color: '#d97706' } }
      ]
    })
  }
}

const offTheme = onThemeChange(() => render())

onMounted(async () => {
  try {
    d.value = await api('/api/admin/dashboard')
  } catch (e) {
    error.value = e.message
    return
  }
  await nextTick()
  render()
  loadUsers(1)
  loadAnnouncements()
})

/* ===== 公告发布 ===== */
const aTitle = ref('')
const aContent = ref('')
const aEmail = ref(true)
const aPublishing = ref(false)
const aMsg = ref('')
const aList = ref([])

const loadAnnouncements = async () => {
  try {
    aList.value = await api('/api/announcements') || []
  } catch (e) { /* 公告历史拉取失败不阻塞看板 */ }
}

const publish = async () => {
  aPublishing.value = true
  aMsg.value = ''
  try {
    await api('/api/announcements', {
      method: 'POST',
      body: { title: aTitle.value, content: aContent.value, notifyEmail: aEmail.value }
    })
    aMsg.value = aEmail.value ? '已发布并推送给全部用户（含邮件）' : '已发布，仅站内通知（撤回零残留）'
    aTitle.value = ''
    aContent.value = ''
    await loadAnnouncements()
  } catch (e) {
    aMsg.value = e.message
  } finally {
    aPublishing.value = false
  }
}

/* 撤回：删除公告存档，同时清理已扇出到各用户铃铛的通知 */
const aRecalling = ref(null)
const recall = async (a) => {
  if (!confirm('撤回公告「' + a.title + '」？所有用户铃铛里的这条公告也会一并消失。')) return
  aRecalling.value = a.id
  aMsg.value = ''
  try {
    const r = await api('/api/announcements/' + a.id, { method: 'DELETE' })
    aMsg.value = '已撤回，清理了 ' + (r.recalled || 0) + ' 条用户通知'
    await loadAnnouncements()
  } catch (e) {
    aMsg.value = e.message
  } finally {
    aRecalling.value = null
  }
}

/* ===== 用户列表 ===== */
const uRecords = ref([])
const uTotal = ref(0)
const uPage = ref(1)
const uSize = 10
const uKeyword = ref('')

const loadUsers = async (page = 1) => {
  try {
    const q = new URLSearchParams({ page: String(page), size: String(uSize) })
    if (uKeyword.value.trim()) q.set('keyword', uKeyword.value.trim())
    const r = await api('/api/admin/users?' + q)
    uRecords.value = r.records || []
    uTotal.value = r.total || 0
    uPage.value = r.page || page
  } catch (e) {
    error.value = e.message
  }
}

const onResize = () => { uaChart?.resize(); llmChart?.resize() }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  offTheme()
  uaChart?.dispose(); llmChart?.dispose()
})
</script>

<style scoped>
.stat-row { display: flex; gap: 10px; flex-wrap: wrap; }
.stat-row .stat { flex: 1; min-width: 130px; }
.chart { width: 100%; height: 300px; }
.u-pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 10px; }
.u-cell { display: flex; align-items: center; gap: 6px; }
.u-av { width: 26px; height: 26px; border-radius: 50%; object-fit: cover; flex: none; display: inline-flex; align-items: center; justify-content: center; background: var(--accent-subtle); color: var(--primary); font-size: 12px; font-weight: 600; }
.ann-form { display: flex; flex-direction: column; gap: 8px; }
.ann-form input, .ann-form textarea { width: 100%; font: inherit; padding: 8px 10px; resize: vertical; }
.ann-email { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 13px; }
.ann-email input { width: auto; }
.ann-history { margin-top: 12px; border-top: 1px solid var(--border); padding-top: 8px; }
.ann-item { padding: 8px 0; border-bottom: 1px dashed var(--border); }
.ann-item:last-child { border-bottom: none; }
.ann-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.ann-body { font-size: 13px; color: var(--muted); margin-top: 3px; white-space: pre-wrap; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
</style>
