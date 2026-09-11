<template>
  <aside class="sidebar">
    <div class="logo"><i></i>智学助手</div>
    <nav>
      <button v-for="m in menus" :key="m.key" :class="{ on: isActive(m) }" @click="switchTo(m.path)">
        <span class="m-ico">{{ m.icon }}</span>
        <span class="m-txt">{{ m.title }}</span>
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
            <div class="tab-bar">
              <span :class="{ on: tab === 'system' }" @click="tab = 'system'">系统通知</span>
              <span :class="{ on: tab === 'mail' }" @click="switchMail">邮箱通知</span>
            </div>
            <template v-if="tab === 'system'">
              <div class="notify-head">
                <b>通知</b>
                <a @click="readAll">全部已读</a>
              </div>
              <div v-if="!notifies.length" class="notify-empty">暂无通知</div>
              <div v-for="n in notifies" :key="n.id" :class="['notify-item', { unread: !n.readFlag }]" @click="readOne(n)">
                <div class="notify-title">{{ n.title }}</div>
                <div class="notify-content">{{ n.content }}</div>
              </div>
            </template>
            <template v-else>
              <div class="notify-head"><b>邮件发送记录</b></div>
              <div v-if="!mailLogs.length" class="notify-empty">
                {{ mailEnabled ? '暂无邮件记录' : '邮件通知未启用（个人中心绑定邮箱，服务器 .env 开启 MAIL_ENABLED）' }}
              </div>
              <div v-for="m in mailLogs" :key="m.id" class="notify-item">
                <div class="notify-title">
                  {{ m.subject }}
                  <span class="mail-tag" :class="m.status === 'SENT' ? 'ok' : 'bad'">{{ m.status === 'SENT' ? '成功' : '失败' }}</span>
                </div>
                <div class="notify-content">{{ m.content }}</div>
                <div class="notify-content muted">{{ m.email }} · {{ fmtShort(m.createdAt) }}</div>
              </div>
            </template>
          </div>
        </div>
        <div class="tb-user" title="进入个人中心" @click="switchTo('/profile')">
          <img v-if="me?.avatar" :src="me.avatar" class="avatar" alt="头像" />
          <span v-else class="avatar">{{ avatarChar }}</span>
          <div class="tb-user-info">
            <div class="tb-name">{{ me?.nickname || '…' }}</div>
            <div class="tb-role">{{ me?.email || '未绑定邮箱' }}</div>
          </div>
        </div>
        <button class="btn ghost small" @click="logout">退出</button>
      </div>
    </header>

    <main class="main" ref="mainRef">
      <router-view v-slot="{ Component }">
        <Transition name="page-fade" mode="out-in">
          <KeepAlive :include="keepAliveViews">
            <component :is="Component" />
          </KeepAlive>
        </Transition>
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
const tab = ref('system')
const mailLogs = ref([])
let notifyTimer = null

const keepAliveViews = ['ChatView', 'GenerateView', 'PracticeView', 'ExamView', 'ProgressView', 'ManageView', 'PlanView', 'ReportView', 'NotesView', 'ProfileView']

