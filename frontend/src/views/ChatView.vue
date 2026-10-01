<template>
  <div class="chat-wrap">
    <div class="side card">
      <h3>会话</h3>
      <button class="btn small" style="margin:8px 0" @click="onNewSession">+ 新建会话</button>
      <div v-if="hint" style="margin-bottom:8px;font-size:12px;color:var(--warn)">{{ hint }}</div>
      <div class="mode-tabs">
        <button :class="{ on: mode === 'kb' }" @click="setMode('kb')">知识库答疑</button>
        <button :class="{ on: mode === 'free' }" @click="setMode('free')">自由对话</button>
      </div>
      <template v-if="mode === 'kb'">
        <span class="label">课程</span>
        <select v-model="courseId" @change="loadKb">
          <option :value="null" disabled>请选择课程</option>
          <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <span class="label" style="margin-top:8px">知识库</span>
        <select v-model="kbId" @change="onKbChange">
          <option :value="null" disabled>请选择知识库</option>
          <option v-for="kb in kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
        <span class="label" style="margin-top:8px">检索范围</span>
        <select v-model="kbScope" @change="onScopeChange">
          <option value="single">仅选中的知识库</option>
          <option value="course">本课程全部知识库</option>
        </select>
      </template>
      <div style="flex:1;overflow:auto;margin-top:10px">
        <div v-for="s in filteredSessions" :key="s.id" class="sess" :class="{ on: s.id === sessionId }" @click="openSession(s.id)">
          <input v-if="renamingId === s.id" v-model="renameVal" class="sess-input"
                 @click.stop @keyup.enter="doRename(s)" @keyup.esc="cancelRename" @blur="doRename(s)" />
          <template v-else>
            <span class="sess-title">{{ s.title }}</span>
            <span class="sess-mode-tag">{{ s.feynman ? '费曼' : (s.socratic ? '引导' : (s.kbId ? '知识库' : '自由')) }}</span>
            <span class="sess-ops" @click.stop>
              <button class="sess-btn" title="重命名" @click="startRename(s)">✎</button>
              <button class="sess-btn" title="删除" @click="removeSession(s)">✕</button>
            </span>
          </template>
        </div>
        <div v-if="!filteredSessions.length" class="empty">暂无会话</div>
      </div>
    </div>

    <div class="chatbox">
      <div class="chat-toolbar">
        <button class="btn ghost small" :disabled="streaming || !sessionId" @click="genFlashcards">生成闪卡</button>
        <label class="socratic-toggle" title="开启后 AI 不直接给答案，拆步骤反问引导你思考">
          <input type="checkbox" :checked="socratic" @change="toggleSocratic" />
          苏格拉底引导
        </label>
        <label class="socratic-toggle" title="费曼技巧：角色互换，你把概念讲给 AI 听，AI 扮演不懂的学生追问；说「讲完了」可得讲解评分">
          <input type="checkbox" :checked="feynman" @change="toggleFeynman" />
          费曼讲解
        </label>
        <template v-if="socratic">
          <span class="socratic-hint">引导模式：AI 逐步反问，不直接给答案</span>
          <a class="link" style="margin-left:auto;font-size:12px" @click="askDirect">卡住了？直接讲解</a>
        </template>
        <span v-if="feynman" class="socratic-hint">费曼模式：你讲 AI 问，讲完后说「讲完了」可得评分</span>
      </div>
      <div class="msgs" ref="bodyRef">
        <div v-if="!messages.length" class="empty">
          <div style="font-size:16px;font-weight:600;margin-bottom:8px">你好，我是 AI 学习助手</div>
          <div style="margin:10px 0 16px">
            {{ mode === 'kb' ? '选择课程知识库后提问，回答将基于资料并标注引用' : '学习问题随时问我' }}
          </div>
        </div>
        <div v-for="(m, i) in messages" :key="i" class="m" :class="m.role === 'user' ? 'me' : 'ai'">
          <div class="av">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="bub">
            <div v-if="streaming && i === messages.length - 1" class="stream-txt">{{ m.content }}<span class="cursor"></span></div>
            <div v-else-if="m.role === 'user'">{{ m.content }}</div>
            <div v-else v-html="mdToHtml(m.content)"></div>
            <div v-if="m.sources && m.role === 'assistant' && !(streaming && i === messages.length - 1)" class="sources">
              <template v-if="m.sourceChunks">
                <span>引用来源：</span>
                <a v-for="(cid, si) in m.sourceChunks.split(',')" :key="si"
                   class="src-chip" @click="jumpSource(m, si + 1, $event)">[{{ si + 1 }}]</a>
              </template>
              <template v-else>引用来源：知识库片段 {{ m.sources.split(',').join('、') }}</template>
            </div>
            <div v-if="m.showSources && m.sourceChunks" class="src-panel">
              <div class="src-panel-head">
                <span>引用原文</span>
                <button class="src-close" @click="closeSources(m)">收起</button>
              </div>
              <div v-for="(d, di) in m.sourceDetails || []" :key="di" class="src-item"
                   :class="{ flash: m.flashSrc === di + 1 }">
                <div class="src-doc">[{{ di + 1 }}] 《{{ d.docName }}》</div>
                <div class="src-txt">{{ d.content }}</div>
              </div>
            </div>
            <!-- Agent 待确认动作：只有点击「确认执行」后 Java 侧才真正写库 -->
            <div v-for="a in m.actions || []" :key="a.id" class="agent-action">
              <div v-if="a.state === 'done'" class="sources">{{ a.result }}</div>
              <template v-else>
                <div class="agent-action-summary">{{ a.summary }}</div>
                <div class="agent-action-ops">
                  <button class="btn small" :disabled="a.busy" @click="confirmAction(m, a)">确认执行</button>
                  <button class="btn ghost small" :disabled="a.busy" @click="cancelAction(m, a)">取消</button>
                </div>
              </template>
            </div>
            <!-- 追问推荐：回答结束后自动给出 2~3 个可继续问的问题，点一下直接提问 -->
            <div v-if="m.role === 'assistant' && m.followups && m.followups.length && !(streaming && i === messages.length - 1)"
                 class="followups">
              <span class="fu-label">继续问</span>
              <button v-for="(q, qi) in m.followups" :key="qi" class="fu-chip" :title="q" @click="send(q)">{{ q }}</button>
            </div>
          </div>
        </div>
      </div>
      <div class="input-bar">
        <textarea v-model="input"
                  :placeholder="feynman ? '用你的话把概念讲给 AI 听…（费曼模式，说「讲完了」可得评分）'
                    : (socratic ? '输入你的思考或回答…（引导模式）' : '输入问题，Enter 发送')"
                  :disabled="streaming"
                  @keydown.enter.exact.prevent="send()"></textarea>
        <button class="btn" :disabled="streaming || !input.trim()" @click="send()">
          {{ streaming ? '生成中…' : '发送' }}
        </button>
      </div>
    </div>

    <div v-if="confirmDel" class="modal-mask" @click.self="confirmDel = null">
      <div class="modal-box">
        <h3>删除会话</h3>
        <p>确定删除会话「{{ confirmDel.title }}」吗？该会话的聊天记录将一并删除，且无法恢复。</p>
        <div class="modal-ops">
          <button class="btn ghost small" @click="confirmDel = null">取消</button>
          <button class="btn danger small" @click="doDelete">删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api, sseStream, getCourses } from '../api'
