<template>
  <div>
    <div class="page-title">学习计划</div>
    <div class="page-sub">AI 根据你的目标与掌握度生成逐日计划，每日打卡跟踪进度</div>

    <div class="card">
      <div class="row" style="justify-content:space-between">
        <h3 style="margin:0">打卡日历 · {{ calMonth }}</h3>
        <div class="row" style="gap:8px">
          <button class="btn ghost small" @click="shiftMonth(-1)">‹</button>
          <button class="btn ghost small" @click="shiftMonth(1)">›</button>
        </div>
      </div>
      <div class="cal-grid">
        <span class="cal-h" v-for="d in ['一','二','三','四','五','六','日']" :key="d">{{ d }}</span>
        <span v-for="blank in calFirstOffset" :key="'b' + blank" class="cal-cell blank"></span>
        <span v-for="d in calDays" :key="d.date" class="cal-cell"
              :class="calLevel(d)" :title="d.date + '：完成 ' + d.done + '/' + d.total">
          <b>{{ Number(d.date.slice(-2)) }}</b>
          <i v-if="d.total">{{ d.done }}/{{ d.total }}</i>
        </span>
      </div>
      <div class="muted small" style="margin-top:8px">绿=全部完成 · 橙=部分完成 · 灰=无任务</div>
    </div>

    <div class="card">
      <h3>创建新计划</h3>
      <div class="row" style="gap:12px;flex-wrap:wrap">
        <input v-model="form.goal" type="text" placeholder="输入学习目标，如：掌握 Java 集合框架" style="flex:1;min-width:220px" />
        <input v-model.number="form.days" type="number" min="1" max="30" placeholder="天数" style="width:80px" />
        <select v-model="form.courseId">
          <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <button class="btn" :disabled="loading" @click="createPlan">生成计划</button>
      </div>
    </div>

    <div v-if="plans.length" class="card">
      <h3>我的计划</h3>
      <div v-for="p in plans" :key="p.id" class="plan-item">
        <div class="plan-head" @click="toggle(p.id)">
          <div>
            <b>{{ p.goal }}</b>
            <span class="tag" :class="p.status">{{ p.status === 'done' ? '已完成' : '进行中' }}</span>
          </div>
          <div class="muted">{{ p.days }} 天</div>
        </div>
        <div v-if="open[p.id] && details[p.id]" class="plan-body">
          <div class="progress-bar">
            <div class="fill" :style="{ width: progress(p.id) + '%' }"></div>
          </div>
          <div class="tasks">
            <div v-for="t in details[p.id].tasks" :key="t.id" :class="['task', { done: t.done }]" @click="checkIn(t)">
              <span class="cb">{{ t.done ? '✅' : '⬜' }}</span>
              <div>
                <div><b>第 {{ t.dayNo }} 天</b> {{ t.title }}</div>
                <div class="muted small">{{ t.tasks }}</div>
                <div v-if="t.focusKp" class="tag small">重点：{{ t.focusKp }}</div>
              </div>
            </div>
          </div>
          <button class="btn ghost small" @click="del(p.id)">删除计划</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { getCourses, listPlans, createPlan as createPlanApi, getPlan, checkInTask, deletePlan } from '../api'

const courses = ref([])
const plans = ref([])

// ===== 打卡日历 =====
const calMonth = ref(new Date().toISOString().slice(0, 7))
const calDays = ref([])
const calFirstOffset = ref(0)

const calLevel = (d) => {
  if (!d.total) return 'c0'
  if (d.done >= d.total) return 'c2'
  if (d.done > 0) return 'c1'
  return 'c0'
}

const shiftMonth = (delta) => {
  const [y, m] = calMonth.value.split('-').map(Number)
  const dt = new Date(y, m - 1 + delta, 1)
  calMonth.value = dt.getFullYear() + '-' + String(dt.getMonth() + 1).padStart(2, '0')
  loadCalendar()
}

const loadCalendar = async () => {
  try {
    const days = await api('/api/plans/calendar?month=' + calMonth.value)
    calDays.value = days
    const first = calMonth.value + '-01'
    const dow = new Date(first).getDay() // 0=周日
    calFirstOffset.value = (dow + 6) % 7 // 周一开头
  } catch (e) { /* 忽略 */ }
}
const details = reactive({})
const open = reactive({})
const loading = ref(false)
const form = ref({ goal: '', days: 7, courseId: 1 })

const load = async () => {
  courses.value = await getCourses()
  plans.value = await listPlans()
  if (plans.value.length && !Object.keys(open).length) {
    open[plans.value[0].id] = true
    await loadDetail(plans.value[0].id)
  }
}

const loadDetail = async (id) => {
  details[id] = await getPlan(id)
}

const toggle = async (id) => {
  open[id] = !open[id]
  if (open[id] && !details[id]) await loadDetail(id)
}

const progress = (id) => {
  const d = details[id]
  if (!d || !d.tasks.length) return 0
  return Math.round(d.doneCount / d.tasks.length * 100)
}

const createPlan = async () => {
  loading.value = true
  try {
    await createPlanApi({ goal: form.value.goal, days: form.value.days, courseId: form.value.courseId })
    form.value.goal = ''
    await load()
  } finally {
    loading.value = false
  }
}

const checkIn = async (t) => {
  await checkInTask(t.id)
  await loadDetail(t.planId)
}

const del = async (id) => {
  await deletePlan(id)
  await load()
}

onMounted(() => {
  load()
  loadCalendar()
})
</script>

<style scoped>
.plan-item { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 12px; overflow: hidden; }
.plan-head { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; background: var(--soft); cursor: pointer; }
.plan-body { padding: 14px; }
.progress-bar { height: 7px; background: #eee8df; border-radius: 4px; margin-bottom: 12px; }
.progress-bar .fill { height: 100%; background: var(--accent); border-radius: 4px; transition: width .3s; }
.tasks { display: grid; gap: 10px; }
.task { display: flex; gap: 10px; padding: 12px; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; }
.task.done { opacity: .7; background: #f7f2ec; }
.cb { font-size: 18px; }
.small { font-size: 12px; }
.muted { color: #888; }
.cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; margin-top: 12px; }
.cal-h { text-align: center; font-size: 12px; color: var(--muted); }
.cal-cell {
  min-height: 56px; border: 1px solid var(--border); border-radius: 8px;
  padding: 4px 6px; display: flex; flex-direction: column; gap: 2px;
}
.cal-cell.blank { border: none; }
.cal-cell b { font-size: 12px; }
.cal-cell i { font-style: normal; font-size: 11px; }
.cal-cell.c0 { background: var(--soft); }
.cal-cell.c1 { background: #fdf3e4; border-color: #e8c98f; }
.cal-cell.c2 { background: #eef7ee; border-color: #b5d8b5; }
</style>
