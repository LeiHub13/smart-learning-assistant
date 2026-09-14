<template>
  <div>
    <div class="page-title">收藏夹</div>
    <div class="page-sub">收藏的好题集中回顾，支持一键重练</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <div class="row" style="justify-content:space-between">
        <div>
          <span class="label">课程</span>
          <select v-model="courseId">
            <option :value="null">全部课程</option>
            <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div class="btns">
          <button class="btn" :disabled="!favList.length" @click="goRetrain">
            重练收藏题（{{ favList.length }}）
          </button>
        </div>
      </div>
    </div>

    <div class="card">
      <div v-for="q in favList" :key="q.questionId" class="fi">
        <div class="fi-head">
          <span class="tag">{{ q.type }}</span>
          <span v-if="q.kpName" class="tag">{{ q.kpName }}</span>
          <span v-if="q.difficulty" class="tag">{{ q.difficulty }}</span>
          <span v-if="!courseId" class="tag">{{ q.courseName }}</span>
          <button class="btn ghost small" style="margin-left:auto" :disabled="removing === q.questionId" @click="remove(q)">
            {{ removing === q.questionId ? '移除中…' : '☆ 取消收藏' }}
          </button>
        </div>
        <div class="fi-stem">{{ q.stem }}</div>
        <div v-if="q.options" class="fi-opts">
          <span v-for="(o, j) in parseOptions(q.options)" :key="j">{{ o.k }}. {{ o.v }}</span>
        </div>
        <details class="fi-ans">
          <summary>查看答案与解析</summary>
          <div class="ans"><b>参考答案：</b>{{ q.answer || '—' }}</div>
          <div v-if="q.analysis" class="ans"><b>解析：</b>{{ q.analysis }}</div>
        </details>
      </div>
      <div v-if="!favList.length && !error" class="empty">
        {{ courseId ? '该课程暂无收藏，在练习/考试报告页可一键收藏' : '暂无收藏，在练习/考试报告页可一键收藏好题' }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, getCourses } from '../api'
import { parseOptions } from '../utils'

defineOptions({ name: 'FavoritesView' })

const router = useRouter()
const courses = ref([])
const courseId = ref(null)
const favList = ref([])
const removing = ref(null)
const error = ref('')

onMounted(async () => {
  courses.value = await getCourses()
  await load()
})

const load = async () => {
  error.value = ''
  try {
    favList.value = await api('/api/favorites' + (courseId.value ? '?courseId=' + courseId.value : ''))
  } catch (e) {
    error.value = e.message
  }
}

watch(courseId, load)

const remove = async (q) => {
  removing.value = q.questionId
  error.value = ''
  try {
    await api('/api/favorites/toggle', { method: 'POST', body: { questionId: q.questionId } })
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    removing.value = null
  }
}

const goRetrain = () => {
  const q = courseId.value ? '?favorite=1&courseId=' + courseId.value : '?favorite=1'
  router.push('/practice' + q)
}
</script>

<style scoped>
.fi { padding: 12px 0; border-bottom: 1px solid #eee7da; }
.fi:last-of-type { border-bottom: none; }
.fi-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
.fi-stem { font-weight: 600; margin-bottom: 4px; }
.fi-opts { display: flex; flex-direction: column; gap: 2px; color: #475569; font-size: 14px; margin-bottom: 4px; }
.fi-ans summary { cursor: pointer; color: var(--muted, #64748b); font-size: 13px; }
</style>
