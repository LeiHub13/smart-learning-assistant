<template>
  <div>
    <div class="page-title">学习计划</div>
    <div class="page-sub">AI 根据你的目标与掌握度生成逐日计划，每日打卡跟踪进度</div>

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

onMounted(load)
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
</style>
