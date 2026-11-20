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

    <!-- 主视觉：左文案 + 右侧 RAG 演示窗 -->
    <section class="wl-hero">
      <div class="wl-hero-deco" aria-hidden="true">
        <span class="wl-orb wl-orb-1"></span>
        <span class="wl-orb wl-orb-2"></span>
        <span class="wl-orb wl-orb-3"></span>
        <span class="wl-grid"></span>
      </div>
      <div class="wl-hero-in">
        <div class="wl-hero-copy">
          <div class="wl-eyebrow" data-reveal><span class="wl-eyebrow-dot"></span>AI · RAG · Agnes</div>
          <h1>
            <span class="wl-h-sm" data-reveal style="--d:.06s">用大模型，重新定义</span>
            <span class="wl-h-lg-wrap" data-reveal style="--d:.12s"><span class="wl-h-lg">智能学习</span></span>
          </h1>
          <p data-reveal style="--d:.18s">基于课程知识库的 RAG 检索增强生成，覆盖答疑、生成、练习、批改与学情分析的完整学习闭环。</p>
          <div class="wl-cta-row" data-reveal style="--d:.24s">
            <button type="button" class="wl-btn wl-btn-primary" @click="goPrimary">
              {{ logged ? '进入首页' : '立即开始' }}
              <svg class="wl-arr" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 8h10.5M9.5 4.5 13 8l-3.5 3.5"/></svg>
            </button>
            <button type="button" class="wl-btn wl-btn-secondary" @click="goFeatures">了解功能</button>
          </div>
          <div v-if="!logged" class="wl-hint" data-reveal style="--d:.3s">已有账号？<button type="button" class="wl-link" @click="goLogin">直接登录</button></div>
          <div class="wl-meta" data-reveal style="--d:.34s">
            <span>RAG 检索增强</span><span>流式生成</span><span>自动批改</span><span>引用溯源</span>
          </div>
        </div>

        <div class="wl-hero-demo" data-reveal style="--d:.2s">
          <div class="wl-dm-chip wl-dm-chip-a" aria-hidden="true"><AppIcon name="zap" :size="13" /> 流式响应</div>
          <div class="wl-dm-chip wl-dm-chip-b" aria-hidden="true"><AppIcon name="star" :size="13" /> 引用可溯源</div>
          <div class="wl-dm-card">
            <div class="wl-dm-head">
              <span class="wl-dm-dots" aria-hidden="true"><i></i><i></i><i></i></span>
              <span class="wl-dm-title"><AppIcon name="chat" :size="13" /> 智能答疑 · 课程知识库 RAG</span>
              <span class="wl-dm-live">LIVE</span>
            </div>
            <div class="wl-dm-body">
              <div class="wl-dm-flow" :key="demo.loop" :class="{ out: demo.stage === 'out' }">
                <!-- 用户提问 -->
                <div class="wl-dm-row-user">
                  <div class="wl-dm-bubble-user">{{ demoQ }}</div>
                </div>
                <!-- 检索状态 -->
                <div class="wl-dm-search" v-if="demo.stage === 'search'">
                  <AppIcon name="search" :size="13" class="wl-dm-search-ico" />
                  <span class="wl-dm-search-txt">正在检索课程知识库<i class="wl-dm-tdots" aria-hidden="true"><i>.</i><i>.</i><i>.</i></i></span>
                  <span class="wl-dm-chunks" aria-hidden="true">
                    <i v-for="n in demo.chunks" :key="n" class="wl-dm-chunk"></i>
                  </span>
                </div>
                <div class="wl-dm-search wl-dm-search-done" v-else-if="demo.stage === 'answer' || demo.stage === 'done' || demo.stage === 'out'">
                  <AppIcon name="hub" :size="13" /> 已召回 4 个相关片段 · 重排完成
                </div>
                <!-- AI 回答（流式打字） -->
                <div class="wl-dm-row" v-if="demo.stage === 'answer' || demo.stage === 'done' || demo.stage === 'out'">
                  <div class="wl-dm-ava">智</div>
                  <div class="wl-dm-bubble-ai">
                    <span class="wl-dm-text">{{ demo.typed }}<i v-if="demo.stage === 'answer'" class="wl-dm-caret" aria-hidden="true"></i></span>
                    <div class="wl-dm-srcs" v-if="demo.sources > 0">
                      <span class="wl-dm-src" v-for="(s, i) in demoSources" :key="s" :style="{ '--d': (i * .12) + 's' }">
                        <AppIcon name="notes" :size="12" /> {{ s }}
                      </span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div class="wl-dm-foot">演示画面 · 实际回答由你的课程知识库驱动</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 核心功能 -->
    <section id="wl-features" class="wl-sec">
      <h2 data-reveal>为学习全流程而设计</h2>
      <p class="wl-sec-sub" data-reveal style="--d:.08s">从资料入库到学情反馈，每个环节都有 AI 参与</p>
      <div class="wl-feats">
        <div class="wl-feat" v-for="(f, i) in feats" :key="f.name" data-reveal :style="{ '--d': (i * .06) + 's' }">
          <span class="wl-feat-ico" :class="'wl-ico-' + i"><AppIcon :name="f.icon" :size="17" /></span>
          <div class="wl-feat-name">{{ f.name }}</div>
          <div class="wl-feat-desc">{{ f.desc }}</div>
        </div>
      </div>
    </section>

    <!-- 使用流程 -->
    <section class="wl-sec">
      <h2 data-reveal>四步，形成学习闭环</h2>
      <p class="wl-sec-sub" data-reveal style="--d:.08s">上传、提问、练习、反馈——循环推进你的掌握度</p>
      <div class="wl-steps">
        <div class="wl-step" v-for="(s, i) in steps" :key="s.no" data-reveal :style="{ '--d': (i * .08) + 's' }">
          <span class="wl-step-no">{{ s.no }}</span>
          <div class="wl-step-name">{{ s.name }}</div>
          <div class="wl-step-desc">{{ s.desc }}</div>
        </div>
      </div>
    </section>

    <!-- 行动引导 -->
    <section class="wl-cta-band">
      <div class="wl-cta-card" data-reveal>
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
import { onBeforeUnmount, onMounted, reactive } from 'vue'
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

