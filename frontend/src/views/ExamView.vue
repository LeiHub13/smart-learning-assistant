<template>
  <div>
    <div class="page-title">在线考试</div>
    <div class="page-sub">组卷 → 限时作答 → 客观题自动判分 / 主观题 AI 批改</div>

    <div v-if="error" class="err">{{ error }}</div>

    <!-- ===== 列表/创建视图 ===== -->
    <template v-if="!paper && !report">
      <div class="card">
        <div class="row" style="gap:12px;align-items:center">
          <select v-model="courseId">
            <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
          <button class="btn ghost small" @click="toggleCreate">{{ createOpen ? '收起' : '创建考试' }}</button>
          <button class="btn ghost small" @click="loadExams">刷新</button>
        </div>

        <div v-if="createOpen" class="create-box">
          <div class="row" style="gap:12px">
            <div><span class="label">考试标题</span><input v-model="form.title" placeholder="如：期中测验" /></div>
            <div><span class="label">时长(分钟，留空不限)</span><input v-model="form.durationMin" type="number" min="5" max="180" /></div>
          </div>
          <div class="row" style="gap:12px;margin-top:10px">
            <div>
              <span class="label">组卷方式</span>
              <select v-model="form.mode">
                <option value="auto">课程内随机抽题</option>
                <option value="manual">手动选题</option>
              </select>
            </div>
            <div v-if="form.mode === 'auto'"><span class="label">题目数量</span><input v-model="form.count" type="number" min="1" max="20" /></div>
            <button class="btn" :disabled="creating" @click="create">{{ creating ? '创建中…' : '确认创建' }}</button>
          </div>
          <div v-if="form.mode === 'manual' && bank.length" class="bank">
            <div v-for="q in bank" :key="q.id" class="bank-item" :class="{ picked: pickedIds.includes(q.id) }" @click="togglePick(q)">
              【{{ q.type }}】{{ q.stem }}
              <span class="muted small">{{ q.kpName }} · {{ q.difficulty || '未标注' }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <h3>考试列表</h3>
        <div v-if="!exams.length" class="empty">暂无考试，点击「创建考试」开一场</div>
        <table v-if="exams.length">
          <thead><tr><th>考试</th><th>课程</th><th>时长</th><th>总分</th><th>我的成绩</th><th></th></tr></thead>
          <tbody>
            <tr v-for="e in exams" :key="e.id">
              <td><b>{{ e.title }}</b></td>
              <td>{{ e.courseName }}</td>
              <td>{{ e.durationMin ? e.durationMin + ' 分钟' : '不限时' }}</td>
              <td>{{ e.totalScore }}</td>
              <td>{{ e.attemptCount ? '最好 ' + e.myBestScore + ' / ' + e.totalScore + '（' + e.attemptCount + ' 次）' : '未参加' }}</td>
              <td><button class="btn small" @click="enter(e)">进入考试</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ===== 作答视图 ===== -->
    <template v-else-if="paper">
      <div class="card">
        <div class="row" style="justify-content:space-between">
          <h3 style="margin:0">{{ examMeta.title }} · {{ paper.length }} 题 · 共 {{ examMeta.totalScore }} 分</h3>
          <div class="timer" :class="{ danger: remainSec < 120 }">
            {{ remainText }}
          </div>
        </div>
        <div v-for="(q, i) in paper" :key="q.id" class="q">
          <div class="head">
            <span class="type">{{ i + 1 }}. 【{{ q.type }}】{{ q.stem }}</span>
            <span class="kp">{{ q.kpName }}（{{ q.score }} 分）</span>
          </div>
          <label v-for="o in parseOptions(q.options)" :key="o.k" class="opt" :class="{ on: isSel(q, o.k) }">
            <input :type="q.type === '多选' ? 'checkbox' : 'radio'"
                   :name="'e' + q.id" :value="o.k"
                   :checked="isSel(q, o.k)"
                   @change="toggle(q, o.k, q.type)" />
            {{ o.k }}. {{ o.v }}
          </label>
          <textarea v-if="q.type === '问答'" v-model="answers[q.id]" placeholder="请输入你的答案…" style="margin-top:8px"></textarea>
        </div>
        <button class="btn" :disabled="submitting" @click="submit">
          {{ submitting ? '批改中…' : '交卷' }}
        </button>
      </div>
    </template>

    <!-- ===== 成绩单视图 ===== -->
    <template v-else>
      <div class="card">
        <div class="row">
          <div class="stat" style="flex:1"><div class="num">{{ report.record.score }} / {{ report.record.totalScore }}</div><div class="lab">总分</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ okCount }} / {{ report.items.length }}</div><div class="lab">答对</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ rate }}%</div><div class="lab">正确率</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ useTime }}</div><div class="lab">用时</div></div>
        </div>
        <div style="text-align:right"><button class="btn ghost small" @click="back">返回考试列表</button></div>
      </div>

      <div v-for="(it, i) in report.items" :key="i" class="q" :class="it.answer.correct ? 'pass' : 'fail'">
        <div class="head">
          <span class="type">{{ i + 1 }}. 【{{ it.question.type }}】{{ it.question.stem }}</span>
          <span class="tag" :class="it.answer.correct ? 'ok' : 'bad'">{{ it.answer.correct ? '正确 +' + it.answer.score : '错误' }}</span>
        </div>
        <div class="ans"><b>你的答案：</b>{{ it.answer.userAnswer || '（未作答）' }}<br />
          <b>参考答案：</b>{{ it.question.answer }}<br /><b>解析：</b>{{ it.question.analysis }}</div>
        <div v-if="it.answer.review" class="ans ai" style="margin-top:6px"><b>AI 点评：</b>{{ it.answer.review }}</div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api, getCourses } from '../api'