import { mdToHtml } from '../utils'

defineOptions({ name: 'ChatView' })

const router = useRouter()
const route = useRoute()

const courses = ref([])
const kbs = ref([])
const sessions = ref([])
const sessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const mode = ref('kb')
const socratic = ref(false)
const feynman = ref(false)
const courseId = ref(null)
const kbId = ref(null)
const kbScope = ref('single')
const bodyRef = ref(null)

let raf = null
const scrollDown = () => {
  if (raf) return
  raf = requestAnimationFrame(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
    raf = null
  })
}

// 进行中的流：sid=流所属会话，target=正在被流式填充的 assistant 消息对象（响应式代理）。
// 切到别的会话时生成仍在后台继续，target 引用保证增量只写进归属会话的那条回复，
// 不会污染当前打开的别的会话的消息列表；切回来时按它重新挂载（见 openSession）。
const activeStream = { sid: null, target: null }

const sessionMode = (s) => (s && s.kbId ? 'kb' : 'free')
const filteredSessions = computed(() => sessions.value.filter((s) => sessionMode(s) === mode.value))

onMounted(async () => {
  courses.value = await getCourses()
  const list = await api('/api/chat/sessions')
  sessions.value = list
  // 知识图谱节点跳转进来：绑定课程/知识库后围绕该概念自动发起提问，清掉 query 防刷新重复发送
  const ask = typeof route.query.ask === 'string' ? route.query.ask.trim() : ''
  if (ask && await askFromGraph(ask)) {
    router.replace({ path: '/chat' })
    return
  }
  if (list.length) await openSession(list[0].id)
})

