<template>
  <div>
    <div class="page-title">学情分析</div>
    <div class="page-sub">基于练习记录分析知识点掌握度，AI 生成复习建议</div>

    <div class="card">
      <div class="row">
        <div>
          <span class="label">课程</span>
          <select v-model="courseId" @change="load">
            <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div class="btns" style="justify-content:flex-end"><button class="btn ghost small" @click="load">刷新</button></div>
      </div>
    </div>

    <div v-if="!summary" class="loading"><i></i>正在分析学情…</div>

    <template v-else>
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

      <div class="card">
        <h3>总体指标</h3>
        <div class="row">
          <div class="stat" style="flex:1"><div class="num">{{ Math.round(summary.averageMastery) }}%</div><div class="lab">平均掌握度</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ summary.totalAttempts }}</div><div class="lab">累计答题</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ summary.wrongBook.length }}</div><div class="lab">错题数</div></div>
        </div>
      </div>

      <div class="card">
        <h3>学习时长</h3>
        <div class="row">
          <div class="stat" style="flex:1"><div class="num">{{ fmtHours(study?.totalMinutes) }}</div><div class="lab">总时长</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ study?.activeDays ?? 0 }}</div><div class="lab">活跃天数</div></div>
        </div>
        <div v-if="study?.calendar?.length" class="heatmap">
          <span v-for="c in study.calendar" :key="c.date" class="hm-cell" :class="hmLevel(c.minutes)" :title="c.date + '：' + c.minutes + ' 分钟'"></span>
        </div>
        <div class="hm-legend">近 12 周 ·
          <span class="hm-cell hm-0"></span>无
          <span class="hm-cell hm-1"></span>&lt;30m
          <span class="hm-cell hm-2"></span>&lt;1h
          <span class="hm-cell hm-3"></span>&lt;2h
          <span class="hm-cell hm-4"></span>≥2h
        </div>
        <table v-if="study?.byCourse?.length">
          <thead><tr><th>课程</th><th>累计时长</th></tr></thead>
          <tbody>
            <tr v-for="c in study.byCourse" :key="c.courseId">
              <td>{{ c.courseName }}</td>
              <td>{{ fmtHours(c.minutes) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">暂无课程时长数据（页面停留期间每分钟自动记录）</div>
      </div>

      <div class="card">
        <div class="row" style="justify-content:space-between">
          <h3 style="margin:0">AI 复习建议
            <span v-if="summary.adviceCached" class="tag" title="读取缓存，点击重新生成可更新">缓存 · 7天内有效</span>
          </h3>
          <button class="btn ghost small" :disabled="refreshingAdvice" @click="refreshAdvice">
            {{ refreshingAdvice ? 'AI 生成中…' : '重新生成' }}
          </button>
        </div>
        <div class="md" v-html="mdToHtml(summary.advice)"></div>
      </div>

      <div class="card">
        <h3>知识点掌握度</h3>
        <div v-if="!summary.masteries.length" class="empty">暂无练习数据，去「题库练习」做一组题后生成</div>
        <div v-for="m in summary.masteries" :key="m.id" class="mb">
          <div class="nm">{{ m.kpName }}</div>
          <div class="tr"><div class="fl" :style="{ width: m.mastery + '%' }"></div></div>
          <div class="vl">{{ Math.round(m.mastery) }}%</div>
          <div class="ct">{{ m.correctCount }}/{{ m.attempts }} 对</div>
        </div>
      </div>

      <div class="card">
        <h3>错题本</h3>
        <div v-if="!summary.wrongBook.length" class="empty">太棒了，暂无错题</div>
        <div v-else class="wb-list">
          <div class="wb-card" v-for="(w, i) in summary.wrongBook" :key="i">
            <div class="wb-head">
              <div class="wb-no">#{{ i + 1 }}</div>
              <span class="tag bad">{{ w.kpName }}</span>
              <div class="wb-time">{{ fmtTime(w.wrongAt) }}</div>
            </div>
            <div class="wb-stem">{{ w.stem }}</div>
            <div class="wb-answers">
              <div class="wb-ans wrong">
                <div class="wb-ans-label"><i>✗</i>我的答案</div>
                <div class="wb-ans-text">{{ w.userAnswer || '未作答' }}</div>
              </div>
              <div class="wb-ans right">
                <div class="wb-ans-label"><i>✓</i>参考答案</div>
                <div class="wb-ans-text">{{ w.answer }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api, getCourses } from '../api'
import { fmtTime, mdToHtml } from '../utils'

defineOptions({ name: 'ProgressView' })

const courses = ref([])
const courseId = ref(null)
const summary = ref(null)
const study = ref(null)
const recommend = ref([])
const refreshingAdvice = ref(false)

const rcIcon = (t) => ({ startup: '🚀', review_kp: '⏰', practice_kp: '📝', document: '📄' }[t] || '💡')

const refreshAdvice = async () => {
  if (!courseId.value) return
  refreshingAdvice.value = true
  try {
    const r = await api('/api/progress/advice/refresh?courseId=' + courseId.value, { method: 'POST' })
    if (summary.value) {
      summary.value.advice = r.advice
      summary.value.adviceCached = false
    }
  } catch (e) { /* 保留旧建议 */ } finally {
    refreshingAdvice.value = false
  }
}

const fmtHours = (m) => {
  if (!m) return '0h'
  return m >= 60 ? Math.round(m / 6) / 10 + 'h' : m + 'min'
}

const hmLevel = (minutes) => {
  if (!minutes) return 'hm-0'
  if (minutes < 30) return 'hm-1'
  if (minutes < 60) return 'hm-2'
  if (minutes < 120) return 'hm-3'
  return 'hm-4'
}

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) {
    courseId.value = courses.value[0].id
    await load()
  }
  api('/api/study/summary').then((s) => { study.value = s }).catch(() => { /* 统计失败不阻塞 */ })
  if (courseId.value) {
    api('/api/recommend?courseId=' + courseId.value).then((r) => { recommend.value = r }).catch(() => { recommend.value = [] })
  }
})

