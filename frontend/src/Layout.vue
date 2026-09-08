<template>
  <aside class="sidebar">
    <div class="logo"><i></i>智学助手</div>
    <nav>
      <button v-for="m in menus" :key="m.key" :class="{ on: isActive(m) }" @click="switchTo(m.path)">
        {{ m.title }}
      </button>
    </nav>
  </aside>

  <div class="right">
    <header class="topbar">
      <div class="tb-title">{{ pageTitle }}</div>
      <div class="tb-right">
        <div class="bell" @click.stop="toggleBell">
          🔔
          <span v-if="unread" class="badge">{{ unread > 99 ? '99+' : unread }}</span>
          <div v-if="bellOpen" class="notify-pop" @click.stop>
            <div class="notify-head">
              <b>通知</b>
              <a @click="readAll">全部已读</a>
            </div>
            <div v-if="!notifies.length" class="notify-empty">暂无通知</div>
            <div v-for="n in notifies" :key="n.id" :class="['notify-item', { unread: !n.readFlag }]" @click="readOne(n)">
              <div class="notify-title">{{ n.title }}</div>
              <div class="notify-content">{{ n.content }}</div>
            </div>
          </div>
        </div>
        <div class="tb-user">
          <span class="avatar">{{ avatarChar }}</span>
          <div class="tb-user-info">
            <div class="tb-name">{{ me?.nickname || '…' }}</div>
            <div class="tb-role">用户</div>
          </div>
        </div>
        <button class="btn ghost small" @click="logout">退出</button>
      </div>
    </header>

    <main class="main" ref="mainRef">
      <router-view v-slot="{ Component }">
        <KeepAlive :include="keepAliveViews">
          <component :is="Component" />
        </KeepAlive>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, getToken, clearToken, resetApiCache, listNotifications, unreadCount, markRead, markAllRead } from './api'

const route = useRoute()
const router = useRouter()
const mainRef = ref(null)
const me = ref(null)
const unread = ref(0)
const notifies = ref([])
const bellOpen = ref(false)
let notifyTimer = null

const keepAliveViews = ['ChatView', 'GenerateView', 'PracticeView', 'ProgressView', 'ManageView', 'PlanView', 'ReportView']

const menus = [
  { key: 'chat', title: '智能答疑', path: '/chat' },
  { key: 'generate', title: '讲义/练习题 生成', path: '/generate' },
  { key: 'practice', title: '题库练习', path: '/practice' },
  { key: 'progress', title: '学情分析', path: '/progress' },
  { key: 'manage', title: '课程与知识库', path: '/manage' },
  { key: 'plan', title: '学习计划', path: '/plans' },
  { key: 'report', title: '学习报告', path: '/reports' }
]

const pageTitle = computed(() => {
  const m = menus.find((x) => route.path.startsWith(x.path))
  return m ? m.title : ''
})

const avatarChar = computed(() => (me.value?.nickname || '?').trim().slice(0, 1).toUpperCase())

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

const loadNotify = async () => {
  try {
    const c = await unreadCount()
    unread.value = c.count || 0
    const list = await listNotifications()
    notifies.value = list.slice(0, 8)
  } catch (e) { /* ignore */ }
}

const toggleBell = async () => {
  bellOpen.value = !bellOpen.value
  if (bellOpen.value) await loadNotify()
}

const readOne = async (n) => {
  if (!n.readFlag) {
    await markRead(n.id)
    n.readFlag = true
    unread.value = Math.max(0, unread.value - 1)
  }
}

const readAll = async () => {
  await markAllRead()
  notifies.value.forEach(n => n.readFlag = true)
  unread.value = 0
}

const closeBell = () => { bellOpen.value = false }

onMounted(async () => {
  document.addEventListener('click', closeBell)
  if (!getToken()) {
    router.replace('/login')
    return
  }
  try {
    me.value = await api('/api/auth/me')
    await loadNotify()
    notifyTimer = setInterval(loadNotify, 30000)
  } catch (e) {
    clearToken()
    resetApiCache()
    router.replace('/login')
  }
})

onUnmounted(() => {
  document.removeEventListener('click', closeBell)
  if (notifyTimer) clearInterval(notifyTimer)
})

watch(
  () => route.path,
  () => {
    if (mainRef.value) mainRef.value.scrollTo(0, 0)
  }
)
</script>

<style scoped>
.bell { cursor: pointer; font-size: 18px; padding: 6px; line-height: 1; }
.bell .badge { position: absolute; top: -4px; right: -8px; background: #ff4d4f; color: #fff; font-size: 10px; border-radius: 8px; padding: 0 5px; line-height: 14px; }
.notify-pop {
  position: absolute; top: 42px; right: -10px; width: 300px; max-height: 380px; overflow: auto;
  background: #fff; border-radius: 8px; box-shadow: 0 8px 24px rgba(0,0,0,.12);
  padding: 10px 0; z-index: 100;
}
.notify-head { display: flex; justify-content: space-between; align-items: center; padding: 0 12px 8px; border-bottom: 1px solid #eee; }
.notify-head a { color: #8c6844; font-size: 12px; cursor: pointer; }
.notify-empty { padding: 20px; text-align: center; color: #999; font-size: 13px; }
.notify-item { padding: 8px 12px; border-bottom: 1px solid #f5f5f5; cursor: pointer; }
.notify-item.unread { background: #f7f2ec; }
.notify-item:hover { background: #f5f5f5; }
.notify-title { font-weight: 600; font-size: 13px; }
.notify-content { font-size: 12px; color: #666; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
