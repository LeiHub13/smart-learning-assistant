<template>
  <div>
    <div class="page-title">课程 Hub</div>
    <div class="page-sub">发现并加入其他人公开的课程，加入后会出现在「我的课程」</div>

    <div v-if="error" class="err">{{ error }}</div>
    <div v-if="loading" class="loading"><i></i>加载中…</div>

    <div v-else-if="!list.length" class="card">
      <div class="empty">
        还没有课程入驻 Hub<br />
        <span class="muted">在「我的课程」创建课程，或把已创建的课程加入 Hub</span>
      </div>
    </div>

    <div v-else class="grid2">
      <CourseCard v-for="c in list" :key="c.id" :course="c" :clickable="false">
        <template #actions>
          <button v-if="!c.enrolled" class="btn small" :disabled="busyId === c.id" @click="join(c)">
            {{ busyId === c.id ? '加入中…' : '加入课程' }}
          </button>
          <template v-else>
            <button class="btn ghost small" @click="goPractice(c)">去练习</button>
            <button v-if="c.role !== 'creator'" class="btn ghost small" :disabled="busyId === c.id" @click="leave(c)">
              {{ busyId === c.id ? '退出中…' : '退出课程' }}
            </button>
          </template>
        </template>
      </CourseCard>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import CourseCard from '../components/CourseCard.vue'

defineOptions({ name: 'CourseHubView' })

const router = useRouter()
const list = ref([])
const loading = ref(true)
const error = ref('')
const busyId = ref('')

const goPractice = (c) => router.push('/practice?courseId=' + c.id)

const load = async () => {
  loading.value = true
  try {
    list.value = await api('/api/courses/hub')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const join = async (c) => {
  busyId.value = c.id
  error.value = ''
  try {
    await api('/api/courses/' + c.id + '/enroll', { method: 'POST', body: {} })
    c.enrolled = true
    c.memberCount = (c.memberCount || 0) + 1
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

const leave = async (c) => {
  busyId.value = c.id
  error.value = ''
  try {
    await api('/api/courses/' + c.id + '/enroll', { method: 'DELETE' })
    c.enrolled = false
    c.memberCount = Math.max((c.memberCount || 1) - 1, 0)
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
/* 卡片与两列网格（.grid2）均复用「我的课程」同一套样式 */
.muted { color: var(--muted); font-size: 12px; }
</style>