// ===== RAG 演示窗（循环播放：提问 → 检索 → 流式回答 → 引用） =====
const demoQ = 'RAG 里为什么用余弦相似度？'
const demoA = '因为归一化后余弦相似度只比较向量方向、不受模长影响，能稳定衡量问题与知识片段的语义距离，计算开销也小，适合召回后的粗排与重排。'
const demoSources = ['课件 03 · 向量检索.pdf', '复习笔记.docx']

const demo = reactive({ loop: 0, stage: 'ask', typed: '', chunks: 0, sources: 0 })
let alive = true
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function runDemo() {
  while (alive) {
    demo.loop++; demo.stage = 'ask'; demo.typed = ''; demo.chunks = 0; demo.sources = 0
    await sleep(1000); if (!alive) return
    demo.stage = 'search'
    for (let i = 1; i <= 4; i++) { demo.chunks = i; await sleep(280); if (!alive) return }
    await sleep(420); if (!alive) return
    demo.stage = 'answer'
    for (let i = 1; i <= demoA.length; i++) { demo.typed = demoA.slice(0, i); await sleep(28); if (!alive) return }
    demo.stage = 'done'; demo.sources = demoSources.length
    await sleep(3600); if (!alive) return
    demo.stage = 'out'
    await sleep(480)
  }
}

// ===== 滚动渐入 =====
onMounted(() => {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const els = Array.from(document.querySelectorAll('[data-reveal]'))
  if (reduce || !('IntersectionObserver' in window)) {
    // 降低动效偏好：全部直接显示，演示窗定格在最终画面
    els.forEach((el) => el.classList.add('in'))
    demo.stage = 'done'; demo.typed = demoA; demo.chunks = 4; demo.sources = demoSources.length
    return
  }
  const io = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target) }
    }
  }, { threshold: 0.12, rootMargin: '0px 0px -36px 0px' })
  els.forEach((el) => io.observe(el))
  runDemo()
})
onBeforeUnmount(() => { alive = false })

