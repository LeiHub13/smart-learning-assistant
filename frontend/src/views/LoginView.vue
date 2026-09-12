<template>
  <div class="wrap" ref="wrapEl">
  <div class="page">
    <div class="left">
      <div class="left-top">
        <div class="logo">
          <div class="logo-mark">智</div>
          <div class="logo-text">智学助手</div>
        </div>

        <div class="hero">
          <h1>
            <span class="line-top">用大模型</span>
            <span class="line-mid">重新定义</span>
            <span class="line-btm">智能学习</span>
          </h1>
          <p>基于 RAG 检索增强生成技术，为教育场景提供 AI 答疑、智能批改、学情分析等全方位支持。</p>
        </div>
      </div>

      <div class="left-bottom">
        <div class="features-subtitle">基于大语言模型的智能学习助手</div>
        <div class="features-bar">
          <div class="feature-item" v-for="(f, i) in feats" :key="f.name">
            <div class="feature-num">0{{ i + 1 }}</div>
            <div class="feature-name">{{ f.name }}</div>
            <div class="feature-desc">{{ f.desc }}</div>
          </div>
        </div>
        <div class="tech-line">Java 业务骨架 · Python langchain AI 服务 · 在线大模型（DeepSeek）</div>
      </div>
    </div>

    <button class="scroll-down" @click="scrollDown" aria-label="向下滚动查看更多">
      <span class="sd-text">下滑了解更多</span>
      <svg class="sd-arrow" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>
    </button>

    <div class="right">
      <div class="login-card">
        <div class="login-header">
          <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
          <p>{{ mode === 'login' ? '登录你的智学助手账号' : '注册一个新账号开始学习' }}</p>
        </div>

        <form @submit.prevent="submit">
          <div class="form-group">
            <label class="form-label" for="username">用户名</label>
            <input id="username" v-model="form.username" type="text" class="form-input" placeholder="请输入用户名" autocomplete="username" />
          </div>
          <div class="form-group">
            <label class="form-label" for="password">密码</label>
            <input id="password" v-model="form.password" type="password" class="form-input" placeholder="请输入密码" autocomplete="current-password" />
          </div>
          <div v-if="error" class="form-error">{{ error }}</div>
          <button type="submit" class="btn-login" :class="{ loading }" :disabled="loading">
            <span class="spinner"></span>
            <span class="btn-text">{{ mode === 'login' ? '登 录' : '注 册' }}</span>
          </button>
        </form>

        <div class="login-divider">或</div>

        <div class="login-footer">
          <template v-if="mode === 'login'">还没有账号？<a @click="switchMode('register')">立即注册</a></template>
          <template v-else>已有账号？<a @click="switchMode('login')">直接登录</a></template>
        </div>

        <div class="demo-box" v-if="mode === 'login'">
          <div class="demo-title">演示账号（密码：123456）</div>
          <div class="demo-list">
            <div class="demo-row" v-for="d in demos" :key="d.name" @click="fillDemo(d.name)">
              <span class="demo-name">{{ d.name }}</span>
              <span class="demo-tag">{{ d.tag }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <footer class="site-footer">
    <div class="ft-inner">
      <div class="ft-brand">
        <div class="ft-logo">
          <div class="logo-mark">智</div>
          <div class="logo-text">智学助手</div>
        </div>
        <p class="ft-meta">Java 业务骨架 · Python langchain</p>
        <p class="ft-meta">在线大模型（DeepSeek）· RAG 检索</p>
        <div class="ft-icons">
          <span class="ft-icon" title="邮箱" aria-label="邮箱">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M3 7l9 7 9-7"/></svg>
          </span>
          <a class="ft-icon" href="https://github.com/LeiHub13/smart-learning-assistant" target="_blank" rel="noopener" title="GitHub" aria-label="GitHub">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M12 2C6.48 2 2 6.58 2 12.26c0 4.52 2.87 8.35 6.84 9.71.5.1.68-.22.68-.49 0-.24-.01-.87-.01-1.71-2.78.62-3.37-1.37-3.37-1.37-.45-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.63.07-.63 1 .07 1.53 1.06 1.53 1.06.9 1.57 2.36 1.12 2.94.86.09-.67.35-1.12.63-1.38-2.22-.26-4.56-1.14-4.56-5.07 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.7 0 0 .84-.27 2.75 1.05A9.3 9.3 0 0 1 12 6.84c.85.01 1.71.12 2.51.34 1.9-1.32 2.74-1.05 2.74-1.05.56 1.4.21 2.44.1 2.7.64.72 1.03 1.63 1.03 2.75 0 3.94-2.34 4.8-4.57 5.06.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.6.69.49A10.04 10.04 0 0 0 22 12.26C22 6.58 17.52 2 12 2z"/></svg>
          </a>
        </div>
      </div>
      <div class="ft-col" v-for="col in footerCols" :key="col.title">
        <div class="ft-col-title">{{ col.title }}</div>
        <span v-for="item in col.items" :key="item" class="ft-link">{{ item }}</span>
      </div>
    </div>
    <div class="ft-copy">© {{ year }} 智学助手 版权所有</div>
  </footer>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../api'

const router = useRouter()
const wrapEl = ref(null)
const mode = ref('login')
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

const feats = [
  { name: 'AI 答疑', desc: '基于课程知识库检索（RAG）作答，回答标注引用来源，支持多轮对话与记忆' },
  { name: '讲义与题目生成', desc: '一键生成课程讲义、练习题，题目自动入库供练习使用' },
  { name: '智能批改', desc: '主观题由大模型按评分标准打分并逐题点评，客观题自动判分' },
  { name: '学情分析', desc: '知识点掌握度追踪，AI 生成个性化复习建议' },
]

const demos = [
  { name: 'xiaoming', tag: '小明' },
  { name: 'xiaohong', tag: '小红' },
  { name: 'xiaoyu', tag: '小宇' },
]

const year = new Date().getFullYear()
const footerCols = [
  { title: '产品', items: ['AI 答疑', '讲义与题目生成', '题库练习', '智能批改', '学情分析'] },
  { title: '技术', items: ['RAG 检索增强', '会话记忆', '知识库管理', '流式生成'] },
  { title: '法务 & 安全', items: ['隐私政策', '用户协议', '数据隔离'] },
  { title: '关于', items: ['使用说明', '反馈建议'] },
]

const fillDemo = (name) => {
  form.username = name
  form.password = '123456'
  error.value = ''
}

const scrollDown = () => {
  const el = wrapEl.value
  if (el) el.scrollTo({ top: el.clientHeight, behavior: 'smooth' })
}

const switchMode = (m) => {
  mode.value = m
  error.value = ''
}

const submit = async () => {
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const path = mode.value === 'login' ? '/api/auth/login' : '/api/auth/register'
    const data = await api(path, { method: 'POST', body: form })
    setToken(data.accessToken || data.token)
    const target = router.currentRoute.value.query.redirect || '/home'
    router.replace(target)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
:root { --lg-accent: #b8956a; }

.wrap {
  position: fixed;
  inset: 0;
  z-index: 200;
  overflow-y: auto;
  background: #f5f3ef;
  font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  color: #1a1a1a;
}
.page {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 520px;
  min-height: 100vh;
  background: transparent;
}
.page::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(0,0,0,0.015) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0,0,0,0.015) 1px, transparent 1px);
  background-size: 40px 40px;
  pointer-events: none;
  z-index: 0;
}