/* 图谱跳转提问：校验并绑定 query 里的课程/知识库，复用既有会话或新建后自动发送；返回是否成功受理 */
const askFromGraph = async (ask) => {
  const cid = Number(route.query.courseId)
  const kid = Number(route.query.kbId)
  if (!courses.value.some((c) => c.id === cid)) {
    return false
  }
  mode.value = 'kb'
  courseId.value = cid
  kbScope.value = 'single'
  kbs.value = await api('/api/courses/' + cid + '/kb').catch(() => [])
  if (!kbs.value.some((k) => k.id === kid)) {
    return false
  }
  kbId.value = kid
  const exist = sessions.value.find((s) => s.kbId === kid && s.courseId === cid)
  if (exist) {
    await openSession(exist.id)
  } else {
    await newSession()
  }
  const rel = typeof route.query.rel === 'string'
    ? route.query.rel.split(',').map((s) => s.trim()).filter(Boolean).slice(0, 4) : []
  send(rel.length
    ? `请结合知识库资料讲解「${ask}」这个概念：它的定义与要点，以及它与「${rel.join('」「')}」等概念的关系。`
    : `请结合知识库资料讲解「${ask}」这个概念的定义与要点。`)
  return true
}

const setMode = async (m) => {
  if (mode.value === m) return
  mode.value = m
  if (m === 'kb') {
    courseId.value = null
    kbId.value = null
    kbs.value = []
    kbScope.value = 'single'
  }
  // 当前会话与新模式不匹配时，清空当前会话，让用户重新选择或新建
  const current = sessions.value.find((x) => x.id === sessionId.value)
  if (current && sessionMode(current) !== m) {
    sessionId.value = null
    messages.value = []
  }
}

const loadKb = async () => {
  kbId.value = null
  kbs.value = courseId.value ? await api('/api/courses/' + courseId.value + '/kb') : []
}

const onKbChange = async () => {
  if (!kbId.value) return
  const exist = sessions.value.find((s) => s.kbId === kbId.value && s.courseId === courseId.value)
  if (exist) {
    await openSession(exist.id)
  } else {
    await newSession()
  }
}

const newSession = async () => {
  const body = mode.value === 'kb' ? { courseId: courseId.value, kbId: kbId.value, kbScope: kbScope.value } : {}
  const s = await api('/api/chat/sessions', { method: 'POST', body })
  sessions.value.unshift(s)
  await openSession(s.id)
}

const onScopeChange = async () => {
  if (!sessionId.value) return
  try {
    const up = await api('/api/chat/sessions/' + sessionId.value, { method: 'PUT', body: { kbScope: kbScope.value } })
    const s = sessions.value.find((x) => x.id === sessionId.value)
    if (s) s.kbScope = up.kbScope
  } catch (e) {
    showHint(e.message)
  }
}

/* ===== 苏格拉底引导模式：会话级开关，开启后 AI 逐步反问不直接给答案 ===== */
const toggleSocratic = async () => {
  if (!sessionId.value) {
    showHint('先选择或新建会话，再开启引导模式')
    return
  }
  const next = !socratic.value
  try {
    // 与费曼模式互斥：开引导时显式关掉费曼
    const body = next ? { socratic: true, feynman: false } : { socratic: false }
    const up = await api('/api/chat/sessions/' + sessionId.value, { method: 'PUT', body })
    socratic.value = !!up.socratic
    feynman.value = !!up.feynman
    const s = sessions.value.find((x) => x.id === sessionId.value)
    if (s) { s.socratic = !!up.socratic; s.feynman = !!up.feynman }
  } catch (e) {
    showHint(e.message)
  }
}