const goPrimary = () => router.push(logged ? '/home' : '/login')
const goLogin = () => router.push('/login')
const goFeatures = () => document.getElementById('wl-features')?.scrollIntoView({ behavior: 'smooth' })
</script>

<style scoped>
/* ==========================================================================
   设计语言：GitHub Primer Light 风格（与登录页一致）
   白底 · 灰边框 #d1d9e0 · 链接蓝 #0969da · 主按钮绿 #1f883d · 6-8px 圆角
   动效：入场/滚动渐入 · 漂浮光斑 · 渐变标题 · RAG 演示窗循环播放
   ========================================================================== */
.wl-wrap {
  --bg: #ffffff;
  --soft: #f6f8fa;
  --hover: #eaeef2;
  --line: #d1d9e0;
  --ink: #1f2328;
  --ink-2: #57606a;
  --ink-3: #59636e;
  --ink-4: #818b98;
  --blue: #0969da;
  --green: #1f883d;
  --accent-subtle: #ddf4ff;
  --g1: #0969da; --g2: #1a7f37; --g3: #8250df;
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

/* ===== 滚动渐入（IntersectionObserver 加 .in 触发，--d 控制级联延迟） ===== */
[data-reveal]:not(.in) { opacity: 0; }
[data-reveal].in {
  animation: wl-rise .7s cubic-bezier(.22, .61, .36, 1) backwards;
  animation-delay: var(--d, 0s);
}
@keyframes wl-rise {
  from { opacity: 0; transform: translateY(22px); }
  to { opacity: 1; transform: translateY(0); }
}

/* ===== 顶部导航 ===== */
.wl-nav {
  position: sticky; top: 0; z-index: 30;
  background: rgba(255, 255, 255, .88);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
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
  background: linear-gradient(135deg, #24292f, #40484f); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 15px; font-weight: 700;
  box-shadow: 0 2px 8px rgba(31, 35, 40, .22);
}
.wl-logo-text { font-size: 16px; font-weight: 700; letter-spacing: -.01em; }
.wl-nav-ops { display: flex; align-items: center; gap: 10px; }
.wl-gh {
  width: 34px; height: 34px; border-radius: 6px;
  display: flex; align-items: center; justify-content: center;
  color: var(--ink-3); background: var(--bg); border: 1px solid var(--line);
  transition: color .15s ease, background .15s ease, transform .2s ease;
}
.wl-gh:hover { color: var(--ink); background: var(--soft); transform: translateY(-1px); }

/* ===== 按钮 ===== */
.wl-btn {
  height: 42px; padding: 0 20px;
  border-radius: 6px; font-size: 14px; font-weight: 600; font-family: inherit;
  cursor: pointer; white-space: nowrap;
  transition: background .15s ease, border-color .15s ease, box-shadow .2s ease, transform .2s ease;
}
.wl-btn:active { transform: translateY(0); }
.wl-btn-sm { height: 34px; padding: 0 14px; font-size: 13px; }
.wl-btn-primary {
  display: inline-flex; align-items: center; gap: 8px;
  border: 1px solid rgba(31, 35, 40, .15);
  background: var(--green); color: #fff;
}
.wl-btn-primary:hover { background: #1a7f37; box-shadow: 0 6px 18px rgba(31, 136, 61, .3); transform: translateY(-1px); }
.wl-btn-primary:active { background: #188531; box-shadow: inset 0 1px 2px rgba(0, 0, 0, .15); }
.wl-arr { transition: transform .25s ease; }
.wl-btn-primary:hover .wl-arr { transform: translateX(3px); }
.wl-btn-secondary {
  border: 1px solid rgba(31, 35, 40, .15);
  background: var(--soft); color: var(--ink);
}
.wl-btn-secondary:hover { background: var(--hover); border-color: rgba(31, 35, 40, .25); transform: translateY(-1px); }

/* ===== 主视觉 ===== */
.wl-hero { position: relative; overflow: hidden; }
.wl-hero-in {
  position: relative; z-index: 1;
  max-width: 1120px; margin: 0 auto;
  padding: 64px 24px 56px;
  display: grid; grid-template-columns: 1.02fr .98fr;
  gap: 48px; align-items: center;
}
.wl-hero-copy { text-align: left; }

/* 背景装饰：漂浮光斑 + 点阵网格 */
.wl-hero-deco { position: absolute; inset: 0; pointer-events: none; }
.wl-orb { position: absolute; border-radius: 50%; filter: blur(70px); opacity: .55; will-change: transform; }
.wl-orb-1 { width: 420px; height: 420px; left: -120px; top: -80px; background: radial-gradient(circle at 30% 30%, rgba(9, 105, 218, .16), transparent 65%); animation: wl-drift-1 22s ease-in-out infinite alternate; }
.wl-orb-2 { width: 380px; height: 380px; right: -100px; top: 10px; background: radial-gradient(circle at 60% 40%, rgba(31, 136, 61, .14), transparent 65%); animation: wl-drift-2 26s ease-in-out infinite alternate; }
.wl-orb-3 { width: 300px; height: 300px; right: 24%; bottom: -140px; background: radial-gradient(circle at 50% 50%, rgba(130, 80, 223, .12), transparent 65%); animation: wl-drift-3 30s ease-in-out infinite alternate; }
@keyframes wl-drift-1 { to { transform: translate(70px, 50px) scale(1.18); } }
@keyframes wl-drift-2 { to { transform: translate(-60px, 60px) scale(1.12); } }
@keyframes wl-drift-3 { to { transform: translate(50px, -50px) scale(1.2); } }
.wl-grid {
  position: absolute; inset: 0;
  background-image: radial-gradient(circle, rgba(31, 35, 40, .10) 1px, transparent 1px);
  background-size: 26px 26px;
  -webkit-mask-image: radial-gradient(ellipse 70% 60% at 50% 35%, #000 30%, transparent 75%);
  mask-image: radial-gradient(ellipse 70% 60% at 50% 35%, #000 30%, transparent 75%);
}

.wl-eyebrow {
  display: inline-flex; align-items: center; gap: 8px;
  margin-bottom: 20px;
  padding: 4px 12px; border-radius: 999px;
  background: var(--soft); border: 1px solid var(--line);
  font-family: var(--mono); font-size: 11px; font-weight: 500;
  letter-spacing: .14em; text-transform: uppercase; color: var(--ink-3);
}
.wl-eyebrow-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--green); animation: wl-pulse 1.8s ease-in-out infinite; }
.wl-hero h1 { line-height: 1.1; }
.wl-h-sm {
  display: block; font-size: clamp(20px, 2.4vw, 28px); font-weight: 400;
  letter-spacing: -.01em; color: var(--ink-3);
}
.wl-h-lg-wrap { display: block; margin-top: 6px; }
.wl-h-lg {
  display: inline-block;
  font-size: clamp(40px, 5.4vw, 68px); font-weight: 700;
  letter-spacing: -.03em;
  background: linear-gradient(90deg, var(--g1), var(--g2), var(--g3), var(--g1));
  background-size: 300% 100%;
  -webkit-background-clip: text; background-clip: text;
  -webkit-text-fill-color: transparent; color: transparent;
  animation: wl-hue 9s ease-in-out infinite;
}
@keyframes wl-hue { 0%, 100% { background-position: 0% 50%; } 50% { background-position: 100% 50%; } }
.wl-hero-copy p { max-width: 480px; margin: 18px 0 0; font-size: 16px; line-height: 1.75; color: var(--ink-2); }
.wl-cta-row { margin-top: 26px; display: flex; align-items: center; justify-content: flex-start; gap: 12px; flex-wrap: wrap; }
.wl-hint { margin-top: 16px; font-size: 13px; color: var(--ink-3); }
.wl-link {
  border: none; background: none; padding: 0;
  font-family: inherit; font-size: 13px; font-weight: 500;
  color: var(--blue); cursor: pointer;
}
.wl-link:hover { text-decoration: underline; }
.wl-meta {
  margin-top: 26px; display: flex; flex-wrap: wrap; gap: 6px 0; align-items: center;
  font-family: var(--mono); font-size: 11.5px; color: var(--ink-4); letter-spacing: .04em;
}
.wl-meta span { display: inline-flex; align-items: center; }
.wl-meta span + span::before { content: ""; display: inline-block; width: 3px; height: 3px; border-radius: 50%; background: currentColor; opacity: .55; margin: 0 10px; }

/* ===== RAG 演示窗 ===== */
.wl-hero-demo { position: relative; }
.wl-dm-card {
  position: relative; z-index: 1;
  border-radius: 12px; overflow: hidden;
  background: rgba(255, 255, 255, .86);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  border: 1px solid var(--line);
  box-shadow: 0 24px 60px -24px rgba(31, 35, 40, .25), 0 4px 16px rgba(31, 35, 40, .05);
}
.wl-dm-head {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--soft);
}
.wl-dm-dots { display: inline-flex; gap: 5px; }
.wl-dm-dots i { width: 9px; height: 9px; border-radius: 50%; }
.wl-dm-dots i:nth-child(1) { background: #ff5f57; }
.wl-dm-dots i:nth-child(2) { background: #febc2e; }
.wl-dm-dots i:nth-child(3) { background: #28c840; }
.wl-dm-title {
  flex: 1; display: inline-flex; align-items: center; gap: 6px;
  font-family: var(--mono); font-size: 11.5px; color: var(--ink-3); letter-spacing: .02em;
  white-space: nowrap; overflow: hidden;
}
.wl-dm-live {
  display: inline-flex; align-items: center; gap: 5px;
  font-family: var(--mono); font-size: 9.5px; font-weight: 600; letter-spacing: .1em;
  color: var(--green); border: 1px solid rgba(31, 136, 61, .4); border-radius: 999px;
  padding: 2px 8px;
}
.wl-dm-live::before { content: ""; width: 6px; height: 6px; border-radius: 50%; background: var(--green); animation: wl-pulse 1.6s ease-in-out infinite; }
.wl-dm-body { padding: 16px; min-height: 228px; }
.wl-dm-foot {
  padding: 9px 14px; border-top: 1px solid var(--line);
  font-family: var(--mono); font-size: 10.5px; color: var(--ink-4); letter-spacing: .04em;
  text-align: center;
}
.wl-dm-flow { display: flex; flex-direction: column; gap: 12px; transition: opacity .45s ease; }
.wl-dm-flow.out { opacity: 0; }

.wl-dm-row-user { display: flex; justify-content: flex-end; }
.wl-dm-bubble-user {
  max-width: 85%;
  padding: 9px 14px; border-radius: 12px 12px 4px 12px;
  background: var(--accent-subtle); border: 1px solid rgba(9, 105, 218, .3);
  font-size: 13px; font-weight: 500; line-height: 1.55;
  animation: wl-in-r .45s cubic-bezier(.22, .61, .36, 1) both;
}
.wl-dm-row { display: flex; gap: 10px; align-items: flex-start; animation: wl-in .4s ease both; }
.wl-dm-ava {
  flex-shrink: 0; width: 26px; height: 26px; border-radius: 7px;
  background: linear-gradient(135deg, #1f883d, #26a148); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 700;
}
.wl-dm-bubble-ai {
  flex: 1; min-width: 0;
  padding: 10px 14px; border-radius: 4px 12px 12px 12px;
  background: #fff; border: 1px solid var(--line);
  font-size: 13px; line-height: 1.7; color: var(--ink);
}
.wl-dm-caret { display: inline-block; width: 2px; height: 14px; margin-left: 2px; background: var(--blue); vertical-align: -2px; animation: wl-blink 1s step-end infinite; }
.wl-dm-srcs { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.wl-dm-src {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 9px; border-radius: 999px;
  background: var(--soft); border: 1px solid var(--line);
  font-size: 11px; color: var(--ink-2);
  animation: wl-pop .3s cubic-bezier(.34, 1.56, .64, 1) both;
  animation-delay: var(--d, 0s);
}

.wl-dm-search {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; border-radius: 8px;
  background: var(--soft); border: 1px dashed var(--line);
  font-size: 12px; color: var(--ink-3);
  animation: wl-in .35s ease both;
}
.wl-dm-search-ico { color: var(--blue); animation: wl-look 1.1s ease-in-out infinite; }
.wl-dm-search-done { border-style: solid; color: var(--green); }
.wl-dm-search-done :deep(svg) { flex-shrink: 0; }
.wl-dm-search-txt { display: inline-flex; }
.wl-dm-tdots i { font-style: normal; animation: wl-tdot 1.2s infinite; }
.wl-dm-tdots i:nth-child(2) { animation-delay: .2s; }
.wl-dm-tdots i:nth-child(3) { animation-delay: .4s; }
.wl-dm-chunks { display: inline-flex; gap: 4px; margin-left: auto; }
.wl-dm-chunk { width: 26px; height: 10px; border-radius: 3px; background: var(--accent-subtle); border: 1px solid rgba(9, 105, 218, .3); animation: wl-pop .3s cubic-bezier(.34, 1.56, .64, 1) both; }
.wl-dm-chunk:nth-child(1) { width: 30px; }
.wl-dm-chunk:nth-child(3) { width: 20px; }

/* 演示窗漂浮徽章 */
.wl-dm-chip {
  position: absolute; z-index: 2;
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 12px; border-radius: 999px;
  background: rgba(255, 255, 255, .92); border: 1px solid var(--line);
  font-size: 12px; font-weight: 600;
  box-shadow: 0 8px 20px rgba(31, 35, 40, .12);
  animation: wl-bob 5s ease-in-out infinite;
}
.wl-dm-chip-a { top: -18px; right: -14px; color: var(--blue); animation-delay: .6s; }
.wl-dm-chip-b { bottom: 26px; left: -14px; color: var(--green); }

@keyframes wl-in { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
@keyframes wl-in-r { from { opacity: 0; transform: translateX(24px); } to { opacity: 1; transform: none; } }
@keyframes wl-pop { from { opacity: 0; transform: scale(.6); } to { opacity: 1; transform: scale(1); } }
@keyframes wl-blink { 50% { opacity: 0; } }
@keyframes wl-tdot { 0%, 60%, 100% { opacity: .2; } 30% { opacity: 1; } }
@keyframes wl-look { 0%, 100% { transform: translateX(0); } 25% { transform: translateX(2px); } 75% { transform: translateX(-2px); } }
@keyframes wl-pulse { 0%, 100% { opacity: 1; box-shadow: 0 0 0 0 rgba(31, 136, 61, .35); } 50% { opacity: .75; box-shadow: 0 0 0 4px rgba(31, 136, 61, 0); } }
@keyframes wl-bob { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-7px); } }

/* ===== 通用分区 ===== */
.wl-sec { max-width: 1120px; margin: 0 auto; padding: 48px 24px; }
.wl-sec h2 {
  position: relative;
  text-align: center; font-size: 28px; font-weight: 600; letter-spacing: -.02em;
  padding-bottom: 14px;
}
.wl-sec h2::after {
  content: ""; position: absolute; left: 50%; bottom: 0;
  width: 44px; height: 3px; border-radius: 3px;
  transform: translateX(-50%);
  background: linear-gradient(90deg, var(--blue), var(--green));
}
.wl-sec-sub { margin-top: 10px; text-align: center; font-size: 14px; color: var(--ink-3); }

/* ===== 核心功能 ===== */
.wl-feats { margin-top: 36px; display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.wl-feat {
  padding: 20px; border-radius: 8px;
  background: #fff; border: 1px solid var(--line);
  transition: transform .25s ease, box-shadow .25s ease, border-color .25s ease, background .15s ease;
}
.wl-feat:hover { background: var(--soft); border-color: #b7c0cb; transform: translateY(-3px); box-shadow: 0 10px 26px rgba(31, 35, 40, .09); }
.wl-feat-ico {
  width: 36px; height: 36px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  margin-bottom: 12px;
  background: var(--accent-subtle); color: var(--blue);
  transition: transform .25s cubic-bezier(.34, 1.56, .64, 1);
}
.wl-feat:hover .wl-feat-ico { transform: scale(1.12) rotate(-4deg); }
.wl-ico-0 { background: #ddf4ff; color: #0969da; }
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
  position: relative; overflow: hidden;
  padding: 18px; border-radius: 8px;
  background: var(--soft); border: 1px solid var(--line);
  transition: transform .25s ease, box-shadow .25s ease, border-color .25s ease;
}
.wl-step:hover { transform: translateY(-3px); border-color: #b7c0cb; box-shadow: 0 10px 26px rgba(31, 35, 40, .09); }
/* 进度条：卡片渐入后从左向右生长，呼应“闭环推进” */
.wl-step::after {
  content: ""; position: absolute; left: 18px; right: 18px; bottom: 0;
  height: 2px; border-radius: 2px;
  background: linear-gradient(90deg, var(--blue), var(--green));
  transform: scaleX(0); transform-origin: left;
  transition: transform .9s cubic-bezier(.22, .61, .36, 1) .3s;
}
.wl-step.in::after { transform: scaleX(1); }
.wl-step-no {
  font-family: var(--mono); font-size: 12px; font-weight: 600;
  color: var(--blue);
}
.wl-step-name { margin-top: 10px; font-size: 14.5px; font-weight: 600; }
.wl-step-desc { margin-top: 4px; font-size: 12.5px; line-height: 1.6; color: var(--ink-3); }

/* ===== 行动引导 ===== */
.wl-cta-band { max-width: 1120px; margin: 0 auto; padding: 24px 24px 72px; }
.wl-cta-card {
  position: relative; overflow: hidden;
  padding: 44px 32px; border-radius: 12px;
  background: linear-gradient(135deg, #f0fff6 0%, #ffffff 46%, #eff5ff 100%);
  border: 1px solid var(--line);
  text-align: center;
}
/* 缓慢扫过的光泽 */
.wl-cta-card::after {
  content: ""; position: absolute; top: 0; bottom: 0; width: 38%; left: -55%;
  background: linear-gradient(105deg, transparent, rgba(255, 255, 255, .55), transparent);
  transform: skewX(-12deg);
  animation: wl-sheen 6s ease-in-out infinite;
  pointer-events: none;
}
@keyframes wl-sheen { 0% { left: -55%; } 55%, 100% { left: 135%; } }
.wl-cta-card h3 { font-size: 22px; font-weight: 600; letter-spacing: -.02em; }
.wl-cta-card p { margin: 8px 0 22px; font-size: 14px; color: var(--ink-2); }
.wl-cta-card .wl-btn-primary { position: relative; z-index: 1; }

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
  .wl-hero-in { grid-template-columns: 1fr; gap: 40px; padding: 52px 24px 48px; }
  .wl-hero-copy { text-align: center; }
  .wl-hero-copy p { margin-left: auto; margin-right: auto; }
  .wl-cta-row { justify-content: center; }
  .wl-hero-demo { max-width: 560px; width: 100%; margin: 0 auto; }
  .wl-feats { grid-template-columns: repeat(2, 1fr); }
  .wl-steps { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 640px) {
  .wl-dm-chip { display: none; }
}
@media (max-width: 600px) {
  .wl-hero-in { padding: 44px 20px 40px; }
  .wl-h-lg { font-size: 38px; }
  .wl-hero-copy p { font-size: 14px; }
  .wl-dm-body { min-height: 252px; padding: 14px; }
  .wl-sec { padding: 36px 20px; }
  .wl-feats, .wl-steps { grid-template-columns: 1fr; }
  .wl-cta-band { padding: 16px 20px 56px; }
  .wl-cta-card { padding: 32px 20px; }
}

/* ===== 深色模式 ===== */
html.dark .wl-wrap {
  --bg: #0d1117; --soft: #161b22; --hover: #21262d; --line: #3d444d;
  --ink: #e6edf3; --ink-2: #9198a1; --ink-3: #9198a1; --ink-4: #6e7681;
  --blue: #4493f8; --green: #238636;
  --accent-subtle: rgba(56, 139, 253, .15);
  --g1: #4493f8; --g2: #3fb950; --g3: #d2a8ff;
}
html.dark .wl-nav { background: rgba(13, 17, 23, .88); }
html.dark .wl-btn-secondary:hover { background: var(--hover); border-color: rgba(240, 246, 252, .2); }
html.dark .wl-btn-primary { border-color: transparent; }
html.dark .wl-grid { background-image: radial-gradient(circle, rgba(230, 237, 243, .09) 1px, transparent 1px); }
html.dark .wl-orb-1 { background: radial-gradient(circle at 30% 30%, rgba(56, 139, 253, .2), transparent 65%); }
html.dark .wl-orb-2 { background: radial-gradient(circle at 60% 40%, rgba(63, 185, 80, .16), transparent 65%); }
html.dark .wl-orb-3 { background: radial-gradient(circle at 50% 50%, rgba(163, 113, 247, .18), transparent 65%); }
html.dark .wl-dm-card { background: rgba(22, 27, 34, .85); box-shadow: 0 24px 60px -24px rgba(0, 0, 0, .6), 0 4px 16px rgba(0, 0, 0, .4); }
html.dark .wl-dm-bubble-ai { background: #0d1117; }
html.dark .wl-dm-bubble-user { background: rgba(56, 139, 253, .14); border-color: rgba(56, 139, 253, .4); }
html.dark .wl-dm-chunk { background: rgba(56, 139, 253, .12); border-color: rgba(56, 139, 253, .4); }
html.dark .wl-dm-chip { background: rgba(22, 27, 34, .92); }
html.dark .wl-feat { background: var(--soft); }
html.dark .wl-feat:hover { background: #1c2128; border-color: #6e7681; }
html.dark .wl-ico-0 { background: rgba(56, 139, 253, .16); color: #4493f8; }
html.dark .wl-ico-1 { background: rgba(63, 185, 80, .16); color: #3fb950; }
html.dark .wl-ico-2 { background: rgba(210, 153, 34, .16); color: #d29922; }
html.dark .wl-ico-3 { background: rgba(248, 81, 73, .16); color: #f85149; }
html.dark .wl-ico-4 { background: rgba(163, 113, 247, .16); color: #d2a8ff; }
html.dark .wl-ico-5 { background: rgba(187, 128, 9, .2); color: #e3b341; }
html.dark .wl-cta-card { background: linear-gradient(135deg, rgba(63, 185, 80, .08) 0%, rgba(13, 17, 23, 0) 46%, rgba(56, 139, 253, .1) 100%); }
html.dark .wl-cta-card::after { background: linear-gradient(105deg, transparent, rgba(255, 255, 255, .06), transparent); }

/* ===== 降低动效偏好 ===== */
@media (prefers-reduced-motion: reduce) {
  .wl-wrap *, .wl-wrap *::before, .wl-wrap *::after {
    animation-duration: .01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: .01ms !important;
  }
  [data-reveal] { opacity: 1 !important; transform: none !important; }
}
</style>
