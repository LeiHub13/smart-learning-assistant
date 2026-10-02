<template>
  <div>
    <div class="page-title">闪卡</div>
    <div class="page-sub">问对式主动记忆：翻卡自评，记住升盒、没记住回炉；支持导出到 Anki</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <div class="row" style="justify-content:space-between;margin-bottom:8px">
        <h3 style="margin:0">生成闪卡 <span class="muted small">共 {{ total }} 张</span></h3>
        <button class="btn ghost small" :disabled="total === 0" @click="exportAnki">导出到 Anki</button>
      </div>
      <div class="row" style="margin-bottom:4px">
        <button class="btn ghost small" :disabled="genLoading" @click="gen('mistake')">从错题本生成</button>
        <select v-model="docId" style="max-width:260px">
          <option :value="null" disabled>选择文档…</option>
          <option v-for="d in docList" :key="d.docId" :value="d.docId">{{ d.fileName }}</option>
        </select>
        <button class="btn ghost small" :disabled="genLoading || !docId" @click="gen('doc')">从该文档生成</button>
      </div>
      <div class="muted small">答疑页工具栏里也可以把当前会话一键提炼成闪卡</div>
    </div>

    <div class="card">
      <div class="row" style="justify-content:space-between;margin-bottom:10px">
        <h3 style="margin:0">学习队列
          <span class="tag">{{ queue.length }} 张</span>
          <span class="tag" :class="due ? 'bad' : 'ok'">今日到期 {{ due }} 张</span>
        </h3>
        <button class="btn ghost small" :disabled="loading" @click="loadQueue">刷新队列</button>
      </div>

      <template v-if="queue.length && current">
        <div v-if="!revealed" class="fc-card" @click="revealed = true">
          <div class="fc-tag">{{ sourceLabel(current.source) }} · 盒 {{ current.box }} · {{ dueLabel(current) }}</div>
          <div class="fc-front">{{ current.front }}</div>
          <div class="fc-hint">点击卡片显示答案</div>
        </div>
        <div v-else class="fc-card open">
          <div class="fc-tag">{{ sourceLabel(current.source) }} · 盒 {{ current.box }} · {{ dueLabel(current) }}</div>
          <div class="fc-front">{{ current.front }}</div>
          <div class="fc-divider"></div>
          <div class="fc-back">{{ current.back }}</div>
        </div>
        <div class="fc-ops">
          <template v-if="!revealed">
            <button class="btn" @click="revealed = true">显示答案</button>
          </template>
          <template v-else>
            <button class="btn danger" @click="grade('miss')">😅 没记住</button>
            <button class="btn accent" @click="grade('ok')">✓ 记住了</button>
          </template>
        </div>
        <div class="muted small" style="text-align:center">
          第 {{ Math.min(idx + 1, queue.length) }} / {{ queue.length }} 张 · 记住升盒、没记住回盒 1{{ lastHint ? ' · ' + lastHint : '' }}
        </div>
      </template>
      <div v-else class="empty">
        {{ loading ? '加载中…' : (idx >= queue.length && queue.length === 0 && finished ? '本轮完成！刷新队列继续，或再生成一批' : '队列为空——先从上面生成一些闪卡吧') }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api, downloadFile } from '../api'

defineOptions({ name: 'FlashcardsView' })

const queue = ref([])
const idx = ref(0)
const revealed = ref(false)
const total = ref(0)
const due = ref(0)
const lastHint = ref('')
const docList = ref([])
const docId = ref(null)
const genLoading = ref(false)
const loading = ref(false)
const finished = ref(false)
const error = ref('')

const current = computed(() => queue.value[idx.value] || null)
const sourceLabel = (s) => ({ mistake: '错题', doc: '文档', chat: '答疑' }[s] || s)

