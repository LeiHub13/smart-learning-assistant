<template>
  <div class="tk-wrap">
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
        <div class="tk-eyebrow">AI · RAG · Agnes</div>
        <h1>
          <span class="tk-h-sm">用大模型</span>
          <span class="tk-h-sm">重新定义</span>
          <span class="tk-h-lg">智能学习</span>
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
   设计语言：GitHub Primer Light 风格
   白底 · 灰边框 #d1d9e0 · 链接蓝 #0969da · 主按钮绿 #1f883d · 6px 圆角
   无渐变无毛玻璃，靠留白与分隔线建立层次
   ========================================================================== */
.tk-wrap {
  --bg: #ffffff;
  --soft: #f6f8fa;
  --line: #d1d9e0;
  --ink: #1f2328;
  --ink-2: #57606a;
  --ink-3: #59636e;
  --ink-4: #818b98;
  --blue: #0969da;
  --green: #1f883d;
  --danger: #cf222e;
  --mono: ui-monospace, SFMono-Regular, "SF Mono", Consolas, monospace;

  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 480px;
  background: var(--bg);
  color: var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans", Helvetica, Arial, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  overflow: hidden;
  -webkit-font-smoothing: antialiased;
}

/* 覆盖 index.html 里的全局焦点环 */
.tk-wrap button:focus-visible,
.tk-wrap a:focus-visible,
.tk-wrap input:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }

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
  width: 38px; height: 38px; flex: none; border-radius: 8px;
  background: #24292f; color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 17px; font-weight: 700;
}
.tk-logo-text { font-size: 17px; font-weight: 700; letter-spacing: -.01em; color: var(--ink); }
.tk-gh {
  width: 36px; height: 36px; flex: none; border-radius: 6px;
  display: flex; align-items: center; justify-content: center;
  color: var(--ink-3); background: #fff; border: 1px solid var(--line);
  transition: color .15s ease, background .15s ease;
}
.tk-gh:hover { color: var(--ink); background: var(--soft); }

.tk-eyebrow {
  display: inline-block; margin-bottom: 22px;
  padding: 3px 12px; border-radius: 999px;
  background: var(--soft); border: 1px solid var(--line);
  font-family: var(--mono); font-size: 11px; font-weight: 500;
  letter-spacing: .14em; text-transform: uppercase; color: var(--ink-3);
}
.tk-hero h1 { line-height: 1.08; }
.tk-h-sm {
  display: block; font-size: clamp(22px, 2.2vw, 30px); font-weight: 400;
  letter-spacing: -.01em; color: var(--ink-3);
}
.tk-h-lg {
  display: block; margin-top: 2px;
  font-size: clamp(42px, 5vw, 68px); font-weight: 700;
  letter-spacing: -.03em; color: var(--ink);
}
.tk-hero p { margin-top: 18px; max-width: 470px; font-size: 15px; line-height: 1.75; color: var(--ink-2); }