.left {
  padding: 64px 0 80px 100px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  position: relative;
  z-index: 1;
}

.logo { display: flex; align-items: center; gap: 12px; margin-bottom: 100px; }
.logo-mark {
  width: 36px; height: 36px; background: #1a1a1a; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 16px; font-weight: 800;
}
.logo-text { font-size: 16px; font-weight: 700; letter-spacing: -0.02em; }

.hero { max-width: 560px; }
.hero h1 { margin-bottom: 32px; }
.hero h1 .line-top,
.hero h1 .line-btm {
  display: block; font-size: 52px; font-weight: 400; color: #888;
  line-height: 1.2; letter-spacing: -0.02em;
}
.hero h1 .line-mid {
  display: block; font-size: 80px; font-weight: 900; color: #1a1a1a;
  line-height: 1.05; letter-spacing: -0.04em; margin: 4px 0;
}
.hero p { font-size: 15px; line-height: 1.7; color: #555; max-width: 380px; }

.left-bottom { margin-top: auto; padding-top: 80px; }
.features-subtitle { font-size: 13px; font-weight: 600; color: #555; margin-bottom: 28px; letter-spacing: 0.01em; }
.features-bar { display: flex; gap: 44px; margin-bottom: 36px; }
.feature-item { display: flex; flex-direction: column; gap: 6px; min-width: 150px; }
.feature-num { font-size: 12px; font-weight: 700; color: #b8956a; font-family: "SF Mono", Consolas, monospace; letter-spacing: 0.08em; }
.feature-name { font-size: 17px; font-weight: 700; letter-spacing: -0.01em; }
.feature-desc { font-size: 13px; color: #888; line-height: 1.6; max-width: 180px; }
.tech-line { font-size: 13px; color: #aaa; font-family: "SF Mono", Consolas, monospace; letter-spacing: 0.02em; }

.scroll-down {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 3;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 6px 12px;
  background: none;
  border: none;
  cursor: pointer;
  color: #999;
  font-family: inherit;
  transition: color 0.2s ease;
}
.scroll-down:hover { color: #1a1a1a; }
.sd-text { font-size: 11px; letter-spacing: 0.2em; white-space: nowrap; }
.sd-arrow { animation: sd-bounce 1.8s ease-in-out infinite; }
@keyframes sd-bounce {
  0%, 100% { transform: translateY(0); opacity: 0.55; }
  50% { transform: translateY(5px); opacity: 1; }
}

.right {
  padding: 80px 80px 80px 0;
  display: flex; align-items: center;
  position: relative; z-index: 2;
}

.login-card {
  width: 100%; max-width: 460px; background: #fff;
  border-radius: 16px; padding: 36px 40px; margin-left: -100px;
  box-shadow:
    0 1px 2px rgba(0,0,0,0.02), 0 4px 8px rgba(0,0,0,0.03),
    0 12px 24px rgba(0,0,0,0.04), 0 24px 48px rgba(0,0,0,0.05),
    0 48px 96px rgba(0,0,0,0.04);
  position: relative;
}
.login-card::before {
  content: ''; position: absolute; top: 0; left: 40px; right: 40px; height: 1px;
  background: linear-gradient(90deg, transparent, rgba(0,0,0,0.06), transparent);
}

.login-header { margin-bottom: 28px; }
.login-header h2 { font-size: 22px; font-weight: 800; letter-spacing: -0.02em; margin-bottom: 4px; }
.login-header p { font-size: 13px; color: #888; }

.form-group { margin-bottom: 14px; }
.form-label {
  display: block; font-size: 11px; font-weight: 600; color: #555;
  margin-bottom: 6px; letter-spacing: 0.04em; text-transform: uppercase;
}
.form-input {
  width: 100%; height: 44px; padding: 0 16px;
  background: #fafafa; border: 1.5px solid #e0ddd6; border-radius: 12px;
  color: #1a1a1a; font-size: 14px; font-family: inherit;
  transition: all 0.25s ease; outline: none;
}
.form-input::placeholder { color: #aaa; }
.form-input:hover { border-color: #c8c4bc; background: #fff; }
.form-input:focus {
  border-color: #b8956a; border-width: 2px; background: #fff;
  box-shadow: 0 0 0 4px rgba(184,149,106,0.1); padding: 0 15px;
}

.form-error {
  font-size: 13px; color: #b3423a; background: #fbf0ef;
  border: 1px solid #eed4d1; border-radius: 10px;
  padding: 9px 14px; margin-bottom: 12px;
}

.btn-login {
  width: 100%; height: 46px; background: #1a1a1a; border: none; border-radius: 12px;
  color: #fff; font-size: 15px; font-weight: 600; font-family: inherit;
  cursor: pointer; transition: all 0.25s ease; margin-top: 6px;
  letter-spacing: 0.02em;
  display: flex; align-items: center; justify-content: center; gap: 8px;
}
.btn-login:hover { background: #333; transform: translateY(-2px); box-shadow: 0 8px 20px rgba(0,0,0,0.15); }
.btn-login .spinner {
  width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff; border-radius: 50%;
  animation: lg-spin 0.8s linear infinite; display: none;
}
.btn-login.loading .spinner { display: block; }
.btn-login.loading .btn-text { display: none; }
@keyframes lg-spin { to { transform: rotate(360deg); } }

.login-divider {
  display: flex; align-items: center; gap: 16px; margin: 18px 0;
  color: #aaa; font-size: 11px; font-weight: 600;
  letter-spacing: 0.1em; text-transform: uppercase;
}
.login-divider::before, .login-divider::after { content: ''; flex: 1; height: 1px; background: #e0ddd6; }

.login-footer { text-align: center; font-size: 13px; color: #888; }
.login-footer a {
  color: #1a1a1a; text-decoration: none; font-weight: 600;
  border-bottom: 1px solid #e0ddd6; padding-bottom: 1px;
  transition: all 0.2s; cursor: pointer;
}
.login-footer a:hover { border-color: #b8956a; color: #b8956a; }

.demo-box {
  margin-top: 20px; padding: 14px;
  background: #fafafa; border: 1px solid #e0ddd6; border-radius: 12px;
}
.demo-title {
  font-size: 10px; font-weight: 700; color: #aaa;
  text-transform: uppercase; letter-spacing: 0.1em; margin-bottom: 8px;
}
.demo-list { display: flex; flex-direction: column; gap: 8px; }
.demo-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 14px; background: #fff; border: 1px solid #e0ddd6;
  border-radius: 10px; cursor: pointer; transition: all 0.2s ease;
}
.demo-row:hover { border-color: #b8956a; background: rgba(184,149,106,0.05); }
.demo-name { font-size: 14px; font-weight: 500; }
.demo-tag { font-size: 12px; color: #aaa; font-family: "SF Mono", Consolas, monospace; }

.site-footer {
  position: relative;
  background: #f7f6f3;
  border-top: 1px solid #eceae4;
  padding: 64px 80px 0;
}
.ft-inner {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr 1fr;
  gap: 40px;
  max-width: 1180px;
  margin: 0 auto;
  padding-bottom: 56px;
}
.ft-logo { display: flex; align-items: center; gap: 10px; margin-bottom: 22px; }
.ft-logo .logo-mark { width: 28px; height: 28px; font-size: 13px; border-radius: 8px; }
.ft-logo .logo-text { font-size: 18px; font-weight: 700; color: #4a6cf7; letter-spacing: -0.02em; }
.ft-meta { font-size: 13px; color: #888; line-height: 1.8; }
.ft-icons { display: flex; gap: 10px; margin-top: 22px; }
.ft-icon {
  width: 32px; height: 32px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  color: #666; background: #eeeae4; text-decoration: none;
  transition: background 0.2s, color 0.2s;
}
.ft-icon:hover { background: #e0ddd6; color: #1a1a1a; }
.ft-col { display: flex; flex-direction: column; gap: 18px; }
.ft-col-title { font-size: 14px; font-weight: 600; color: #1a1a1a; margin-bottom: 4px; }
.ft-link {
  font-size: 14px; color: #6b6b6b; text-decoration: none;
  line-height: 1.4; cursor: default;
}
.ft-copy {
  max-width: 1180px; margin: 0 auto;
  border-top: 1px solid #eceae4;
  padding: 22px 0 28px;
  font-size: 13px; color: #888;
}

@media (max-width: 1100px) {
  .page { grid-template-columns: 1fr; }
  .left { padding: 48px 40px 60px; text-align: center; }
  .hero { max-width: 100%; }
  .hero h1 .line-top, .hero h1 .line-btm { font-size: 40px; }
  .hero h1 .line-mid { font-size: 64px; }
  .hero p { max-width: 100%; margin: 0 auto; }
  .logo { justify-content: center; margin-bottom: 60px; }
  .features-subtitle { display: none; }
  .features-bar { justify-content: center; flex-wrap: wrap; }
  .feature-desc { max-width: 100%; }
  .tech-line { display: none; }
  .right { padding: 0 40px 60px; justify-content: center; }
  .login-card { margin-left: 0; max-width: 480px; }
  .site-footer { padding: 48px 40px 0; }
  .ft-inner { grid-template-columns: 1fr 1fr; gap: 32px 24px; }
}

@media (max-width: 480px) {
  .left, .right { padding-left: 24px; padding-right: 24px; }
  .hero h1 .line-top, .hero h1 .line-btm { font-size: 32px; }
  .hero h1 .line-mid { font-size: 48px; }
  .login-card { padding: 36px 24px; }
  .features-bar { gap: 24px; }
  .feature-desc { max-width: 100%; font-size: 12px; }
  .feature-name { font-size: 15px; }
  .tech-line { display: none; }
  .site-footer { padding: 40px 24px 0; }
  .ft-inner { grid-template-columns: 1fr 1fr; gap: 28px 16px; padding-bottom: 36px; }
}
</style>