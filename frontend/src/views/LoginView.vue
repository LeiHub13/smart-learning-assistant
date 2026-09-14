<template>
  <div class="wrap">
    <!-- 左：品牌面板 -->
    <section class="brand">
      <div class="brand-top">
        <div class="logo">
          <div class="logo-mark">智</div>
          <div class="logo-text">智学助手</div>
        </div>
        <a class="gh" href="https://github.com/LeiHub13/smart-learning-assistant" target="_blank" rel="noopener" title="GitHub" aria-label="GitHub">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M12 2C6.48 2 2 6.58 2 12.26c0 4.52 2.87 8.35 6.84 9.71.5.1.68-.22.68-.49 0-.24-.01-.87-.01-1.71-2.78.62-3.37-1.37-3.37-1.37-.45-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.63.07-.63 1 .07 1.53 1.06 1.53 1.06.9 1.57 2.36 1.12 2.94.86.09-.67.35-1.12.63-1.38-2.22-.26-4.56-1.14-4.56-5.07 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.7 0 0 .84-.27 2.75 1.05A9.3 9.3 0 0 1 12 6.84c.85.01 1.71.12 2.51.34 1.9-1.32 2.74-1.05 2.74-1.05.56 1.4.21 2.44.1 2.7.64.72 1.03 1.63 1.03 2.75 0 3.94-2.34 4.8-4.57 5.06.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.6.69.49A10.04 10.04 0 0 0 22 12.26C22 6.58 17.52 2 12 2z"/></svg>
        </a>
      </div>

      <div class="hero">
        <h1>
          <span class="line-top">用大模型</span>
          <span class="line-mid">重新定义</span>
          <span class="line-btm">智能学习</span>
        </h1>
        <p>基于 RAG 检索增强生成，覆盖答疑、生成、批改与学情的完整学习闭环。</p>
      </div>

      <div class="feats">
        <div class="feat" v-for="f in feats" :key="f.name">
          <span class="feat-ico"><AppIcon :name="f.icon" :size="15" /></span>
          <div class="feat-body">
            <div class="feat-name">{{ f.name }}</div>
            <div class="feat-desc">{{ f.desc }}</div>
          </div>
        </div>
      </div>

      <div class="tech-line">Java 业务骨架 · Python langchain AI 服务 · DeepSeek</div>
    </section>

    <!-- 右：登录/注册 -->
    <section class="auth">
      <div class="auth-card">
        <div class="mode-tabs">
          <button :class="{ on: mode === 'login' }" @click="switchMode('login')">登 录</button>
          <button :class="{ on: mode === 'register' }" @click="switchMode('register')">注 册</button>
        </div>

        <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
        <p class="auth-sub">{{ mode === 'login' ? '登录你的智学助手账号' : '注册一个新账号开始学习' }}</p>

        <form @submit.prevent="submit">
          <label class="form-label" for="username">用户名</label>
          <input id="username" v-model="form.username" type="text" class="form-input" placeholder="请输入用户名" autocomplete="username" />
          <label class="form-label" for="password" style="margin-top:14px">密码</label>
          <input id="password" v-model="form.password" type="password" class="form-input" placeholder="请输入密码" autocomplete="current-password" />
          <div v-if="error" class="form-error">{{ error }}</div>
          <button type="submit" class="btn-login" :class="{ loading }" :disabled="loading">
            <span class="spinner"></span>
            <span class="btn-text">{{ mode === 'login' ? '登 录' : '注 册' }}</span>
          </button>
        </form>

        <div class="demo" v-if="mode === 'login'">
          <div class="demo-title">演示账号 · 密码 123456 · 点击自动填入</div>
          <div class="demo-chips">
            <button type="button" class="demo-chip" v-for="d in demos" :key="d.name" @click="fillDemo(d.name)">
              <span class="dc-name">{{ d.tag }}</span>
              <span class="dc-user">{{ d.name }}</span>
            </button>
          </div>
        </div>
      </div>

      <div class="auth-foot">© {{ year }} 智学助手 · 让每一次学习都有反馈</div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../api'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()
const mode = ref('login')
const loading = ref(false)
const error = ref('')
const form = reactive({ username: '', password: '' })

const feats = [
  { icon: 'chat', name: 'AI 答疑', desc: '课程知识库 RAG 检索，回答标注引用来源' },
  { icon: 'sparkles', name: '讲义与题目生成', desc: '一键流式生成讲义，题目自动入库' },
  { icon: 'practice', name: '练习与批改', desc: '客观题自动判分，主观题 AI 点评' },
  { icon: 'progress', name: '学情分析', desc: '掌握度追踪、错题归集与个性化建议' },
]

