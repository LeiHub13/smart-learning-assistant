<template>
  <div class="wl-wrap">
    <!-- 顶部导航 -->
    <header class="wl-nav">
      <div class="wl-nav-in">
        <div class="wl-logo">
          <div class="wl-logo-mark">智</div>
          <div class="wl-logo-text">智学助手</div>
        </div>
        <div class="wl-nav-ops">
          <a class="wl-gh" href="https://github.com/LeiHub13/smart-learning-assistant" target="_blank" rel="noopener" title="GitHub" aria-label="GitHub">
            <svg viewBox="0 0 24 24" width="17" height="17" fill="currentColor"><path d="M12 2C6.48 2 2 6.58 2 12.26c0 4.52 2.87 8.35 6.84 9.71.5.1.68-.22.68-.49 0-.24-.01-.87-.01-1.71-2.78.62-3.37-1.37-3.37-1.37-.45-1.18-1.11-1.5-1.11-1.5-.91-.64.07-.63.07-.63 1 .07 1.53 1.06 1.53 1.06.9 1.57 2.36 1.12 2.94.86.09-.67.35-1.12.63-1.38-2.22-.26-4.56-1.14-4.56-5.07 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.7 0 0 .84-.27 2.75 1.05A9.3 9.3 0 0 1 12 6.84c.85.01 1.71.12 2.51.34 1.9-1.32 2.74-1.05 2.74-1.05.56 1.4.21 2.44.1 2.7.64.72 1.03 1.63 1.03 2.75 0 3.94-2.34 4.8-4.57 5.06.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.6.69.49A10.04 10.04 0 0 0 22 12.26C22 6.58 17.52 2 12 2z"/></svg>
          </a>
          <button type="button" class="wl-btn wl-btn-primary wl-btn-sm" @click="goPrimary">
            {{ logged ? '进入首页' : '登录 / 注册' }}
          </button>
        </div>
      </div>
    </header>

    <!-- 主视觉 -->
    <section class="wl-hero">
      <div class="wl-eyebrow">AI · RAG · Agnes</div>
      <h1>
        <span class="wl-h-sm">用大模型，重新定义</span>
        <span class="wl-h-lg">智能学习</span>
      </h1>
      <p>基于课程知识库的 RAG 检索增强生成，覆盖答疑、生成、练习、批改与学情分析的完整学习闭环。</p>
      <div class="wl-cta-row">
        <button type="button" class="wl-btn wl-btn-primary" @click="goPrimary">{{ logged ? '进入首页' : '立即开始' }}</button>
        <button type="button" class="wl-btn wl-btn-secondary" @click="goFeatures">了解功能</button>
      </div>
      <div v-if="!logged" class="wl-hint">已有账号？<button type="button" class="wl-link" @click="goLogin">直接登录</button></div>
    </section>

    <!-- 核心功能 -->
    <section id="wl-features" class="wl-sec">
      <h2>为学习全流程而设计</h2>
      <p class="wl-sec-sub">从资料入库到学情反馈，每个环节都有 AI 参与</p>
      <div class="wl-feats">
        <div class="wl-feat" v-for="(f, i) in feats" :key="f.name">
          <span class="wl-feat-ico" :class="'wl-ico-' + i"><AppIcon :name="f.icon" :size="17" /></span>
          <div class="wl-feat-name">{{ f.name }}</div>
          <div class="wl-feat-desc">{{ f.desc }}</div>
        </div>
      </div>
    </section>

    <!-- 使用流程 -->
    <section class="wl-sec">
      <h2>四步，形成学习闭环</h2>
      <p class="wl-sec-sub">上传、提问、练习、反馈——循环推进你的掌握度</p>
      <div class="wl-steps">
        <div class="wl-step" v-for="s in steps" :key="s.no">
          <span class="wl-step-no">{{ s.no }}</span>
          <div class="wl-step-name">{{ s.name }}</div>
          <div class="wl-step-desc">{{ s.desc }}</div>
        </div>
      </div>
    </section>

    <!-- 行动引导 -->
    <section class="wl-cta-band">
      <div class="wl-cta-card">
        <h3>准备好了吗？</h3>
        <p>选择课程知识库，开始你的第一次智能提问</p>
        <button type="button" class="wl-btn wl-btn-primary" @click="goPrimary">{{ logged ? '进入首页' : '免费开始使用' }}</button>
      </div>
    </section>

    <footer class="wl-foot">
      <div class="wl-tech">Java 业务骨架 · Python langchain AI 服务 · Agnes</div>
      <div>© {{ year }} 智学助手 · 让每一次学习都有反馈</div>
    </footer>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { getToken } from '../api'
import AppIcon from '../components/AppIcon.vue'

const router = useRouter()
const logged = !!getToken()
const year = new Date().getFullYear()