const menus = [
  { key: 'chat', title: '智能答疑', path: '/chat', icon: '💬' },
  { key: 'generate', title: '讲义/练习题 生成', path: '/generate', icon: '✨' },
  { key: 'practice', title: '题库练习', path: '/practice', icon: '📝' },
  { key: 'exam', title: '在线考试', path: '/exam', icon: '📋' },
  { key: 'progress', title: '学情分析', path: '/progress', icon: '📈' },
  { key: 'manage', title: '课程与知识库', path: '/manage', icon: '📚' },
  { key: 'plan', title: '学习计划', path: '/plans', icon: '🗓️' },
  { key: 'report', title: '学习报告', path: '/reports', icon: '📄' },
  { key: 'notes', title: '学习笔记', path: '/notes', icon: '📒' },
  { key: 'profile', title: '个人中心', path: '/profile', icon: '👤' }
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

// 学习时长心跳：页面可见时每 60s 上报 1 分钟（后端上限 5 分钟/次防刷）
let heartbeatTimer = null
const startHeartbeat = () => {
  heartbeatTimer = setInterval(() => {
    if (document.visibilityState !== 'visible') return
    api('/api/study/heartbeat', { method: 'POST', body: { minutes: 1 } }).catch(() => { /* 静默失败 */ })
  }, 60_000)
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
  if (bellOpen.value) {
    await loadNotify()
    loadMailLogs()
  }
}

const switchMail = () => {
  tab.value = 'mail'
  loadMailLogs()
}

const loadMailLogs = async () => {
  try {
    mailLogs.value = await api('/api/notifications/mails')
  } catch (e) { mailLogs.value = [] }
}

const fmtShort = (t) => {
  if (!t) return ''
  const d = new Date(t)
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
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
    startHeartbeat()
  } catch (e) {
    clearToken()
    resetApiCache()
    router.replace('/login')
  }
})

onUnmounted(() => {
  document.removeEventListener('click', closeBell)
  if (notifyTimer) clearInterval(notifyTimer)
  if (heartbeatTimer) clearInterval(heartbeatTimer)
})

watch(
  () => route.path,
  () => {
    if (mainRef.value) mainRef.value.scrollTo(0, 0)
  }
)
</script>

<style scoped>
img.avatar { object-fit: cover; padding: 0; }
.tab-bar { display: flex; border-bottom: 1px solid #f0ece6; }
.tab-bar span { flex: 1; text-align: center; padding: 10px 0 9px; font-size: 13px; font-weight: 700; color: #999; cursor: pointer; }
.tab-bar span.on { color: #1a1a1a; box-shadow: inset 0 -2px 0 #c98f4e; }
.mail-tag { font-size: 10px; border-radius: 6px; padding: 1px 6px; margin-left: 6px; vertical-align: 1px; }
.mail-tag.ok { background: #eef7ee; color: #2e7d32; }
.mail-tag.bad { background: #fdeeee; color: #c62828; }
.muted { color: #aaa; }
.tb-user { cursor: pointer; border-radius: 10px; padding: 4px 6px; transition: background .2s ease; }
.tb-user:hover { background: rgba(26,26,26,.05); }
.bell {
  position: relative; cursor: pointer; font-size: 17px; width: 34px; height: 34px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 10px; transition: background .2s ease;
}
.bell:hover { background: rgba(26,26,26,.05); }
.bell .badge { position: absolute; top: 1px; right: 0; background: #ff4d4f; color: #fff; font-size: 10px; border-radius: 8px; padding: 0 5px; line-height: 14px; box-shadow: 0 0 0 2px #fff; }
.notify-pop {
  position: absolute; top: 42px; right: -10px; width: 320px; max-height: 400px; overflow: auto;
  background: #fff; border-radius: 14px; box-shadow: 0 1px 2px rgba(0,0,0,.04), 0 12px 32px rgba(0,0,0,.14);
  padding: 6px 0; z-index: 100;
  animation: pop-in .16s ease;
}
@keyframes pop-in { from { opacity: 0; transform: translateY(-6px) scale(.98); } }
.notify-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px 9px; border-bottom: 1px solid #f0ece6; }
.notify-head b { font-size: 14px; letter-spacing: -.01em; }
.notify-head a { color: #8c6844; font-size: 12px; cursor: pointer; }
.notify-head a:hover { text-decoration: underline; }
.notify-empty { padding: 24px; text-align: center; color: #999; font-size: 13px; }
.notify-item { padding: 9px 14px; border-bottom: 1px solid #f7f4ef; cursor: pointer; transition: background .15s ease; }
.notify-item.unread { background: #faf6ef; }
.notify-item.unread .notify-title::before { content: ''; display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: #c98f4e; margin-right: 6px; vertical-align: 1px; }
.notify-item:hover { background: #f5f2ec; }
.notify-title { font-weight: 600; font-size: 13px; }
.notify-content { font-size: 12px; color: #666; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