const demos = [
  { name: 'pg13', tag: '泡椒' },
  { name: 'xiaohong', tag: '小红' },
  { name: 'xiaoyu', tag: '小宇' },
]

const year = new Date().getFullYear()

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
.wrap {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  grid-template-columns: 1.15fr 480px;
  background: #f5f3ef;
  font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  color: #1a1a1a;
  overflow: hidden;
}

/* ===== 左侧品牌面板 ===== */
.brand {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 44px 64px 36px 72px;
  background: linear-gradient(155deg, #232019 0%, #17150f 58%, #12100c 100%);
  color: #fff;
  overflow: hidden;
}
.brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,0.028) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,0.028) 1px, transparent 1px);
  background-size: 44px 44px;
  pointer-events: none;
}
.brand::after {
  content: '';
  position: absolute;
  width: 560px; height: 560px;
  left: -160px; bottom: -220px;
  background: radial-gradient(circle, rgba(184,149,106,0.16), transparent 65%);
  pointer-events: none;
}
.brand > * { position: relative; z-index: 1; }

.brand-top { display: flex; align-items: center; justify-content: space-between; }
.logo { display: flex; align-items: center; gap: 12px; }
.logo-mark {
  width: 36px; height: 36px; background: #f5f3ef; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  color: #17150f; font-size: 16px; font-weight: 800;
}
.logo-text { font-size: 16px; font-weight: 700; letter-spacing: -0.02em; color: #f5f3ef; }
.gh {
  width: 34px; height: 34px; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  color: rgba(245,243,239,0.55); background: rgba(255,255,255,0.06);
  border: 1px solid rgba(255,255,255,0.09);
  transition: all .2s ease;
}
.gh:hover { color: #fff; background: rgba(255,255,255,0.1); }

.hero { margin: 8px 0 36px; }
.hero h1 { margin-bottom: 20px; }
.hero h1 .line-top,
.hero h1 .line-btm {
  display: block; font-size: 40px; font-weight: 400;
  color: rgba(245,243,239,0.44); line-height: 1.18; letter-spacing: -0.02em;
}
.hero h1 .line-mid {
  display: block; font-size: 68px; font-weight: 900; color: #f5f3ef;
  line-height: 1.08; letter-spacing: -0.04em; margin: 2px 0;
}
.hero h1 .line-mid::after {
  content: '';
  display: block; width: 56px; height: 5px; border-radius: 3px;
  background: #b8956a; margin-top: 10px;
}
.hero p { font-size: 14px; line-height: 1.7; color: rgba(245,243,239,0.55); max-width: 420px; }

.feats { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 22px; margin-bottom: 34px; }
.feat {
  display: flex; gap: 12px; align-items: flex-start;
  padding: 13px 14px; border-radius: 12px;
  background: rgba(255,255,255,0.045);
  border: 1px solid rgba(255,255,255,0.07);
}
.feat-ico {
  width: 32px; height: 32px; flex: none; border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
  color: #cfb18a; background: rgba(184,149,106,0.14);
}
.feat-name { font-size: 13.5px; font-weight: 700; letter-spacing: -0.01em; margin-bottom: 3px; }
.feat-desc { font-size: 12px; color: rgba(245,243,239,0.48); line-height: 1.55; }

.tech-line {
  font-size: 12px; color: rgba(245,243,239,0.32);
  font-family: "SF Mono", Consolas, monospace; letter-spacing: 0.03em;
}

/* ===== 右侧登录/注册 ===== */
.auth {
  position: relative;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  padding: 40px 56px;
  background:
    radial-gradient(1200px 500px at 80% -10%, rgba(184,149,106,0.07), transparent 60%),
    #f5f3ef;
}
.auth-card { width: 100%; max-width: 372px; }

.mode-tabs {
  display: flex; gap: 4px; padding: 4px;
  background: #eae7df; border-radius: 12px; margin-bottom: 26px;
}
.mode-tabs button {
  flex: 1; height: 38px; border: none; border-radius: 9px;
  background: transparent; color: #777;
  font-size: 14px; font-weight: 600; font-family: inherit;
  cursor: pointer; transition: all .2s ease;
}
.mode-tabs button.on {
  background: #fff; color: #1a1a1a; font-weight: 800;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}

.auth-card h2 { font-size: 24px; font-weight: 800; letter-spacing: -0.02em; margin-bottom: 4px; }
.auth-sub { font-size: 13px; color: #888; margin-bottom: 24px; }

.form-label {
  display: block; font-size: 11px; font-weight: 600; color: #555;
  margin-bottom: 6px; letter-spacing: 0.05em;
}
.form-input {
  width: 100%; height: 44px; padding: 0 16px;
  background: #fafaf8; border: 1.5px solid #e0ddd6; border-radius: 12px;
  color: #1a1a1a; font-size: 14px; font-family: inherit;
  transition: all 0.25s ease; outline: none; margin-bottom: 4px;
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
  padding: 9px 14px; margin: 10px 0 4px;
}

.btn-login {
  height: 46px; width: 100%; margin-top: 16px;
  border: none; border-radius: 12px;
  background: linear-gradient(180deg, #2e2b27, #191816);
  color: #fff; font-size: 15px; font-weight: 600; letter-spacing: .06em; font-family: inherit;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(26,26,26,.16), inset 0 1px 0 rgba(255,255,255,.09);
  display: flex; align-items: center; justify-content: center; gap: 8px;
  transition: background .18s ease, box-shadow .18s ease, transform .1s ease;
}
.btn-login:hover { background: linear-gradient(180deg, #262320, #121110); box-shadow: 0 2px 4px rgba(26,26,26,.16), 0 8px 20px rgba(26,26,26,.14), inset 0 1px 0 rgba(255,255,255,.08); }
.btn-login:active { transform: translateY(1px); box-shadow: 0 1px 2px rgba(26,26,26,.2); }
.btn-login:disabled { opacity: .5; cursor: not-allowed; box-shadow: none; }
.btn-login .spinner {
  width: 16px; height: 16px; border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff; border-radius: 50%;
  animation: lg-spin 0.8s linear infinite; display: none;
}
.btn-login.loading .spinner { display: block; }
.btn-login.loading .btn-text { display: none; }
@keyframes lg-spin { to { transform: rotate(360deg); } }

.demo { margin-top: 26px; padding-top: 18px; border-top: 1px dashed #ddd9d0; }
.demo-title {
  font-size: 10.5px; font-weight: 700; color: #aaa;
  text-transform: uppercase; letter-spacing: 0.08em; margin-bottom: 10px;
}
.demo-chips { display: flex; gap: 8px; }
.demo-chip {
  flex: 1; padding: 9px 0 8px; text-align: center;
  background: #fafaf8; border: 1px solid #e0ddd6; border-radius: 11px;
  cursor: pointer; font-family: inherit; transition: all .2s ease;
  display: flex; flex-direction: column; gap: 1px;
}
.demo-chip:hover { border-color: #b8956a; background: rgba(184,149,106,0.06); transform: translateY(-1px); }
.dc-name { font-size: 13px; font-weight: 700; color: #1a1a1a; }
.dc-user { font-size: 11px; color: #aaa; font-family: "SF Mono", Consolas, monospace; }

.auth-foot {
  position: absolute; bottom: 22px; left: 0; right: 0;
  text-align: center; font-size: 12px; color: #b5b1a8; letter-spacing: 0.02em;
}

/* ===== 响应式 ===== */
@media (max-width: 1100px) {
  .wrap { display: block; overflow-y: auto; }
  .brand { padding: 32px 32px 26px; }
  .hero { margin: 22px 0 24px; }
  .hero h1 .line-top, .hero h1 .line-btm { font-size: 26px; }
  .hero h1 .line-mid { font-size: 46px; }
  .hero h1 .line-mid::after { width: 42px; height: 4px; margin-top: 8px; }
  .hero p { max-width: 100%; font-size: 13px; }
  .feats { grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 0; }
  .feat { padding: 10px 12px; }
  .tech-line { display: none; }
  .auth { min-height: 82vh; padding: 36px 32px 72px; }
}
@media (max-width: 620px) {
  .brand { padding: 26px 22px 20px; }
  .hero h1 .line-top, .hero h1 .line-btm { font-size: 20px; }
  .hero h1 .line-mid { font-size: 36px; }
  .feats { grid-template-columns: 1fr; }
  .feat-desc { display: none; }
  .feat { align-items: center; }
  .auth { padding: 28px 20px 72px; }
  .auth-foot { font-size: 11px; }
}
</style>