const feats = [
  { icon: 'chat', name: '智能答疑', desc: '基于课程知识库 RAG 检索回答，答案标注引用来源' },
  { icon: 'sparkles', name: 'AI 生成讲义/练习题', desc: '一键流式生成讲义，生成的练习题自动入库' },
  { icon: 'practice', name: '题库练习', desc: '客观题自动判分，主观题由 AI 点评解析' },
  { icon: 'target', name: '错题本', desc: '错题自动归集，配合收藏夹针对性强化薄弱点' },
  { icon: 'exam', name: '在线考试', desc: '限时组卷作答，成绩曲线持续追踪' },
  { icon: 'progress', name: '学情与规划', desc: '掌握度画像、学习报告与个性化学习计划' },
]

const steps = [
  { no: '01', name: '上传资料', desc: '上传课件与文档，自动解析分块并建立向量索引' },
  { no: '02', name: '知识库提问', desc: 'RAG 检索增强回答，答案附引用来源可溯源' },
  { no: '03', name: '练习巩固', desc: 'AI 生成讲义与练习题，作答后自动批改归集错题' },
  { no: '04', name: '学情反馈', desc: '掌握度追踪与学习报告，生成个性化学习计划' },
]

const goPrimary = () => router.push(logged ? '/home' : '/login')
const goLogin = () => router.push('/login')
const goFeatures = () => document.getElementById('wl-features')?.scrollIntoView({ behavior: 'smooth' })
</script>

<style scoped>
/* ==========================================================================
   设计语言：GitHub Primer Light 风格（与登录页一致）
   白底 · 灰边框 #d1d9e0 · 链接蓝 #0969da · 主按钮绿 #1f883d · 6-8px 圆角
   ========================================================================== */
.wl-wrap {
  --bg: #ffffff;
  --soft: #f6f8fa;
  --line: #d1d9e0;
  --ink: #1f2328;
  --ink-2: #57606a;
  --ink-3: #59636e;
  --ink-4: #818b98;
  --blue: #0969da;
  --green: #1f883d;
  --mono: ui-monospace, SFMono-Regular, "SF Mono", Consolas, monospace;

  min-height: 100vh;
  /* #app 是全局 flex 容器：不撑开的话页面会被收缩成内容宽度的窄栏 */
  width: 100%;
  flex: 1 0 auto;
  background: var(--bg);
  color: var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans", Helvetica, Arial, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
  -webkit-font-smoothing: antialiased;
}

.wl-wrap button:focus-visible,
.wl-wrap a:focus-visible { outline: 2px solid var(--blue); outline-offset: 2px; }

/* ===== 顶部导航 ===== */
.wl-nav {
  position: sticky; top: 0; z-index: 30;
  background: rgba(255, 255, 255, .94);
  border-bottom: 1px solid var(--line);
}
.wl-nav-in {
  max-width: 1120px; margin: 0 auto; height: 60px;
  padding: 0 24px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
}
.wl-logo { display: flex; align-items: center; gap: 10px; }
.wl-logo-mark {
  width: 32px; height: 32px; border-radius: 7px;
  background: #24292f; color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 15px; font-weight: 700;
}
.wl-logo-text { font-size: 16px; font-weight: 700; letter-spacing: -.01em; }
.wl-nav-ops { display: flex; align-items: center; gap: 10px; }
.wl-gh {
  width: 34px; height: 34px; border-radius: 6px;
  display: flex; align-items: center; justify-content: center;
  color: var(--ink-3); background: #fff; border: 1px solid var(--line);
  transition: color .15s ease, background .15s ease;
}
.wl-gh:hover { color: var(--ink); background: var(--soft); }

