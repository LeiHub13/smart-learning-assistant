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

    <div v-else class="hub-grid">
      <div v-for="c in list" :key="c.id" class="card hub-card">
        <h3>{{ c.name }}
          <span v-if="c.role === 'creator'" class="tag ok">我创建的</span>
        </h3>
        <div class="hub-desc">{{ c.description || '暂无简介' }}</div>
        <div class="hub-meta">
          <span>创建者：{{ c.ownerName }}</span>
          <span>{{ c.memberCount }} 人加入</span>
          <span>题库 {{ c.questionCount }} 题</span>
        </div>
        <div class="hub-ops">
          <button v-if="!c.enrolled" class="btn small" :disabled="busyId === c.id" @click="join(c)">
            {{ busyId === c.id ? '加入中…' : '加入课程' }}
          </button>
          <span v-else class="tag ok">已加入</span>
          <button class="btn ghost small" @click="go('/practice')">去练习</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

defineOptions({ name: 'CourseHubView' })

const router = useRouter()
const list = ref([])
const loading = ref(true)
const error = ref('')
const busyId = ref('')

const go = (path) => router.push(path)

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

onMounted(load)
</script>

<style scoped>
.hub-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.hub-card { margin-bottom: 0; display: flex; flex-direction: column; }
.hub-card h3 { margin-bottom: 8px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.hub-desc { color: var(--muted); font-size: 13px; line-height: 1.7; min-height: 42px; }
.hub-meta {
  display: flex; flex-wrap: wrap; gap: 10px; margin-top: 12px;
  font-size: 12px; color: var(--muted);
}
.hub-ops { display: flex; align-items: center; gap: 8px; margin-top: 14px; }
.muted { color: var(--muted); font-size: 12px; }
@media (max-width: 768px) { .hub-grid { grid-template-columns: 1fr; } }
</style>
