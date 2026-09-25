<template>
  <div class="tk-wrap">
    <!-- 背景装饰：深空底色 + 霓虹光斑 + 网格（独立裁切层，防溢出滚动） -->
    <div class="tk-bg">
      <div class="tk-orb tk-orb-a"></div>
      <div class="tk-orb tk-orb-b"></div>
      <div class="tk-orb tk-orb-c"></div>
      <div class="tk-grid"></div>
    </div>

    <!-- 左：品牌面板 -->
    <section class="tk-brand">
      <div class="tk-brand-top">
        <div class="tk-logo">
          <div class="tk-logo-mark">智</div>
          <div class="tk-logo-text">智学助手</div>
        </div>
        <a class="tk-gh" href="https://github.com/LeiHub13/smart-learning-assistant" target="_blank" rel="noopener" title="GitHub" aria-label="GitHub">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M12 2C6.48 2 2 6.58 2 12.26c0 4.52 2.87 8.35 6.84 9.71.5.1.68-.22.68-.49 0-.24-.01-.87-.01-1.71-2.78.62-3.37-1.37-3.37-1.37-.45-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.63.07-.63 1 .07 1.53 1.06 1.53 1.06.9 1.57 2.36 1.12 2.94.86.09-.67.35-1.12.63-1.38-2.22-.26-4.56-1.14-4.56-5.07 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.7 0 0 .84-.27 2.75 1.05A9.3 9.3 0 0 1 12 6.84c.85.01 1.71.12 2.51.34 1.9-1.32 2.74-1.05 2.74-1.05.56 1.4.21 2.44.1 2.7.64.72 1.03 1.63 1.03 2.75 0 3.94-2.34 4.8-4.57 5.06.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.6.69.49A10.04 10.04 0 0 0 22 12.26C22 6.58 17.52 2 12 2z"/></svg>
        </a>
      </div>

      <div class="tk-hero">
        <div class="tk-eyebrow"><span class="tk-eyebrow-dot"></span>AI · RAG · Agnes</div>
        <h1>
          <span class="tk-h-sm">用大模型</span>
          <span class="tk-h-sm">重新定义</span>
          <span class="tk-h-lg tk-grad-text">智能学习</span>
        </h1>
        <p>基于 RAG 检索增强生成，覆盖答疑、生成、批改与学情的完整学习闭环。</p>
      </div>

      <div class="tk-feats">
        <div class="tk-feat" v-for="f in feats" :key="f.name">
          <span class="tk-feat-ico"><AppIcon :name="f.icon" :size="15" /></span>
          <div class="tk-feat-body">
            <div class="tk-feat-name">{{ f.name }}</div>
            <div class="tk-feat-desc">{{ f.desc }}</div>
          </div>
        </div>
      </div>

      <div class="tk-tech">Java 业务骨架 · Python langchain AI 服务 · Agnes</div>
    </section>

    <!-- 右：登录 / 注册 -->
    <section class="tk-auth">
      <div class="tk-card">
        <div class="tk-tabs" v-if="mode !== 'forgot'">
          <button type="button" :class="{ on: mode === 'login' }" @click="switchMode('login')">登 录</button>
          <button type="button" :class="{ on: mode === 'register' }" @click="switchMode('register')">注 册</button>
        </div>
        <div class="tk-back-row" v-else>
          <button type="button" class="tk-link" @click="switchMode('login')">← 返回登录</button>
        </div>

        <h2>{{ mode === 'login' ? '欢迎回来' : mode === 'register' ? '创建账号' : '重置密码' }}</h2>
        <p class="tk-sub">{{ mode === 'login' ? '登录你的智学助手账号' : mode === 'register' ? '注册一个新账号开始学习' : '通过绑定邮箱的验证码设置新密码' }}</p>

        <form @submit.prevent="submit">
          <template v-if="mode !== 'forgot'">
            <label class="tk-label" for="username">用户名</label>
            <input id="username" v-model="form.username" type="text" class="tk-input" placeholder="请输入用户名" autocomplete="username" />
            <label class="tk-label" for="password" style="margin-top:14px">密码</label>
            <input id="password" v-model="form.password" type="password" class="tk-input" placeholder="请输入密码" autocomplete="current-password" />
            <template v-if="mode === 'register'">
              <label class="tk-label" for="email" style="margin-top:14px">邮箱</label>
              <input id="email" v-model="form.email" type="email" class="tk-input" placeholder="name@qq.com，用于接收验证码" autocomplete="email" />
              <label class="tk-label" for="code" style="margin-top:14px">邮箱验证码</label>
              <div class="tk-code-row">
                <input id="code" v-model="form.code" type="text" class="tk-input" placeholder="6 位验证码" maxlength="6" inputmode="numeric" />
                <button type="button" class="tk-code-btn" :disabled="countdown > 0 || sending" @click="sendCode">
                  {{ countdown > 0 ? countdown + 's 后重发' : '发送验证码' }}
                </button>
              </div>
            </template>
            <div class="tk-forgot-row" v-if="mode === 'login'">
              <button type="button" class="tk-link" @click="switchMode('forgot')">忘记密码？</button>
            </div>
          </template>
          <template v-else>
            <label class="tk-label" for="fp-email">邮箱</label>
            <input id="fp-email" v-model="forgotForm.email" type="email" class="tk-input" placeholder="注册 / 绑定的邮箱" autocomplete="email" />
            <label class="tk-label" for="fp-code" style="margin-top:14px">邮箱验证码</label>
            <div class="tk-code-row">
              <input id="fp-code" v-model="forgotForm.code" type="text" class="tk-input" placeholder="6 位验证码" maxlength="6" inputmode="numeric" />
              <button type="button" class="tk-code-btn" :disabled="countdown > 0 || sending" @click="sendCode">
                {{ countdown > 0 ? countdown + 's 后重发' : '发送验证码' }}
              </button>
            </div>
            <label class="tk-label" for="fp-new" style="margin-top:14px">新密码</label>
            <input id="fp-new" v-model="forgotForm.password" type="password" class="tk-input" placeholder="至少 6 位" autocomplete="new-password" />
            <label class="tk-label" for="fp-confirm" style="margin-top:14px">确认新密码</label>
            <input id="fp-confirm" v-model="forgotForm.confirm" type="password" class="tk-input" placeholder="再输入一次新密码" autocomplete="new-password" />
          </template>
          <div v-if="error" class="tk-error">{{ error }}</div>
          <div v-if="success" class="tk-success">{{ success }}</div>
          <button type="submit" class="tk-btn" :class="{ loading }" :disabled="loading">
            <span class="spinner"></span>
            <span class="btn-text">{{ mode === 'login' ? '登 录' : mode === 'register' ? '注 册' : '重置密码' }}</span>
          </button>
        </form>

        <div class="tk-demo" v-if="mode === 'login'">
          <div class="tk-demo-title">演示账号 · 密码 123456 · 点击自动填入</div>
          <div class="tk-chips">
            <button type="button" class="tk-chip" v-for="d in demos" :key="d.name" @click="fillDemo(d.name)">
              <span class="dc-name">{{ d.tag }}</span>
              <span class="dc-user">{{ d.name }}</span>
            </button>
          </div>
        </div>
      </div>

      <div class="tk-foot">© {{ year }} 智学助手 · 让每一次学习都有反馈</div>
    </section>
  </div>
