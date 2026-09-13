<template>
  <div>
    <div class="page-title">错题本</div>
    <div class="page-sub">自动归集练习与考试中答错的题，重练答对后自动出本</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <div class="row" style="justify-content:space-between">
        <div style="display:flex;gap:16px;align-items:flex-end">
          <div>
            <span class="label">课程</span>
            <select v-model="courseId">
              <option :value="null">全部课程</option>
              <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
        </div>
        <div class="btns">
          <button class="btn" :disabled="!total" @click="goRetrain">
            重练错题（{{ total }}）
          </button>
        </div>
      </div>
    </div>

    <div class="card">
      <div v-for="m in records" :key="m.question.id" class="mi">
        <div class="mi-head">
          <span class="tag">{{ m.question.type }}</span>
          <span v-if="m.question.kpName" class="tag">{{ m.question.kpName }}</span>
          <span v-if="m.question.difficulty" class="tag">{{ m.question.difficulty }}</span>
          <span class="muted small">最近答错 {{ fmtTime(m.lastWrongAt) }} · 累计错 {{ m.wrongCount }} 次</span>
        </div>
        <div class="mi-stem">{{ m.question.stem }}</div>
        <div v-if="m.question.options" class="mi-opts">
          <span v-for="(o, j) in parseOptions(m.question.options)" :key="j">{{ o.k }}. {{ o.v }}</span>
        </div>
        <details class="mi-ans">
          <summary>查看答案与解析</summary>
          <div class="ans"><b>参考答案：</b>{{ m.question.answer || '—' }}</div>
          <div v-if="m.question.analysis" class="ans"><b>解析：</b>{{ m.question.analysis }}</div>
        </details>
      </div>
      <div v-if="!records.length && !error" class="empty">
        {{ courseId ? '该课程暂无错题，保持下去！' : '暂无错题，完成一次练习后自动归集' }}
      </div>
      <div v-if="total" class="pager">
        <button class="btn ghost small" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
        <span class="muted small">第 {{ page }} / {{ pages }} 页 · 共 {{ total }} 题</span>
        <button class="btn ghost small" :disabled="page >= pages" @click="load(page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, getCourses } from '../api'
import { fmtTime, parseOptions } from '../utils'

defineOptions({ name: 'MistakeBookView' })

const PAGE_SIZE = 10
const router = useRouter()

const courses = ref([])
const courseId = ref(null)
const records = ref([])
const page = ref(1)
const total = ref(0)
const error = ref('')

const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

onMounted(async () => {
  courses.value = await getCourses()
  load(1)
})

const load = async (p = 1) => {
  error.value = ''
  try {
    const q = new URLSearchParams({ page: p, size: PAGE_SIZE })
    if (courseId.value) q.set('courseId', courseId.value)
    const d = await api('/api/mistakes?' + q)
    records.value = d.records || []
    total.value = d.total || 0
    page.value = p
  } catch (e) {
    error.value = e.message
  }
}

watch(courseId, () => load(1))

const goRetrain = () => {
  const q = courseId.value ? '?mistake=1&courseId=' + courseId.value : '?mistake=1'
  router.push('/practice' + q)
}
</script>

<style scoped>
.mi { padding: 12px 0; border-bottom: 1px solid #eee7da; }
.mi:last-of-type { border-bottom: none; }
.mi-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
.mi-stem { font-weight: 600; margin-bottom: 4px; }
.mi-opts { display: flex; flex-direction: column; gap: 2px; color: #475569; font-size: 14px; margin-bottom: 4px; }
.mi-ans summary { cursor: pointer; color: var(--muted, #64748b); font-size: 13px; }
.pager { margin-top: 8px; display: flex; align-items: center; gap: 10px; }
</style>
