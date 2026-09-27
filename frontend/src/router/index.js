import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../api'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true, title: '登录 - 智学助手' }
  },
  {
    path: '/welcome',
    name: 'welcome',
    component: () => import('../views/WelcomeView.vue'),
    meta: { public: true, title: '欢迎使用 - 智学助手' }
  },
  {
    path: '/',
    component: () => import('../Layout.vue'),
    // 未登录先看系统介绍页，已登录直接进首页
    redirect: () => (getToken() ? { path: '/home' } : { path: '/welcome' }),
    children: [
      { path: 'home', name: 'home', component: () => import('../views/DashboardView.vue'), meta: { title: '首页 - 智学助手' } },
      { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: '智能答疑 - 智学助手' } },
      { path: 'hall', name: 'hall', component: () => import('../views/HallView.vue'), meta: { title: '对话厅 - 智学助手' } },
      { path: 'generate', name: 'generate', component: () => import('../views/GenerateView.vue'), meta: { title: 'AI 内容生成 - 智学助手' } },
      { path: 'practice', name: 'practice', component: () => import('../views/PracticeView.vue'), meta: { title: '题库练习 - 智学助手' } },
      { path: 'mistakes', name: 'mistakes', component: () => import('../views/MistakeBookView.vue'), meta: { title: '错题本 - 智学助手' } },
      { path: 'favorites', name: 'favorites', component: () => import('../views/FavoritesView.vue'), meta: { title: '收藏夹 - 智学助手' } },
      { path: 'exam', name: 'exam', component: () => import('../views/ExamView.vue'), meta: { title: '在线考试 - 智学助手' } },
      { path: 'bank', name: 'bank', component: () => import('../views/QuestionBankView.vue'), meta: { title: '题库管理 - 智学助手' } },
      { path: 'progress', name: 'progress', component: () => import('../views/ProgressView.vue'), meta: { title: '学情分析 - 智学助手' } },
      { path: 'manage', name: 'manage', component: () => import('../views/ManageView.vue'), meta: { title: '我的课程 - 智学助手' } },
      { path: 'hub', name: 'hub', component: () => import('../views/CourseHubView.vue'), meta: { title: '课程 Hub - 智学助手' } },
      { path: 'docs', name: 'docs', component: () => import('../views/DocsView.vue'), meta: { title: '文档管理 - 智学助手' } },
      { path: 'plans', name: 'plans', component: () => import('../views/PlanView.vue'), meta: { title: '学习计划 - 智学助手' } },
      { path: 'reports', name: 'reports', component: () => import('../views/ReportView.vue'), meta: { title: '学习报告 - 智学助手' } },
      { path: 'notes', name: 'notes', component: () => import('../views/NotesView.vue'), meta: { title: '学习笔记 - 智学助手' } },
      { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '个人中心 - 智学助手' } },
      { path: 'admin', name: 'admin', component: () => import('../views/AdminView.vue'), meta: { title: '系统看板 - 智学助手' } },
      { path: 'flashcards', name: 'flashcards', component: () => import('../views/FlashcardsView.vue'), meta: { title: '闪卡 - 智学助手' } },
      { path: 'search', name: 'search', component: () => import('../views/SearchView.vue'), meta: { title: '搜索 - 智学助手' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// admin 账号（纯管理角色）：仅允许系统看板，其余路由一律重定向回看板。
// 用户名经 /api/auth/me 拉取一次并按 token 缓存，避免每次导航都请求。
let adminCache = { token: null, username: null }

const cachedUsername = async () => {
  const token = getToken()
  if (!token) return null
  if (adminCache.token !== token) {
    try {
      const r = await fetch('/api/auth/me', { headers: { Authorization: 'Bearer ' + token } })
      const body = await r.json().catch(() => null)
      adminCache = { token, username: body && body.data ? body.data.username : null }
    } catch (e) {
      adminCache = { token, username: null }
    }
  }
  return adminCache.username
}

router.beforeEach(async (to) => {
  const logged = !!getToken()
  if (!to.meta.public && !logged) {
    return { path: '/login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }
  if (to.path === '/login' && logged) {
    return { path: '/chat' }
  }
  // admin 是纯管理角色：登录后只能进入系统看板
  const username = await cachedUsername()
  if (username === 'admin' && to.path !== '/admin') {
    return { path: '/admin' }
  }
  document.title = to.meta.title || '智学助手'
})

export default router