// 卡片到期状态：到期（或未排期的存量卡）显示「今日到期」，未到期显示下次复习日期
const dueLabel = (c) => {
  if (!c.dueAt) return '今日到期'
  const d = new Date(c.dueAt)
  const now = new Date()
  if (d <= now) return '今日到期'
  const p = (x) => String(x).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} 复习`
}
// 与后端 BOX_INTERVAL_DAYS 对齐：盒 1-5 答对后的下次间隔（天）
const BOX_DAYS = [1, 2, 4, 7, 15]

const loadQueue = async () => {
  loading.value = true
  try {
    queue.value = await api('/api/flashcards/study?count=10')
    idx.value = 0
    revealed.value = false
    finished.value = false
    lastHint.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const loadDue = async () => {
  try {
    const r = await api('/api/flashcards/due')
    due.value = r.due || 0
  } catch (e) { /* 静默 */ }
}

const loadTotal = async () => {
  try {
    total.value = await api('/api/flashcards/count')
  } catch (e) { /* 静默 */ }
}

const loadDocs = async () => {
  try {
    docList.value = await api('/api/kb/docs')
  } catch (e) { /* 静默 */ }
}

const grade = async (result) => {
  const card = current.value
  if (!card) return
  try {
    const updated = await api('/api/flashcards/' + card.id + '/review', { method: 'POST', body: { result } })
    // 排期反馈：与后端 Leitner 间隔一致（记住按盒序拉长，没记住 10 分钟后重现）
    lastHint.value = result === 'ok'
      ? `已排期：${BOX_DAYS[Math.max((updated.box || 1) - 1, 0)]} 天后再复习`
      : '已排期：10 分钟后重现'
    queue.value.splice(idx.value, 1)
    revealed.value = false
    if (!queue.value.length) {
      finished.value = true
      loadTotal()
    }
    loadDue()
  } catch (e) {
    error.value = e.message
  }
}

const gen = async (mode) => {
  if (genLoading.value) return
  if (mode === 'doc' && !docId.value) return
  genLoading.value = true
  error.value = ''
  try {
    const r = await api('/api/flashcards/generate', {
      method: 'POST',
      body: mode === 'doc' ? { mode, sourceId: docId.value } : { mode }
    })
    error.value = ''
    showDone(r.created)
    await Promise.all([loadQueue(), loadTotal()])
  } catch (e) {
    error.value = e.message
  } finally {
    genLoading.value = false
  }
}

let doneTimer = null
const showDone = (n) => {
  error.value = ''
  clearTimeout(doneTimer)
  doneTimer = setTimeout(() => { if (error.value.startsWith('已生成')) error.value = '' }, 3000)
  error.value = '已生成 ' + n + ' 张闪卡'
}

const exportAnki = () => {
  downloadFile('/api/flashcards/export', 'flashcards-anki.txt')
}

onMounted(async () => {
  await Promise.all([loadQueue(), loadTotal(), loadDocs(), loadDue()])
})
</script>

<style scoped>
.fc-card {
  background: var(--card); border: 2px solid var(--border); border-radius: 14px;
  padding: 26px 28px; min-height: 190px; cursor: pointer;
  transition: border-color .2s ease, box-shadow .2s ease;
}
.fc-card:hover { border-color: var(--primary); box-shadow: 0 4px 18px rgba(9,105,218,.10); }
.fc-card.open { cursor: default; border-color: var(--primary); }
.fc-tag { font-size: 11px; font-weight: 700; color: var(--muted); margin-bottom: 12px; }
.fc-front { font-size: 16px; font-weight: 700; line-height: 1.8; }
.fc-back { font-size: 14px; line-height: 1.9; color: var(--ok-strong); white-space: pre-wrap; }
.fc-hint { margin-top: 18px; font-size: 12px; color: var(--muted); }
.fc-divider { border-top: 1px dashed var(--border); margin: 14px 0; }
.fc-ops { display: flex; justify-content: center; gap: 12px; margin-top: 14px; }
.u-pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 10px; }
</style>
