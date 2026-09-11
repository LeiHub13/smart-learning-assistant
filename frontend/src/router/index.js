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
    path: '/',
    component: () => import('../Layout.vue'),
    redirect: '/home',
    children: [
      { path: 'home', name: 'home', component: () => import('../views/DashboardView.vue'), meta: { title: '首页 - 智学助手' } },
      { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue'), meta: { title: '智能答疑 - 智学助手' } },
      { path: 'generate', name: 'generate', component: () => import('../views/GenerateView.vue'), meta: { title: 'AI 内容生成 - 智学助手' } },
      { path: 'practice', name: 'practice', component: () => import('../views/PracticeView.vue'), meta: { title: '题库练习 - 智学助手' } },
      { path: 'exam', name: 'exam', component: () => import('../views/ExamView.vue'), meta: { title: '在线考试 - 智学助手' } },
      { path: 'progress', name: 'progress', component: () => import('../views/ProgressView.vue'), meta: { title: '学情分析 - 智学助手' } },
      { path: 'manage', name: 'manage', component: () => import('../views/ManageView.vue'), meta: { title: '课程与知识库 - 智学助手' } },
      { path: 'plans', name: 'plans', component: () => import('../views/PlanView.vue'), meta: { title: '学习计划 - 智学助手' } },
      { path: 'reports', name: 'reports', component: () => import('../views/ReportView.vue'), meta: { title: '学习报告 - 智学助手' } },
      { path: 'notes', name: 'notes', component: () => import('../views/NotesView.vue'), meta: { title: '学习笔记 - 智学助手' } },
      { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '个人中心 - 智学助手' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const logged = !!getToken()
  if (!to.meta.public && !logged) {
    return { path: '/login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } }
  }
  if (to.path === '/login' && logged) {
    return { path: '/chat' }
  }
  document.title = to.meta.title || '智学助手'
})

export default router