/* ===== 费曼讲解模式：角色互换，用户讲 AI 追问，说「讲完了」给评分 ===== */
const toggleFeynman = async () => {
  if (!sessionId.value) {
    showHint('先选择或新建会话，再开启费曼模式')
    return
  }
  const next = !feynman.value
  try {
    // 与引导模式互斥：开费曼时显式关掉引导
    const body = next ? { feynman: true, socratic: false } : { feynman: false }
    const up = await api('/api/chat/sessions/' + sessionId.value, { method: 'PUT', body })
    feynman.value = !!up.feynman
    socratic.value = !!up.socratic
    const s = sessions.value.find((x) => x.id === sessionId.value)
    if (s) { s.feynman = !!up.feynman; s.socratic = !!up.socratic }
  } catch (e) {
    showHint(e.message)
  }
}

const askDirect = () => {
  if (streaming.value) return
  input.value = '我卡住了，请直接给我讲解'
  send()
}

let hintTimer = null
const hint = ref('')
const showHint = (msg) => {
  hint.value = msg
  clearTimeout(hintTimer)
  hintTimer = setTimeout(() => (hint.value = ''), 2500)
}

const onNewSession = async () => {
  if (sessionId.value && !messages.value.length) {
    showHint('当前会话还没有对话，无需新建')
    return
  }
  if (mode.value === 'kb' && !kbId.value) {
    showHint('请先选择课程和知识库，再新建会话')
    return
  }
  await newSession()
}

const renamingId = ref(null)
const renameVal = ref('')

const startRename = (s) => {
  renamingId.value = s.id
  renameVal.value = s.title
}

const cancelRename = () => {
  renamingId.value = null
}

const doRename = async (s) => {
  if (renamingId.value !== s.id) return
  renamingId.value = null
  const t = renameVal.value.trim()
  if (!t || t === s.title) return
  try {
    const up = await api('/api/chat/sessions/' + s.id, { method: 'PUT', body: { title: t } })
    s.title = up.title
  } catch (e) {
    showHint(e.message)
  }
}

const confirmDel = ref(null)

const removeSession = (s) => {
  confirmDel.value = s
}

const doDelete = async () => {
  const s = confirmDel.value
  confirmDel.value = null
  if (!s) return
  try {
    await api('/api/chat/sessions/' + s.id, { method: 'DELETE' })
    sessions.value = sessions.value.filter((x) => x.id !== s.id)
    if (sessionId.value === s.id) {
      sessionId.value = null
      messages.value = []
      if (sessions.value.length) await openSession(sessions.value[0].id)
    }
  } catch (e) {
    showHint(e.message)
  }
}

const openSession = async (id) => {
  sessionId.value = id
  // followups 在库里是 JSON 数组串，统一解析成字符串数组供渲染
  messages.value = (await api('/api/chat/sessions/' + id + '/messages'))
    .map((m) => ({ ...m, followups: parseFollowups(m.followups) }))
  // 这个会话的回复正在后台流式生成：把进行中的那条回复重新挂回列表（带上已生成的部分），
  // 后续增量继续写进它。否则切走再切回时只能看到提问、看不到正在生成的回复。
  if (activeStream.sid === id && activeStream.target) {
    const seeded = {
      role: 'assistant',
      content: activeStream.target.content,
      sources: activeStream.target.sources || '',
      sourceChunks: activeStream.target.sourceChunks || '',
      followups: activeStream.target.followups || [],
      actions: activeStream.target.actions || []
    }
    messages.value.push(seeded)
    activeStream.target = messages.value[messages.value.length - 1]
    scrollDown()
  }
  // 服务端仍在有效期内的待确认动作要在重进会话时重现：否则卡片一刷新就"消失"，
  // 但动作还在，用户既确认不了也取消不了。挂在最后一条 assistant 消息下作为锚点。
  try {
    const pending = await api('/api/agent/actions?sessionId=' + id)
    if (pending && pending.length) {
      const last = [...messages.value].reverse().find((m) => m.role === 'assistant')
      if (last) last.actions = [...(last.actions || []), ...pending]
    }
  } catch (e) { /* 待确认列表拉不到不影响读历史 */ }
  const s = sessions.value.find((x) => x.id === id)
  if (s) {
    mode.value = sessionMode(s)
    socratic.value = !!s.socratic
    feynman.value = !!s.feynman
    if (mode.value === 'kb') {
      courseId.value = s.courseId || null
      kbId.value = s.kbId || null
      kbScope.value = s.kbScope || 'single'
      if (courseId.value) {
        try { kbs.value = await api('/api/courses/' + courseId.value + '/kb') } catch (e) { kbs.value = [] }
      }
    } else {
      courseId.value = null
      kbId.value = null
      kbs.value = []
    }
  }
  scrollDown()
}