</template>

<script setup>
import { onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, setToken } from '../api'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()
const mode = ref('login') // login | register | forgot
const loading = ref(false)
const sending = ref(false)
const error = ref('')
const success = ref('')
const form = reactive({ username: '', password: '', email: '', code: '' })
const forgotForm = reactive({ email: '', code: '', password: '', confirm: '' })
const countdown = ref(0)
let timer = null

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

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
  success.value = ''
}

const startCountdown = () => {
  countdown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}
onUnmounted(() => timer && clearInterval(timer))

const sendCode = async () => {
  const email = (mode.value === 'register' ? form.email : forgotForm.email).trim()
  if (!EMAIL_RE.test(email)) {
    error.value = '请输入正确的邮箱地址'
    return
  }
  sending.value = true
  error.value = ''
  success.value = ''
  try {
    await api('/api/auth/email-code', {
      method: 'POST',
      body: { email, scene: mode.value === 'register' ? 'register' : 'reset_password' }
    })
    success.value = '验证码已发送，请登录邮箱查收（10 分钟内有效）'
    startCountdown()
  } catch (e) {
    error.value = e.message
  } finally {
    sending.value = false
  }
}

const fail = (t) => { error.value = t; return }

const submit = async () => {
  error.value = ''
  success.value = ''
  if (mode.value === 'forgot') {
    if (!EMAIL_RE.test(forgotForm.email.trim())) return fail('请输入正确的邮箱地址')
    if (!forgotForm.code.trim()) return fail('请填写邮箱验证码')
    if (!forgotForm.password || forgotForm.password.length < 6) return fail('新密码至少 6 位')
    if (forgotForm.password !== forgotForm.confirm) return fail('两次输入的新密码不一致')
    loading.value = true
    try {
      await api('/api/auth/reset-password', {
        method: 'POST',
        body: { email: forgotForm.email.trim(), code: forgotForm.code.trim(), newPassword: forgotForm.password }
      })
      Object.assign(forgotForm, { email: '', code: '', password: '', confirm: '' })
      switchMode('login')
      success.value = '密码已重置，请使用新密码登录'
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
    return
  }
  if (!form.username || !form.password) return fail('请输入用户名和密码')
  if (mode.value === 'register' && (!EMAIL_RE.test(form.email.trim()) || !form.code.trim())) {
    return fail(EMAIL_RE.test(form.email.trim()) ? '请填写邮箱验证码' : '请输入正确的邮箱地址')
  }
  loading.value = true
  try {
    const path = mode.value === 'login' ? '/api/auth/login' : '/api/auth/register'
    const body = mode.value === 'login'
      ? { username: form.username, password: form.password }
      : { username: form.username, password: form.password, email: form.email.trim(), code: form.code.trim() }
    const data = await api(path, { method: 'POST', body })
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
/* ==========================================================================
   设计语言：Aurora Glass · Light 风格
   白底 #f7f8fc · 玻璃卡片（白色半透明 + backdrop-blur）· 柔和紫蓝光斑
   强调渐变 紫→靛→蓝，按钮渐变白字，输入聚焦靛蓝描边
   ========================================================================== */
.tk-wrap {
  --bg: #f7f8fc;
  --card: rgba(255, 255, 255, 0.68);
  --card-2: rgba(255, 255, 255, 0.92);
  --line: rgba(20, 22, 31, 0.08);
  --line-2: rgba(20, 22, 31, 0.16);
  --ink: #14161f;
  --ink-2: rgba(20, 22, 31, 0.68);
  --ink-3: rgba(20, 22, 31, 0.46);
  --ink-4: rgba(20, 22, 31, 0.32);
  --vio: #7c3aed;
  --indigo: #4f46e5;
  --blue: #2563eb;
  --pink: #db2777;
  --mint: #059669;
  --grad: linear-gradient(120deg, #7c3aed 0%, #4f46e5 45%, #2563eb 100%);
  --danger: #b91c1c;
  --mono: "Fragment Mono", "Haffer Mono", ui-monospace, SFMono-Regular, Consolas, monospace;

  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 480px;
  background: var(--bg);
  color: var(--ink);
  font-family: "Haffer", "Inter", -apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  overflow: hidden;
  -webkit-font-smoothing: antialiased;
}

/* 覆盖 index.html 里的全局金色焦点环 */
.tk-wrap button:focus-visible,
.tk-wrap a:focus-visible,
.tk-wrap input:focus-visible { outline: 2px solid var(--indigo); outline-offset: 2px; }

/* ===== 背景装饰 ===== */
.tk-bg {
  position: absolute; inset: 0; overflow: hidden; pointer-events: none;
}
.tk-orb {
  position: absolute; border-radius: 50%; pointer-events: none;
  filter: blur(80px); opacity: .55;
  animation: tk-float 14s ease-in-out infinite;
}
.tk-orb-a { width: 620px; height: 620px; top: -260px; right: -120px; background: radial-gradient(circle at 30% 30%, rgba(196, 181, 253, .75), transparent 65%); }
.tk-orb-b { width: 520px; height: 520px; bottom: -260px; left: 8%; background: radial-gradient(circle at 60% 40%, rgba(191, 219, 254, .8), transparent 65%); animation-delay: -5s; }
.tk-orb-c { width: 380px; height: 380px; top: 34%; left: 38%; background: radial-gradient(circle at 50% 50%, rgba(251, 207, 232, .6), transparent 60%); opacity: .45; animation-delay: -9s; }
@keyframes tk-float {
  0%, 100% { transform: translate3d(0, 0, 0) scale(1); }
  50% { transform: translate3d(30px, -24px, 0) scale(1.06); }
}
.tk-grid {
  position: absolute; inset: 0; pointer-events: none;
  background-image:
    linear-gradient(rgba(79, 70, 229, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(79, 70, 229, 0.055) 1px, transparent 1px);
  background-size: 44px 44px;
  -webkit-mask-image: radial-gradient(ellipse 90% 80% at 50% 40%, #000 20%, transparent 75%);
  mask-image: radial-gradient(ellipse 90% 80% at 50% 40%, #000 20%, transparent 75%);
}
/* 光斑带负定位与位移动画：任何断点下都不允许横向滚动 */
.tk-wrap, .tk-bg { overflow-x: clip; }

/* ===== 左侧品牌面板 ===== */
.tk-brand {
  position: relative; z-index: 1;
  display: flex; flex-direction: column; justify-content: center;
  gap: 30px; min-width: 0;
  padding: 54px clamp(32px, 6vw, 88px);
}
.tk-brand-top { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.tk-logo { display: flex; align-items: center; gap: 12px; }
.tk-logo-mark {
  width: 40px; height: 40px; flex: none; border-radius: 13px;
  background: var(--grad); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 17px; font-weight: 800;
  box-shadow: 0 4px 16px rgba(79, 70, 229, .3);
}
.tk-logo-text { font-size: 17px; font-weight: 700; letter-spacing: -0.02em; color: var(--ink); }
.tk-gh {
  width: 38px; height: 38px; flex: none; border-radius: 999px;
  display: flex; align-items: center; justify-content: center;
  color: var(--ink-3); background: var(--card); border: 1px solid var(--line);
  backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
  transition: color .2s ease, border-color .2s ease, transform .2s ease;
}
.tk-gh:hover { color: var(--ink); border-color: var(--line-2); transform: translateY(-1px); }

.tk-eyebrow {
  display: inline-flex; align-items: center; gap: 8px; margin-bottom: 22px;
  padding: 6px 14px; border-radius: 999px;
  background: var(--card); border: 1px solid var(--line);
  backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
  font-family: var(--mono); font-size: 11px; font-weight: 500;
  letter-spacing: .16em; text-transform: uppercase; color: var(--ink-3);
}
.tk-eyebrow-dot {
  width: 7px; height: 7px; border-radius: 50%; background: var(--mint);
  box-shadow: 0 0 6px rgba(5, 150, 105, .55);
}
.tk-hero h1 { line-height: 1.06; }
.tk-h-sm {
  display: block; font-size: clamp(23px, 2.3vw, 33px); font-weight: 500;
  letter-spacing: -0.02em; color: var(--ink-3);
}
.tk-h-lg {
  display: block; margin-top: 2px;
  font-size: clamp(44px, 5.2vw, 74px); font-weight: 900;
  letter-spacing: -0.045em;
}
.tk-grad-text {
  background: var(--grad);
  -webkit-background-clip: text; background-clip: text;
  -webkit-text-fill-color: transparent; color: transparent;
}
.tk-hero p { margin-top: 20px; max-width: 470px; font-size: 15px; line-height: 1.75; color: var(--ink-2); }

.tk-feats { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; max-width: 640px; }
.tk-feat {
  display: flex; gap: 12px; align-items: flex-start;
  padding: 16px; border-radius: 20px;
  background: var(--card); border: 1px solid var(--line);
  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
  transition: transform .2s ease, border-color .2s ease, background .2s ease;
}
.tk-feat:hover { transform: translateY(-2px); border-color: var(--line-2); background: var(--card-2); }
.tk-feat-ico {
  width: 32px; height: 32px; flex: none; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(124, 58, 237, 0.1); color: var(--vio);
  border: 1px solid rgba(124, 58, 237, 0.2);
}
.tk-feat:nth-child(2) .tk-feat-ico { background: rgba(37, 99, 235, 0.1); color: var(--blue); border-color: rgba(37, 99, 235, 0.2); }
.tk-feat:nth-child(3) .tk-feat-ico { background: rgba(219, 39, 119, 0.09); color: var(--pink); border-color: rgba(219, 39, 119, 0.18); }
.tk-feat:nth-child(4) .tk-feat-ico { background: rgba(5, 150, 105, 0.09); color: var(--mint); border-color: rgba(5, 150, 105, 0.18); }
.tk-feat-name { font-size: 14px; font-weight: 700; letter-spacing: -0.01em; color: var(--ink); margin-bottom: 3px; }
.tk-feat-desc { font-size: 12.5px; line-height: 1.55; color: var(--ink-3); }

.tk-tech {
  font-family: var(--mono); font-size: 11px; letter-spacing: .08em;
  color: var(--ink-4);
}

/* ===== 右侧登录 / 注册 ===== */
.tk-auth {
  position: relative; z-index: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 48px 44px 78px;
}
.tk-card {
  width: 100%; max-width: 396px;
  background: var(--card);
  border: 1px solid rgba(255, 255, 255, 0.7); border-radius: 24px;
  padding: 30px 32px 30px;
  backdrop-filter: blur(24px) saturate(150%);
  -webkit-backdrop-filter: blur(24px) saturate(150%);
  box-shadow: 0 1px 0 rgba(255, 255, 255, .85) inset, 0 30px 80px rgba(31, 35, 60, .12);
}

.tk-tabs {
  display: flex; gap: 4px; padding: 4px;
  background: rgba(20, 22, 31, 0.05); border: 1px solid var(--line);
  border-radius: 999px; margin-bottom: 26px;
}
.tk-tabs button {
  flex: 1; height: 38px; border: none; border-radius: 999px;
  background: transparent; color: var(--ink-3);
  font-size: 13.5px; font-weight: 600; font-family: inherit;
  cursor: pointer; transition: color .2s ease, background .2s ease, box-shadow .2s ease;
}
.tk-tabs button:hover { color: var(--ink); }
.tk-tabs button.on {
  background: var(--grad); color: #fff; font-weight: 700;
  box-shadow: 0 4px 14px rgba(79, 70, 229, .32);
}

.tk-card h2 { font-size: 26px; font-weight: 800; letter-spacing: -0.03em; color: var(--ink); }
.tk-sub { margin: 6px 0 24px; font-size: 13.5px; color: var(--ink-3); }

.tk-label {
  display: block; margin-bottom: 7px;
  font-size: 11px; font-weight: 700; letter-spacing: .08em;
  text-transform: uppercase; color: var(--ink-3);
}
.tk-input {
  width: 100%; height: 48px; padding: 0 16px;
  background: #f1f2f8; border: 1.5px solid transparent; border-radius: 14px;
  color: var(--ink); font-size: 14px; font-family: inherit; outline: none;
  transition: border-color .2s ease, background .2s ease, box-shadow .2s ease;
}
.tk-input::placeholder { color: var(--ink-4); }
.tk-input:hover { background: #eceef6; }
.tk-input:focus {
  background: #fff;
  border-color: var(--indigo);
  box-shadow: 0 0 0 4px rgba(79, 70, 229, .12);
}

.tk-error {
  margin: 14px 0 2px; padding: 10px 14px; border-radius: 14px;
  font-size: 13px; line-height: 1.6;
  color: var(--danger); background: rgba(185, 28, 28, .07);
  border: 1px solid rgba(185, 28, 28, .18);
}

.tk-success {
  margin: 14px 0 2px; padding: 10px 14px; border-radius: 14px;
  font-size: 13px; line-height: 1.6;
  color: #047857; background: rgba(5, 150, 105, .08);
  border: 1px solid rgba(5, 150, 105, .2);
}

.tk-forgot-row { margin-top: 10px; display: flex; justify-content: flex-end; }
.tk-back-row { margin-bottom: 16px; }
.tk-link {
  border: none; background: none; padding: 0;
  font-family: inherit; font-size: 13px; font-weight: 600;
  color: var(--vio); cursor: pointer;
}
.tk-link:hover { color: var(--blue); text-decoration: underline; }

.tk-code-row { display: flex; gap: 8px; }
.tk-code-row .tk-input { flex: 1; min-width: 0; }
.tk-code-btn {
  flex: none; height: 48px; padding: 0 16px; border-radius: 14px;
  border: 1.5px solid var(--line); background: var(--card); color: var(--ink-2);
  font-size: 13px; font-weight: 700; font-family: inherit; white-space: nowrap; cursor: pointer;
  transition: background .18s ease, border-color .18s ease, color .18s ease;
}
.tk-code-btn:hover:not(:disabled) { border-color: rgba(79, 70, 229, .45); color: var(--indigo); background: rgba(79, 70, 229, .07); }
.tk-code-btn:disabled { opacity: .55; cursor: not-allowed; }

.tk-btn {
  width: 100%; height: 50px; margin-top: 18px;
  border: none; border-radius: 999px;
  background: var(--grad); color: #fff;
  font-size: 15px; font-weight: 800; letter-spacing: .04em; font-family: inherit;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center; gap: 8px;
  box-shadow: 0 4px 16px rgba(79, 70, 229, .3), inset 0 1px 0 rgba(255, 255, 255, .25);
  transition: box-shadow .18s ease, transform .12s ease, filter .18s ease;
}
.tk-btn:hover { transform: translateY(-1px); filter: brightness(1.08); box-shadow: 0 6px 22px rgba(79, 70, 229, .38), 0 14px 36px rgba(37, 99, 235, .2), inset 0 1px 0 rgba(255, 255, 255, .25); }
.tk-btn:active { transform: translateY(0); box-shadow: 0 2px 10px rgba(79, 70, 229, .35); }
.tk-btn:disabled { opacity: .5; cursor: not-allowed; transform: none; box-shadow: none; }
.tk-btn .spinner {
  width: 16px; height: 16px; border: 2px solid rgba(255, 255, 255, .4);
  border-top-color: #fff; border-radius: 50%;
  animation: tk-spin .8s linear infinite; display: none;
}
.tk-btn.loading .spinner { display: block; }
.tk-btn.loading .btn-text { display: none; }
@keyframes tk-spin { to { transform: rotate(360deg); } }

.tk-demo { margin-top: 26px; padding-top: 20px; border-top: 1px dashed var(--line); }
.tk-demo-title {
  margin-bottom: 12px; font-size: 11px; font-weight: 700;
  letter-spacing: .06em; color: var(--ink-4);
}
.tk-chips { display: flex; gap: 8px; }
.tk-chip {
  flex: 1 1 0; min-width: 0; height: 38px; padding: 0 12px;
  display: inline-flex; align-items: center; justify-content: center; gap: 7px;
  background: var(--card); border: 1px solid var(--line); border-radius: 999px;
  font-family: inherit; cursor: pointer;
  transition: background .18s ease, border-color .18s ease, transform .18s ease;
}
.tk-chip:hover { background: rgba(79, 70, 229, .07); border-color: rgba(79, 70, 229, .38); transform: translateY(-1px); }
.dc-name { font-size: 12.5px; font-weight: 700; color: var(--ink); white-space: nowrap; }
.dc-user { font-family: var(--mono); font-size: 11px; color: var(--ink-4); white-space: nowrap; }
.tk-chip:hover .dc-user { color: var(--indigo); }

.tk-foot {
  position: absolute; bottom: 24px; left: 0; right: 0;
  text-align: center; font-size: 12px; letter-spacing: .02em; color: var(--ink-4);
}

/* ===== 响应式 ===== */
@media (max-width: 1180px) {
  .tk-wrap { display: block; overflow-y: auto; overflow-x: hidden; }
  .tk-brand { gap: 24px; padding: 40px 32px 30px; }
  .tk-feats { max-width: 100%; }
  .tk-auth { padding: 34px 32px 80px; }
}
@media (max-width: 640px) {
  .tk-brand { gap: 20px; padding: 26px 20px 20px; }
  .tk-h-sm { font-size: 22px; }
  .tk-h-lg { font-size: 40px; }
  .tk-hero p { font-size: 13.5px; }
  .tk-feats { grid-template-columns: 1fr; }
  .tk-feat { align-items: center; padding: 13px 14px; }
  .tk-feat-desc { display: none; }
  .tk-tech { display: none; }
  .tk-auth { padding: 26px 18px 76px; }
  .tk-card { padding: 26px 22px; }
  .tk-chips { flex-direction: column; }
  .tk-chip { flex: none; }
  .tk-foot { font-size: 11px; }
}
</style>