import { parseOptions } from '../utils'

defineOptions({ name: 'ExamView' })

const courses = ref([])
const courseId = ref(null)
const exams = ref([])
const error = ref('')

// 创建
const createOpen = ref(false)
const creating = ref(false)
const form = ref({ title: '', durationMin: '', mode: 'auto', count: 5 })
const bank = ref([])
const pickedIds = ref([])

// 作答
const paper = ref(null)
const examMeta = ref({})
const record = ref(null)
const answers = ref({})
const submitting = ref(false)
let deadline = null
let timer = null
const nowTick = ref(Date.now())

// 成绩单
const report = ref(null)

const remainSec = computed(() => {
  if (!deadline) return Infinity
  return Math.max(0, Math.floor((deadline - nowTick.value) / 1000))
})
const remainText = computed(() => {
  if (!isFinite(remainSec.value)) return '不限时'
  const m = Math.floor(remainSec.value / 60)
  const s = remainSec.value % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})
const okCount = computed(() => (report.value ? report.value.items.filter((i) => i.answer.correct).length : 0))
const rate = computed(() => (report.value && report.value.items.length
  ? Math.round((okCount.value / report.value.items.length) * 100) : 0))
const useTime = computed(() => {
  if (!report.value) return ''
  const sec = Math.round((new Date(report.value.record.submittedAt) - new Date(report.value.record.startedAt)) / 1000)
  return Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒'
})

const loadExams = async () => {
  exams.value = await api('/api/exams' + (courseId.value ? '?courseId=' + courseId.value : ''))
}

const toggleCreate = async () => {
  createOpen.value = !createOpen.value
  if (createOpen.value && courseId.value) {
    bank.value = await api('/api/exams/bank?courseId=' + courseId.value)
  }
}

const togglePick = (q) => {
  const i = pickedIds.value.indexOf(q.id)
  if (i >= 0) pickedIds.value.splice(i, 1)
  else pickedIds.value.push(q.id)
}

const create = async () => {
  creating.value = true
  error.value = ''
  try {
    const base = { courseId: courseId.value, title: form.value.title, durationMin: form.value.durationMin || null }
    if (form.value.mode === 'auto') {
      await api('/api/exams/auto', { method: 'POST', body: { ...base, count: Number(form.value.count) || 5 } })
    } else {
      if (!pickedIds.value.length) throw new Error('请至少勾选一道题目')
      await api('/api/exams', { method: 'POST', body: { ...base, questionIds: pickedIds.value } })
    }
    createOpen.value = false
    form.value = { title: '', durationMin: '', mode: 'auto', count: 5 }
    pickedIds.value = []
    await loadExams()
  } catch (e) {
    error.value = e.message
  } finally {
    creating.value = false
  }
}

const enter = async (e) => {
  error.value = ''
  try {
    const detail = await api('/api/exams/' + e.id)
    const r = await api('/api/exams/' + e.id + '/start', { method: 'POST' })
    examMeta.value = detail.exam
    paper.value = detail.questions
    record.value = r
    answers.value = {}
    deadline = r.deadlineAt ? new Date(r.deadlineAt.replace(' ', 'T')).getTime() : null
    timer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  } catch (err) {
    error.value = err.message
  }
}

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

const submit = async () => {
  submitting.value = true
  error.value = ''
  try {
    const items = paper.value.map((q) => ({ questionId: q.id, answer: answers.value[q.id] || '' }))
    await api('/api/exams/records/' + record.value.id + '/submit', { method: 'POST', body: { items } })
    clearInterval(timer)
    report.value = await api('/api/exams/records/' + record.value.id + '/report')
    paper.value = null
    await loadExams()
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

const back = () => {
  report.value = null
  paper.value = null
}

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) courseId.value = courses.value[0].id
  await loadExams()
})
</script>

<style scoped>
.timer { font-size: 20px; font-weight: 800; letter-spacing: .04em; }
.timer.danger { color: #ff4d4f; }
.create-box { margin-top: 14px; padding: 14px; border: 1px solid var(--border); border-radius: 10px; }
.bank { max-height: 240px; overflow: auto; margin-top: 10px; border-top: 1px solid var(--border); padding-top: 8px; }
.bank > div { padding: 8px 6px; border-bottom: 1px solid var(--border); cursor: pointer; }
.bank > div.picked { background: #f3ede2; }
</style>
