<template>
  <div class="tk-wrap">
    <!-- 背景装饰：马卡龙柔光 + 点阵 -->
    <div class="tk-blob tk-blob-a"></div>
    <div class="tk-blob tk-blob-b"></div>
    <div class="tk-dots"></div>

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
        <div class="tk-eyebrow">AI · RAG · DeepSeek</div>
        <h1>
          <span class="tk-h-sm">用大模型</span>
          <span class="tk-h-sm">重新定义</span>
          <span class="tk-h-lg">智能学习</span>
        </h1>
        <p>基于 RAG 检索增强生成，覆盖答疑、生成、批改与学情的完整学习闭环。</p>
      </div>

      <div class="tk-feats">
        <div class="tk-feat" v-for="(f, i) in feats" :key="f.name" :class="'c' + i">
          <span class="tk-feat-ico"><AppIcon :name="f.icon" :size="15" /></span>
          <div class="tk-feat-body">
            <div class="tk-feat-name">{{ f.name }}</div>
            <div class="tk-feat-desc">{{ f.desc }}</div>
          </div>
        </div>
      </div>

      <div class="tk-tech">Java 业务骨架 · Python langchain AI 服务 · DeepSeek</div>
    </section>

    <!-- 右：登录 / 注册 -->
    <section class="tk-auth">
      <div class="tk-card">
        <div class="tk-tabs">
          <button type="button" :class="{ on: mode === 'login' }" @click="switchMode('login')">登 录</button>
          <button type="button" :class="{ on: mode === 'register' }" @click="switchMode('register')">注 册</button>
        </div>

        <h2>{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
        <p class="tk-sub">{{ mode === 'login' ? '登录你的智学助手账号' : '注册一个新账号开始学习' }}</p>

        <form @submit.prevent="submit">
          <label class="tk-label" for="username">用户名</label>
          <input id="username" v-model="form.username" type="text" class="tk-input" placeholder="请输入用户名" autocomplete="username" />
          <label class="tk-label" for="password" style="margin-top:14px">密码</label>
          <input id="password" v-model="form.password" type="password" class="tk-input" placeholder="请输入密码" autocomplete="current-password" />
          <div v-if="error" class="tk-error">{{ error }}</div>
          <button type="submit" class="tk-btn" :class="{ loading }" :disabled="loading">
            <span class="spinner"></span>
            <span class="btn-text">{{ mode === 'login' ? '登 录' : '注 册' }}</span>
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
/* ==========================================================================
   设计语言：Teak 风格
   底色 #F8FAF7 · 墨色 #2A0F14 · 主按钮品红 #FF4EAF · 卡片 20px 圆角
   按钮/标签/胶囊一律 999px，马卡龙色块（粉/黄/紫/蓝）做信息分区
   ========================================================================== */
.tk-wrap {
  --tk-bg: #f8faf7;
  --tk-card: #ffffff;
  --tk-soft: #f0f2ef;
  --tk-line: #dfe0de;
  --tk-ink: #2a0f14;
  --tk-ink-2: #544145;
  --tk-ink-3: #766e70;
  --tk-ink-4: rgba(42, 15, 20, 0.34);
  --tk-pink: #ff4eaf;
  --tk-pink-dark: #e8389a;
  --tk-plum: #4b003a;
  --tk-pink-pale: #ffdcef;
  --tk-yellow-pale: #fdf1b0;
  --tk-violet-pale: #ebe5ff;
  --tk-blue-pale: #e5f9ff;
  --tk-danger: #991a00;
  --tk-mono: "Fragment Mono", "Haffer Mono", ui-monospace, SFMono-Regular, Consolas, monospace;

  position: fixed;
  inset: 0;
  z-index: 200;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 480px;
  background: var(--tk-bg);
  color: var(--tk-ink);
  font-family: "Haffer", "Inter", -apple-system, BlinkMacSystemFont, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  overflow: hidden;
  -webkit-font-smoothing: antialiased;
}

/* 覆盖 index.html 里的全局金色焦点环 */
.tk-wrap button:focus-visible,
.tk-wrap a:focus-visible,
.tk-wrap input:focus-visible { outline: 2px solid var(--tk-pink); outline-offset: 2px; }

/* ===== 背景装饰 ===== */
.tk-blob { position: absolute; border-radius: 50%; filter: blur(72px); pointer-events: none; }
.tk-blob-a { width: 540px; height: 540px; top: -230px; right: -140px; background: var(--tk-pink-pale); opacity: .75; }
.tk-blob-b { width: 460px; height: 460px; bottom: -240px; left: 14%; background: var(--tk-yellow-pale); opacity: .6; }
.tk-dots {
  position: absolute; inset: 0; pointer-events: none;
  background-image: radial-gradient(rgba(42, 15, 20, 0.07) 1px, transparent 1px);
  background-size: 26px 26px;
  -webkit-mask-image: linear-gradient(158deg, #000 0%, rgba(0, 0, 0, .35) 38%, transparent 66%);
  mask-image: linear-gradient(158deg, #000 0%, rgba(0, 0, 0, .35) 38%, transparent 66%);
}

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
  background: var(--tk-ink); color: var(--tk-bg);
  display: flex; align-items: center; justify-content: center;
  font-size: 17px; font-weight: 800;
}
.tk-logo-text { font-size: 17px; font-weight: 700; letter-spacing: -0.02em; color: var(--tk-ink); }
.tk-gh {
  width: 38px; height: 38px; flex: none; border-radius: 999px;
  display: flex; align-items: center; justify-content: center;
  color: var(--tk-ink-3); background: var(--tk-card); border: 1px solid var(--tk-line);
  transition: color .2s ease, border-color .2s ease, transform .2s ease;
}
.tk-gh:hover { color: var(--tk-ink); border-color: var(--tk-ink); transform: translateY(-1px); }

.tk-eyebrow {
  display: inline-block; margin-bottom: 22px;
  padding: 6px 14px; border-radius: 999px;
  background: var(--tk-card); border: 1px solid var(--tk-line);
  font-family: var(--tk-mono); font-size: 11px; font-weight: 500;
  letter-spacing: .16em; text-transform: uppercase; color: var(--tk-ink-3);
}
.tk-hero h1 { line-height: 1.06; }
.tk-h-sm {
  display: block; font-size: clamp(23px, 2.3vw, 33px); font-weight: 500;
  letter-spacing: -0.02em; color: var(--tk-ink-4);
}
.tk-h-lg {
  display: block; margin-top: 2px;
  font-size: clamp(44px, 5.2vw, 74px); font-weight: 900;
  letter-spacing: -0.045em; color: var(--tk-ink);
}
.tk-h-lg::after {
  content: ''; display: block; width: 68px; height: 7px; margin-top: 15px;
  border-radius: 999px; background: var(--tk-pink);
}
.tk-hero p { margin-top: 20px; max-width: 470px; font-size: 15px; line-height: 1.75; color: var(--tk-ink-2); }

.tk-feats { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; max-width: 640px; }
.tk-feat {
  display: flex; gap: 12px; align-items: flex-start;
  padding: 16px; border-radius: 20px;
  border: 1px solid rgba(42, 15, 20, 0.05);
}
.tk-feat.c0 { background: var(--tk-pink-pale); }
.tk-feat.c1 { background: var(--tk-yellow-pale); }
.tk-feat.c2 { background: var(--tk-violet-pale); }
.tk-feat.c3 { background: var(--tk-blue-pale); }
.tk-feat-ico {
  width: 32px; height: 32px; flex: none; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  background: rgba(255, 255, 255, 0.82); color: var(--tk-ink);
}
.tk-feat-name { font-size: 14px; font-weight: 700; letter-spacing: -0.01em; color: var(--tk-ink); margin-bottom: 3px; }
.tk-feat-desc { font-size: 12.5px; line-height: 1.55; color: rgba(42, 15, 20, 0.6); }

.tk-tech {
  font-family: var(--tk-mono); font-size: 11px; letter-spacing: .08em;
  color: var(--tk-ink-4);
}

/* ===== 右侧登录 / 注册 ===== */
.tk-auth {
  position: relative; z-index: 1;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 48px 44px 78px;
}
.tk-card {
  width: 100%; max-width: 396px;
  background: var(--tk-card); border: 1px solid var(--tk-line); border-radius: 20px;
  padding: 30px 32px 30px;
  box-shadow: 0 1px 2px rgba(42, 15, 20, .04), 0 22px 56px rgba(42, 15, 20, .07);
}

.tk-tabs {
  display: flex; gap: 4px; padding: 4px;
  background: var(--tk-soft); border-radius: 999px; margin-bottom: 26px;
}
.tk-tabs button {
  flex: 1; height: 38px; border: none; border-radius: 999px;
  background: transparent; color: var(--tk-ink-3);
  font-size: 13.5px; font-weight: 600; font-family: inherit;
  cursor: pointer; transition: color .2s ease, background .2s ease, box-shadow .2s ease;
}
.tk-tabs button:hover { color: var(--tk-ink); }
.tk-tabs button.on {
  background: var(--tk-card); color: var(--tk-ink); font-weight: 700;
  box-shadow: 0 1px 2px rgba(42, 15, 20, .06), 0 4px 12px rgba(42, 15, 20, .06);
}

.tk-card h2 { font-size: 26px; font-weight: 800; letter-spacing: -0.03em; color: var(--tk-ink); }
.tk-sub { margin: 6px 0 24px; font-size: 13.5px; color: var(--tk-ink-3); }

.tk-label {
  display: block; margin-bottom: 7px;
  font-size: 11px; font-weight: 700; letter-spacing: .08em;
  text-transform: uppercase; color: var(--tk-ink-2);
}
.tk-input {
  width: 100%; height: 48px; padding: 0 16px;
  background: var(--tk-bg); border: 1.5px solid var(--tk-line); border-radius: 14px;
  color: var(--tk-ink); font-size: 14px; font-family: inherit; outline: none;
  transition: border-color .2s ease, background .2s ease, box-shadow .2s ease;
}
.tk-input::placeholder { color: var(--tk-ink-4); }
.tk-input:hover { border-color: #c9cbc7; }
.tk-input:focus { background: var(--tk-card); border-color: var(--tk-pink); box-shadow: 0 0 0 4px rgba(255, 78, 175, .14); }

.tk-error {
  margin: 14px 0 2px; padding: 10px 14px; border-radius: 14px;
  font-size: 13px; line-height: 1.6;
  color: var(--tk-danger); background: rgba(153, 26, 0, .07);
  border: 1px solid rgba(153, 26, 0, .18);
}

.tk-btn {
  width: 100%; height: 50px; margin-top: 18px;
  border: none; border-radius: 999px;
  background: var(--tk-pink); color: #fff;
  font-size: 15px; font-weight: 700; letter-spacing: .04em; font-family: inherit;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center; gap: 8px;
  box-shadow: 0 1px 2px rgba(255, 78, 175, .3), inset 0 1px 0 rgba(255, 255, 255, .22);
  transition: background .18s ease, box-shadow .18s ease, transform .12s ease;
}
.tk-btn:hover { background: var(--tk-pink-dark); transform: translateY(-1px); box-shadow: 0 2px 6px rgba(255, 78, 175, .3), 0 12px 28px rgba(255, 78, 175, .28); }
.tk-btn:active { transform: translateY(0); box-shadow: 0 1px 2px rgba(255, 78, 175, .4); }
.tk-btn:disabled { opacity: .5; cursor: not-allowed; transform: none; box-shadow: none; }
.tk-btn .spinner {
  width: 16px; height: 16px; border: 2px solid rgba(255, 255, 255, .35);
  border-top-color: #fff; border-radius: 50%;
  animation: tk-spin .8s linear infinite; display: none;
}
.tk-btn.loading .spinner { display: block; }
.tk-btn.loading .btn-text { display: none; }
@keyframes tk-spin { to { transform: rotate(360deg); } }

.tk-demo { margin-top: 26px; padding-top: 20px; border-top: 1px dashed var(--tk-line); }
.tk-demo-title {
  margin-bottom: 12px; font-size: 11px; font-weight: 700;
  letter-spacing: .06em; color: var(--tk-ink-4);
}
.tk-chips { display: flex; gap: 8px; }
.tk-chip {
  flex: 1 1 0; min-width: 0; height: 38px; padding: 0 12px;
  display: inline-flex; align-items: center; justify-content: center; gap: 7px;
  background: var(--tk-soft); border: 1px solid transparent; border-radius: 999px;
  font-family: inherit; cursor: pointer;
  transition: background .18s ease, border-color .18s ease, transform .18s ease;
}
.tk-chip:hover { background: var(--tk-pink-pale); border-color: var(--tk-pink); transform: translateY(-1px); }
.dc-name { font-size: 12.5px; font-weight: 700; color: var(--tk-ink); white-space: nowrap; }
.dc-user { font-family: var(--tk-mono); font-size: 11px; color: var(--tk-ink-4); white-space: nowrap; }
.tk-chip:hover .dc-user { color: var(--tk-plum); }

.tk-foot {
  position: absolute; bottom: 24px; left: 0; right: 0;
  text-align: center; font-size: 12px; letter-spacing: .02em; color: var(--tk-ink-4);
}

/* ===== 响应式 ===== */
@media (max-width: 1180px) {
  .tk-wrap { display: block; overflow-y: auto; }
  .tk-brand { gap: 24px; padding: 40px 32px 30px; }
  .tk-feats { max-width: 100%; }
  .tk-auth { padding: 34px 32px 80px; }
}
@media (max-width: 640px) {
  .tk-brand { gap: 20px; padding: 26px 20px 20px; }
  .tk-h-sm { font-size: 22px; }
  .tk-h-lg { font-size: 40px; }
  .tk-h-lg::after { width: 48px; height: 5px; margin-top: 12px; }
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