const load = async () => {
  if (!courseId.value) return
  summary.value = null
  summary.value = await api('/api/progress/summary?courseId=' + courseId.value)
  api('/api/recommend?courseId=' + courseId.value).then((r) => { recommend.value = r }).catch(() => {})
}
</script>

<style scoped>
.rc-item { display: flex; gap: 10px; padding: 10px 0; border-bottom: 1px solid var(--border); }
.rc-item:last-child { border-bottom: none; }
.rc-icon { font-size: 20px; line-height: 1.4; }
.rc-title { font-weight: 700; font-size: 14px; }
.rc-reason { font-size: 13px; color: var(--muted); margin-top: 2px; line-height: 1.6; }
.md { line-height: 1.9; color: #334155; }
.md :deep(h2), .md :deep(h3), .md :deep(h4) { margin: 8px 0 4px; font-weight: 800; }
.md :deep(strong) { color: #1a1a1a; }
.md :deep(code) { background: #f4f1ea; border-radius: 4px; padding: 1px 5px; font-size: 13px; }
.heatmap { display: grid; grid-template-rows: repeat(7, 12px); grid-auto-flow: column; gap: 3px; margin: 12px 0 4px; width: fit-content; }
.hm-cell { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.hm-0 { background: #e8eaf2; }
.hm-1 { background: #c7d2fe; }
.hm-2 { background: #a5b4fc; }
.hm-3 { background: #818cf8; }
.hm-4 { background: #4f46e5; }
.hm-legend { font-size: 12px; color: var(--muted); display: flex; align-items: center; gap: 4px; margin-bottom: 10px; }

.wb-list { display: flex; flex-direction: column; gap: 16px; }
.wb-card {
  border: 1px solid #e3dfd8;
  border-radius: 14px;
  padding: 16px 18px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,0.03), 0 4px 12px rgba(0,0,0,0.04);
}
.wb-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.wb-no {
  font-size: 12px; font-weight: 800; color: #fff; background: linear-gradient(135deg, #e06c5a, #c94f4f);
  border-radius: 20px; padding: 2px 10px; font-family: "SF Mono", Consolas, monospace; letter-spacing: 0.04em;
}
.wb-time { margin-left: auto; font-size: 12px; color: #9a968d; }
.wb-stem {
  font-size: 14px; line-height: 1.75; color: #2c2c2c;
  background: #faf9f6; border: 1px solid #eeeae2; border-radius: 10px;
  padding: 12px 14px; margin-bottom: 14px;
}
.wb-answers { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.wb-ans { border-radius: 10px; padding: 12px 14px; }
.wb-ans.wrong { background: #fdf1f0; border: 1px solid #f0cecb; }
.wb-ans.right { background: #f1f8f1; border: 1px solid #cfe4d1; }
.wb-ans-label {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 700; margin-bottom: 6px;
}
.wb-ans.wrong .wb-ans-label { color: #c94f4f; }
.wb-ans.right .wb-ans-label { color: #3a8f52; }
.wb-ans-label i {
  width: 16px; height: 16px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 10px; font-style: normal; color: #fff;
}
.wb-ans.wrong .wb-ans-label i { background: #d96a5c; }
.wb-ans.right .wb-ans-label i { background: #4faf6a; }
.wb-ans-text { font-size: 13.5px; line-height: 1.7; color: #3a3a3a; word-break: break-word; }

@media (max-width: 480px) {
  .wb-answers { grid-template-columns: 1fr; }
}
</style>