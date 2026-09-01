<template>
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
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../api'

const router = useRouter()
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

const fillDemo = (name) => {
  form.username = name
  form.password = '123456'
  error.value = ''
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
    const target = router.currentRoute.value.query.redirect || '/chat'
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

.page {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  grid-template-columns: 1fr 520px;
  min-height: 100vh;
  background: #f5f3ef;
  font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  color: #1a1a1a;
}
.page::before {
  content: '';
  position: fixed;
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
}
</style>