/* ===== 按钮 ===== */
.wl-btn {
  height: 42px; padding: 0 20px;
  border-radius: 6px; font-size: 14px; font-weight: 600; font-family: inherit;
  cursor: pointer; white-space: nowrap;
  transition: background .15s ease, border-color .15s ease, box-shadow .15s ease;
}
.wl-btn-sm { height: 34px; padding: 0 14px; font-size: 13px; }
.wl-btn-primary {
  border: 1px solid rgba(31, 35, 40, .15);
  background: var(--green); color: #fff;
}
.wl-btn-primary:hover { background: #1a7f37; }
.wl-btn-primary:active { background: #188531; box-shadow: inset 0 1px 2px rgba(0, 0, 0, .15); }
.wl-btn-secondary {
  border: 1px solid rgba(31, 35, 40, .15);
  background: var(--soft); color: var(--ink);
}
.wl-btn-secondary:hover { background: #f3f4f6; border-color: rgba(31, 35, 40, .25); }

/* ===== 主视觉 ===== */
.wl-hero { max-width: 1120px; margin: 0 auto; padding: 84px 24px 60px; text-align: center; }
.wl-eyebrow {
  display: inline-block; margin-bottom: 24px;
  padding: 3px 12px; border-radius: 999px;
  background: var(--soft); border: 1px solid var(--line);
  font-family: var(--mono); font-size: 11px; font-weight: 500;
  letter-spacing: .14em; text-transform: uppercase; color: var(--ink-3);
}
.wl-hero h1 { line-height: 1.1; }
.wl-h-sm {
  display: block; font-size: clamp(22px, 2.6vw, 30px); font-weight: 400;
  letter-spacing: -.01em; color: var(--ink-3);
}
.wl-h-lg {
  display: block; margin-top: 4px;
  font-size: clamp(44px, 6vw, 76px); font-weight: 700;
  letter-spacing: -.03em; color: var(--ink);
}
.wl-hero p { max-width: 560px; margin: 20px auto 0; font-size: 16px; line-height: 1.75; color: var(--ink-2); }
.wl-cta-row { margin-top: 30px; display: flex; align-items: center; justify-content: center; gap: 12px; flex-wrap: wrap; }
.wl-hint { margin-top: 16px; font-size: 13px; color: var(--ink-3); }
.wl-link {
  border: none; background: none; padding: 0;
  font-family: inherit; font-size: 13px; font-weight: 500;
  color: var(--blue); cursor: pointer;
}
.wl-link:hover { text-decoration: underline; }

/* ===== 通用分区 ===== */
.wl-sec { max-width: 1120px; margin: 0 auto; padding: 48px 24px; }
.wl-sec h2 { text-align: center; font-size: 28px; font-weight: 600; letter-spacing: -.02em; }
.wl-sec-sub { margin-top: 8px; text-align: center; font-size: 14px; color: var(--ink-3); }

/* ===== 核心功能 ===== */
.wl-feats { margin-top: 36px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.wl-feat {
  padding: 20px; border-radius: 8px;
  background: #fff; border: 1px solid var(--line);
  transition: background .15s ease, border-color .15s ease;
}
.wl-feat:hover { background: var(--soft); border-color: #b7c0cb; }
.wl-feat-ico {
  width: 36px; height: 36px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  margin-bottom: 12px;
  background: #ddf4ff; color: var(--blue);
}
.wl-ico-1 { background: #dafbe1; color: #1a7f37; }
.wl-ico-2 { background: #fff1e5; color: #bc4c00; }
.wl-ico-3 { background: #ffebe9; color: #cf222e; }
.wl-ico-4 { background: #fbefff; color: #8250df; }
.wl-ico-5 { background: #fff8c5; color: #9a6700; }
.wl-feat-name { font-size: 15px; font-weight: 600; margin-bottom: 5px; }
.wl-feat-desc { font-size: 13px; line-height: 1.6; color: var(--ink-2); }

/* ===== 使用流程 ===== */
.wl-steps { margin-top: 36px; display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.wl-step {
  padding: 18px; border-radius: 8px;
  background: var(--soft); border: 1px solid var(--line);
}
.wl-step-no {
  font-family: var(--mono); font-size: 12px; font-weight: 600;
  color: var(--blue);
}
.wl-step-name { margin-top: 10px; font-size: 14.5px; font-weight: 600; }
.wl-step-desc { margin-top: 4px; font-size: 12.5px; line-height: 1.6; color: var(--ink-3); }

/* ===== 行动引导 ===== */
.wl-cta-band { max-width: 1120px; margin: 0 auto; padding: 24px 24px 72px; }
.wl-cta-card {
  padding: 44px 32px; border-radius: 12px;
  background: var(--soft); border: 1px solid var(--line);
  text-align: center;
}
.wl-cta-card h3 { font-size: 22px; font-weight: 600; letter-spacing: -.02em; }
.wl-cta-card p { margin: 8px 0 22px; font-size: 14px; color: var(--ink-2); }

/* ===== 页脚 ===== */
.wl-foot {
  border-top: 1px solid var(--line);
  padding: 22px 24px; text-align: center;
  font-size: 12px; color: var(--ink-4);
}
.wl-tech {
  font-family: var(--mono); font-size: 11px; letter-spacing: .06em;
  margin-bottom: 6px;
}

/* ===== 响应式 ===== */
@media (max-width: 900px) {
  .wl-feats { grid-template-columns: repeat(2, 1fr); }
  .wl-steps { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 600px) {
  .wl-hero { padding: 56px 20px 44px; }
  .wl-h-lg { font-size: 40px; }
  .wl-hero p { font-size: 14px; }
  .wl-sec { padding: 36px 20px; }
  .wl-feats, .wl-steps { grid-template-columns: 1fr; }
  .wl-cta-band { padding: 16px 20px 56px; }
  .wl-cta-card { padding: 32px 20px; }
}
</style>