/* 追问推荐：done 事件与历史消息中的 followups 均为 JSON 数组串，统一解析为字符串数组 */
const parseFollowups = (raw) => {
  try {
    const arr = JSON.parse(raw || '[]')
    return Array.isArray(arr) ? arr.filter((s) => typeof s === 'string' && s.trim()).slice(0, 3) : []
  } catch {
    return []
  }
}

const send = async (textArg) => {
  // 追问 chips 直接传问题文本发起提问；其余入口（输入框/回车/直接讲解）不带参，走输入框内容
  const text = (typeof textArg === 'string' ? textArg : input.value).trim()
  if (!text || streaming.value) return
  if (mode.value === 'kb' && !kbId.value) {
    showHint('请先选择知识库再提问')
    return
  }
  if (!sessionId.value) await newSession()
  const sid = sessionId.value
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '' })
  activeStream.sid = sid
  activeStream.target = messages.value[messages.value.length - 1]
  input.value = ''
  streaming.value = true
  scrollDown()
  try {
    await sseStream('/api/chat/sessions/' + sid + '/stream', { message: text },
      (delta) => {
        if (activeStream.target) activeStream.target.content += delta
        if (sessionId.value === sid) scrollDown()
      },
      (done) => {
        if (activeStream.target) {
          activeStream.target.sources = (done && done.sources) || ''
          activeStream.target.sourceChunks = (done && done.sourceChunks) || ''
          activeStream.target.followups = parseFollowups(done && done.followups)
          activeStream.target.actions = (done && done.actions) || []
        }
      })
  } catch (e) {
    if (activeStream.target) activeStream.target.content = '（请求失败：' + e.message + '）'
  } finally {
    streaming.value = false
    activeStream.sid = null
    activeStream.target = null
    sessions.value = await api('/api/chat/sessions')
    if (sessionId.value === sid) scrollDown()
  }
}

/* 一键把当前会话提炼成闪卡 */
const genFlashcards = async () => {
  if (!sessionId.value) {
    showHint('先选择或新建会话')
    return
  }
  try {
    const r = await api('/api/flashcards/generate', { method: 'POST', body: { mode: 'chat', sourceId: sessionId.value } })
    showHint('已生成 ' + r.created + ' 张闪卡，去「闪卡」页复习')
  } catch (e) {
    showHint(e.message)
  }
}

/* ===== 引用跳转：点 [n] 展开来源原文面板并高亮定位（chunk 原文懒加载，按消息缓存）；
   面板可经「收起」按钮或再次点击当前编号关闭 ===== */
