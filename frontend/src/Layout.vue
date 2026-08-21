<template>
  <aside class="sidebar">
    <div class="logo"><i></i>智学助手</div>
    <nav>
      <button v-for="m in menus" :key="m.key" :class="{ on: isActive(m) }" @click="switchTo(m.path)">
        {{ m.title }}
      </button>
    </nav>
    <div class="foot">
      <div>
        <div>{{ me?.nickname || '…' }}</div>
        <div class="role">用户</div>
      </div>
      <button class="btn ghost small" @click="logout">退出</button>
    </div>
  </aside>
  <main class="main" ref="mainRef">
    <router-view v-slot="{ Component }">
      <KeepAlive :include="keepAliveViews">
        <component :is="Component" />
      </KeepAlive>
    </router-view>
  </main>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, getToken, clearToken, resetApiCache } from './api'

const route = useRoute()
const router = useRouter()
const mainRef = ref(null)
const me = ref(null)

const keepAliveViews = ['ChatView', 'GenerateView', 'PracticeView', 'ProgressView', 'ManageView']

const menus = [
  { key: 'chat', title: '智能答疑', path: '/chat' },
  { key: 'generate', title: 'AI 内容生成', path: '/generate' },
  { key: 'practice', title: '题库练习', path: '/practice' },
  { key: 'progress', title: '学情分析', path: '/progress' },
  { key: 'manage', title: '课程与知识库', path: '/manage' }
]

const isActive = (m) => route.path.startsWith(m.path)

const switchTo = (path) => {
  if (route.path !== path) router.push(path)
}

const logout = () => {
  clearToken()
  resetApiCache()
  me.value = null
  router.replace('/login')
}

onMounted(async () => {
  if (!getToken()) {
    router.replace('/login')
    return
  }
  try {
    me.value = await api('/api/auth/me')
  } catch (e) {
    clearToken()
    resetApiCache()
    router.replace('/login')
  }
})

watch(
  () => route.path,
  () => {
    if (mainRef.value) mainRef.value.scrollTo(0, 0)
  }
)
</script>