.tk-feats { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; max-width: 640px; }
.tk-feat {
  display: flex; gap: 12px; align-items: flex-start;
  padding: 14px 16px; border-radius: 8px;
  background: var(--soft); border: 1px solid var(--line);
  transition: background .15s ease;
}
.tk-feat:hover { background: #eef1f4; }
.tk-feat-ico {
  width: 30px; height: 30px; flex: none; border-radius: 6px;
  display: flex; align-items: center; justify-content: center;
  background: #ddf4ff; color: var(--blue);
}
.tk-feat:nth-child(2) .tk-feat-ico { background: #dafbe1; color: #1a7f37; }
.tk-feat:nth-child(3) .tk-feat-ico { background: #fff1e5; color: #bc4c00; }
.tk-feat:nth-child(4) .tk-feat-ico { background: #fbefff; color: #8250df; }
.tk-feat-name { font-size: 14px; font-weight: 600; color: var(--ink); margin-bottom: 3px; }
.tk-feat-desc { font-size: 12.5px; line-height: 1.55; color: var(--ink-3); }

.tk-tech {
  font-family: var(--mono); font-size: 11px; letter-spacing: .06em;
  color: var(--ink-4);
}

/* ===== 右侧登录 / 注册 ===== */
.tk-auth {
  position: relative; z-index: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 48px 44px 78px;
  border-left: 1px solid var(--line);
}
.tk-card {
  width: 100%; max-width: 396px;
  background: #fff; border: 1px solid var(--line); border-radius: 8px;
  padding: 28px 32px 30px;
  box-shadow: 0 1px 3px rgba(31, 35, 40, .06);
}

.tk-tabs {
  display: flex; gap: 4px; padding: 3px;
  background: var(--soft); border: 1px solid var(--line);
  border-radius: 6px; margin-bottom: 24px;
}
.tk-tabs button {
  flex: 1; height: 34px; border: none; border-radius: 4px;
  background: transparent; color: var(--ink-3);
  font-size: 13.5px; font-weight: 500; font-family: inherit;
  cursor: pointer; transition: color .15s ease, background .15s ease, box-shadow .15s ease;
}
.tk-tabs button:hover { color: var(--ink); }
.tk-tabs button.on {
  background: #fff; color: var(--ink); font-weight: 600;
  box-shadow: 0 1px 2px rgba(31, 35, 40, .1);
}

.tk-card h2 { font-size: 24px; font-weight: 600; letter-spacing: -.02em; color: var(--ink); }
.tk-sub { margin: 6px 0 22px; font-size: 13.5px; color: var(--ink-3); }

.tk-label {
  display: block; margin-bottom: 6px;
  font-size: 13px; font-weight: 500;
  color: var(--ink);
}
.tk-input {
  width: 100%; height: 40px; padding: 0 12px;
  background: #fff; border: 1px solid var(--line); border-radius: 6px;
  color: var(--ink); font-size: 14px; font-family: inherit; outline: none;
  transition: border-color .15s ease, box-shadow .15s ease;
}
.tk-input::placeholder { color: var(--ink-4); }
.tk-input:hover { border-color: #8c959f; }
.tk-input:focus { border-color: var(--blue); box-shadow: 0 0 0 3px rgba(9, 105, 218, .2); }

.tk-error {
  margin: 14px 0 2px; padding: 10px 14px; border-radius: 6px;
  font-size: 13px; line-height: 1.6;
  color: #a40e26; background: #ffebe9;
  border: 1px solid #ffc1bc;
}

.tk-success {
  margin: 14px 0 2px; padding: 10px 14px; border-radius: 6px;
  font-size: 13px; line-height: 1.6;
  color: #116329; background: #dafbe1;
  border: 1px solid #aceebb;
}

.tk-forgot-row { margin-top: 10px; display: flex; justify-content: flex-end; }
.tk-back-row { margin-bottom: 16px; }
.tk-link {
  border: none; background: none; padding: 0;
  font-family: inherit; font-size: 13px; font-weight: 500;
  color: var(--blue); cursor: pointer;
}
.tk-link:hover { text-decoration: underline; }

.tk-code-row { display: flex; gap: 8px; }
.tk-code-row .tk-input { flex: 1; min-width: 0; }
.tk-code-btn {
  flex: none; height: 40px; padding: 0 14px; border-radius: 6px;
  border: 1px solid rgba(31, 35, 40, .15); background: var(--soft); color: var(--ink);
  font-size: 13px; font-weight: 500; font-family: inherit; white-space: nowrap; cursor: pointer;
  transition: background .15s ease;
}
.tk-code-btn:hover:not(:disabled) { background: #f3f4f6; }
.tk-code-btn:disabled { opacity: .55; cursor: not-allowed; }

.tk-btn {
  width: 100%; height: 42px; margin-top: 18px;
  border: 1px solid rgba(31, 35, 40, .15); border-radius: 6px;
  background: var(--green); color: #fff;
  font-size: 14px; font-weight: 600; font-family: inherit;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center; gap: 8px;
  transition: background .15s ease, box-shadow .15s ease;
}
.tk-btn:hover { background: #1a7f37; }
.tk-btn:active { background: #188531; box-shadow: inset 0 1px 2px rgba(0, 0, 0, .15); }
.tk-btn:disabled { opacity: .5; cursor: not-allowed; box-shadow: none; }
.tk-btn .spinner {
  width: 14px; height: 14px; border: 2px solid rgba(255, 255, 255, .4);
  border-top-color: #fff; border-radius: 50%;
  animation: tk-spin .8s linear infinite; display: none;
}
.tk-btn.loading .spinner { display: block; }
.tk-btn.loading .btn-text { display: none; }
@keyframes tk-spin { to { transform: rotate(360deg); } }

.tk-demo { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--line); }
.tk-demo-title {
  margin-bottom: 10px; font-size: 11px; font-weight: 600;
  letter-spacing: .04em; color: var(--ink-4);
}
.tk-chips { display: flex; gap: 8px; }
.tk-chip {
  flex: 1 1 0; min-width: 0; height: 34px; padding: 0 12px;
  display: inline-flex; align-items: center; justify-content: center; gap: 7px;
  background: var(--soft); border: 1px solid rgba(31, 35, 40, .15); border-radius: 6px;
  font-family: inherit; cursor: pointer;
  transition: background .15s ease, border-color .15s ease;
}
.tk-chip:hover { background: #f3f4f6; border-color: rgba(31, 35, 40, .25); }
.dc-name { font-size: 12.5px; font-weight: 600; color: var(--ink); white-space: nowrap; }
.dc-user { font-family: var(--mono); font-size: 11px; color: var(--ink-4); white-space: nowrap; }

.tk-foot {
  position: absolute; bottom: 24px; left: 0; right: 0;
  text-align: center; font-size: 12px; letter-spacing: .02em; color: var(--ink-4);
}

/* ===== 响应式 ===== */
@media (max-width: 1180px) {
  .tk-wrap { display: block; overflow-y: auto; overflow-x: hidden; }
  .tk-brand { gap: 24px; padding: 40px 32px 30px; }
  .tk-feats { max-width: 100%; }
  .tk-auth { padding: 34px 32px 80px; border-left: none; border-top: 1px solid var(--line); }
}
@media (max-width: 640px) {
  .tk-brand { gap: 20px; padding: 26px 20px 20px; }
  .tk-h-sm { font-size: 21px; }
  .tk-h-lg { font-size: 38px; }
  .tk-hero p { font-size: 13.5px; }
  .tk-feats { grid-template-columns: 1fr; }
  .tk-feat { align-items: center; padding: 12px 14px; }
  .tk-feat-desc { display: none; }
  .tk-tech { display: none; }
  .tk-auth { padding: 26px 18px 76px; }
  .tk-card { padding: 24px 22px; }
  .tk-chips { flex-direction: column; }
  .tk-chip { flex: none; }
  .tk-foot { font-size: 11px; }
}
</style>