const jumpSource = async (m, n, ev) => {
  // 当前正开着的同一编号再点一次 = 收起
  if (m.showSources && m.openSrc === n) {
    closeSources(m)
    return
  }
  m.openSrc = n
  m.showSources = true
  m.flashSrc = n
  if (!m.sourceDetails) {
    try {
      m.sourceDetails = await api('/api/kb/chunks?ids=' + m.sourceChunks)
    } catch (e) {
      showHint('引用原文加载失败：' + e.message)
      closeSources(m)
      return
    }
  }
  nextTick(() => {
    // 从点击处向上找所在气泡，避免多条消息同时展开面板时定位到别家
    const bub = ev && ev.target ? ev.target.closest('.bub') : null
    const item = bub && bub.querySelectorAll('.src-item')[n - 1]
    if (item) item.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
  setTimeout(() => { if (m.flashSrc === n) m.flashSrc = 0 }, 1800)
}

const closeSources = (m) => {
  m.showSources = false
  m.openSrc = 0
  m.flashSrc = 0
}

const confirmAction = async (m, a) => {
  if (a.busy) return
  a.busy = true
  try {
    const r = await api('/api/agent/actions/' + a.id + '/confirm', { method: 'POST' })
    a.state = 'done'
    a.result = r.result || '已执行'
    // 导航类动作（打开页面 / 出题后去练习）：服务端校验白名单后下发路径，前端执行跳转
    if (r.navigate) router.push(r.navigate)
  } catch (e) {
    showHint(e.message)
  } finally {
    a.busy = false
  }
}

const cancelAction = async (m, a) => {
  if (a.busy) return
  a.busy = true
  try {
    await api('/api/agent/actions/' + a.id + '/cancel', { method: 'POST' })
    m.actions.splice(m.actions.indexOf(a), 1)
  } catch (e) {
    showHint(e.message)
    a.busy = false
  }
}
</script>

<style scoped>
/* 待确认动作卡片：沿用 .sources 配色，仅补两条布局规则 */
.agent-action { margin-top: 8px; }
.agent-action-summary {
  font-size: 12px; color: var(--primary); background: var(--accent-subtle);
  border-radius: 8px 8px 0 0; padding: 6px 10px; line-height: 1.7;
}
.agent-action-ops {
  display: flex; gap: 8px; background: var(--accent-subtle);
  border-radius: 0 0 8px 8px; padding: 6px 10px;
}

/* 引用跳转：chips + 来源原文面板 */
.src-chip {
  cursor: pointer; font-weight: 700; margin: 0 3px; padding: 1px 6px;
  border: 1px solid currentColor; border-radius: 6px; transition: background .15s ease;
}
.src-chip:hover { background: var(--bg); }
.src-panel { margin-top: 8px; display: flex; flex-direction: column; gap: 8px; }
.src-panel-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 12px; color: var(--muted); padding: 0 2px;
}
.src-close {
  border: 1px solid var(--border); background: var(--bg); color: var(--muted);
  font-size: 12px; border-radius: 999px; padding: 2px 10px; cursor: pointer;
  transition: color .15s ease, border-color .15s ease;
}
.src-close:hover { color: var(--primary); border-color: var(--primary); }
.src-item {
  background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 8px 12px;
  transition: border-color .3s ease, box-shadow .3s ease;
}
.src-item.flash { border-color: var(--primary); box-shadow: 0 0 0 3px rgba(9,105,218,.18); }
html.dark .src-item.flash { box-shadow: 0 0 0 3px rgba(56,139,253,.35); }
.src-doc { font-size: 12px; font-weight: 700; color: var(--primary); margin-bottom: 3px; }
.src-txt { font-size: 12.5px; color: var(--muted); line-height: 1.7; }

/* 追问推荐 chips：沿用待确认动作的浅蓝配色，点击直接发起该问题 */
.followups {
  margin-top: 8px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center;
}
.fu-label { font-size: 12px; color: var(--muted); }
.fu-chip {
  font-size: 12.5px; color: var(--primary); background: var(--accent-subtle);
  border: none; border-radius: 999px; padding: 4px 12px; cursor: pointer;
  text-align: left; line-height: 1.5; transition: background .15s ease;
}
.fu-chip:hover { background: #c9e7ff; }

/* 苏格拉底引导模式 */
.chat-toolbar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 18px 0; font-size: 12.5px;
}
.socratic-toggle {
  display: inline-flex; align-items: center; gap: 6px;
  cursor: pointer; user-select: none; font-weight: 600; color: var(--muted);
}
.socratic-toggle input { width: auto; margin: 0; accent-color: var(--primary); }
.socratic-toggle:has(input:checked) { color: var(--primary); }
.socratic-hint { color: var(--muted); }

html.dark .fu-chip:hover { background: rgba(56,139,253,.3); }
</style>