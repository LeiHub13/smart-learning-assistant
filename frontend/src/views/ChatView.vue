<template>
  <div class="chat-wrap">
    <aside class="side">
      <button class="new-chat" @click="onNewSession">
        <AppIcon name="practice" :size="15" /> 新聊天
      </button>
      <div v-if="hint" class="side-hint">{{ hint }}</div>
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
        <div class="sess-sec">最近</div>
        <!-- 知识库模式按课程分组（组序随组内最新会话），组头可点击收起/展开；自由对话保持平铺 -->
        <div v-for="g in groupedSessions" :key="g.id ?? 'none'" class="sess-group">
          <button v-if="g.name" class="sess-group-head" :title="isCollapsed(g.id) ? '展开' : '收起'"
                  @click="toggleGroup(g.id)">
            <span class="sess-group-name">{{ g.name }}</span>
            <span class="sess-group-meta">
              <span class="sess-group-n">{{ g.items.length }}</span>
              <AppIcon name="chev" class="grp-arrow" :class="{ open: !isCollapsed(g.id) }" :size="12" />
            </span>
          </button>
          <div v-show="!g.name || !isCollapsed(g.id)">
            <div v-for="s in g.items" :key="s.id" class="sess" :class="{ on: s.id === sessionId }" @click="openSession(s.id)">
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
          </div>
        </div>
        <EmptyState v-if="!filteredSessions.length" compact icon="chat" title="暂无会话"
                    hint="点击上方「新聊天」，或直接在右侧开始提问" />
      </div>
    </aside>

    <div class="chatbox">
      <div class="chat-topbar">
        <div class="ct-title">{{ currentSession ? currentSession.title : '新对话' }}</div>
        <div class="ct-ops">
          <QuotaBadge />
          <button class="btn ghost small" :disabled="streaming || !sessionId" @click="genFlashcards">生成闪卡</button>
          <label class="mode-pill" title="开启后 AI 不直接给答案，拆步骤反问引导你思考">
            <input type="checkbox" :checked="socratic" @change="toggleSocratic" />
            苏格拉底
          </label>
          <label class="mode-pill" title="费曼技巧：角色互换，你把概念讲给 AI 听，AI 扮演不懂的学生追问；说「讲完了」可得讲解评分">
            <input type="checkbox" :checked="feynman" @change="toggleFeynman" />
            费曼
          </label>
        </div>
      </div>
      <div v-if="socratic || feynman" class="mode-banner">
        <template v-if="socratic">引导模式：AI 逐步反问，不直接给答案</template>
        <template v-else>费曼模式：你讲 AI 问，讲完后说「讲完了」可得评分</template>
        <a v-if="socratic" class="link" @click="askDirect">卡住了？直接讲解</a>
      </div>
      <div class="msgs" ref="bodyRef" :class="{ 'is-empty': !messages.length }">
        <EmptyState v-if="!messages.length" icon="sparkles" title="你好，我是 AI 学习助手"
                    :hint="mode === 'kb' ? '选择课程知识库后提问，回答将基于资料并标注引用' : '学习问题随时问我'">
          <button v-for="q in suggestQs" :key="q" class="ce-chip" :title="q" @click="fill(q)">{{ q }}</button>
        </EmptyState>
        <div v-for="(m, i) in messages" :key="i" class="cm" :class="m.role === 'user' ? 'me' : 'ai'">
          <div v-if="m.role === 'user'" class="cm-bub">{{ m.content }}</div>
          <div v-else class="cm-ai">
            <div class="cm-body">
              <div v-if="streaming && i === messages.length - 1" class="stream-txt">{{ m.content }}<span class="cursor"></span></div>
              <div v-else v-html="mdToHtml(m.content)"></div>
              <div v-if="m.sources && !(streaming && i === messages.length - 1)" class="sources">
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
              <div v-if="m.followups && m.followups.length && !(streaming && i === messages.length - 1)"
                   class="followups">
                <span class="fu-label">继续问</span>
                <button v-for="(q, qi) in m.followups" :key="qi" class="fu-chip" :title="q" @click="send(q)">{{ q }}</button>
              </div>
            </div>
            <div v-if="!(streaming && i === messages.length - 1) && m.content" class="cm-ops">
              <button class="ai-op" @click="copyMessage(m, i)">{{ copiedId === i ? '已复制' : '复制' }}</button>
            </div>
          </div>
        </div>
      </div>
      <div class="composer">
        <div class="composer-box">
          <textarea ref="inputRef" v-model="input" rows="1"
                    :placeholder="feynman ? '用你的话把概念讲给 AI 听…（费曼模式，说「讲完了」可得评分）'
                      : (socratic ? '输入你的思考或回答…（引导模式）' : '询问任何学习问题，Enter 发送')"
                    :disabled="streaming"
                    @input="autoGrow"
                    @keydown.enter.exact.prevent="send()"></textarea>
          <button class="composer-send" :disabled="streaming || !input.trim()"
                  :title="streaming ? '生成中…' : '发送'" @click="send()">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M12 19V5" /><path d="m5 12 7-7 7 7" />
            </svg>
          </button>
        </div>
        <div class="composer-note">AI 回答可能出错，请核查重要信息</div>
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
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { api, sseStream, getCourses } from '../api'
import QuotaBadge from '../components/QuotaBadge.vue'
import EmptyState from '../components/EmptyState.vue'
import AppIcon from '../components/AppIcon.vue'
import { mdToHtml, copyText } from '../utils'

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

// 空状态「猜你想问」：点击填入输入框（不直接发送，让用户先确认/补全上下文）
const suggestQs = computed(() => (mode.value === 'kb'
  ? ['帮我梳理这门课的核心知识框架', '出一道题检验我的掌握程度', '这门课有哪些常见易错点？']
  : ['帮我制定一份两周复习计划', '如何提高刷题效率？', '讲讲艾宾浩斯遗忘曲线的用法']))
const fill = (q) => { input.value = q }
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
const currentSession = computed(() => sessions.value.find((x) => x.id === sessionId.value))
/* 会话分组：知识库模式按课程归类（课程名取自已加载课程表，已删课程兜底展示），
   组序随组内最新会话（filteredSessions 已按时间倒序，首现顺序即组序）；自由对话单组平铺不显示组头 */
const groupedSessions = computed(() => {
  if (mode.value !== 'kb') return [{ id: null, name: '', items: filteredSessions.value }]
  const groups = new Map()
  for (const s of filteredSessions.value) {
    const key = s.courseId ?? null
    if (!groups.has(key)) {
      groups.set(key, { id: key, name: key ? (courses.value.find((c) => c.id === key)?.name || '已删除课程') : '未绑定课程', items: [] })
    }
    groups.get(key).items.push(s)
  }
  return [...groups.values()]
})

/* 分组折叠：默认全展开，点组头切换（key 用字符串区分 null 组） */
const collapsedGroups = ref(new Set())
const groupKey = (id) => String(id ?? 'none')
const isCollapsed = (id) => collapsedGroups.value.has(groupKey(id))
const toggleGroup = (id) => {
  const k = groupKey(id)
  const next = new Set(collapsedGroups.value)
  next.has(k) ? next.delete(k) : next.add(k)
  collapsedGroups.value = next
}

/* ===== 刷新后恢复现场：记住用户最后选择的模式、知识库绑定与所在会话 ===== */
const LS_KEYS = { mode: 'chat-mode', kbCourse: 'chat-kb-course', kbKb: 'chat-kb-id', kbScope: 'chat-kb-scope' }
const lastSidKey = (m) => (m === 'kb' ? 'chat-last-sid-kb' : 'chat-last-sid-free')
const lsGet = (k) => { try { return localStorage.getItem(k) } catch { return null } }
const lsSet = (k, v) => { try { v == null || v === '' ? localStorage.removeItem(k) : localStorage.setItem(k, String(v)) } catch { /* 隐私模式忽略 */ } }

watch(mode, (m) => lsSet(LS_KEYS.mode, m))
watch(courseId, (v) => lsSet(LS_KEYS.kbCourse, v ?? ''))
watch(kbId, (v) => lsSet(LS_KEYS.kbKb, v ?? ''))
watch(kbScope, (v) => lsSet(LS_KEYS.kbScope, v))

/* 输入框自动增高（ChatGPT 式），上限 200px；发送后由 send() 复位 */
const inputRef = ref(null)
const autoGrow = (e) => {
  const el = e.target
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

/* 复制 AI 回复：沿用全局 copyText（非 https 环境自动降级 execCommand） */
const copiedId = ref(-1)
let copiedTimer = null
const copyMessage = async (m, i) => {
  if (await copyText(m.content)) {
    copiedId.value = i
    clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => (copiedId.value = -1), 1500)
  } else {
    showHint('复制失败，请手动选择文本复制')
  }
}

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
  // 恢复刷新前的模式与知识库绑定（此前总是打开最新会话并把模式拽成它的，用户选的模式被覆盖）
  const savedMode = lsGet(LS_KEYS.mode) === 'free' ? 'free' : 'kb'
  mode.value = savedMode
  if (savedMode === 'kb') {
    const cid = Number(lsGet(LS_KEYS.kbCourse))
    courseId.value = courses.value.some((c) => c.id === cid) ? cid : null
    if (courseId.value) {
      kbs.value = await api('/api/courses/' + courseId.value + '/kb').catch(() => [])
      const kid = Number(lsGet(LS_KEYS.kbKb))
      kbId.value = kbs.value.some((k) => k.id === kid) ? kid : null
      kbScope.value = lsGet(LS_KEYS.kbScope) === 'course' ? 'course' : 'single'
    } else {
      kbs.value = []
      kbId.value = null
    }
  } else {
    courseId.value = null
    kbId.value = null
    kbs.value = []
  }
  // 优先回到上次打开的会话（须属于当前模式），否则打开该模式最新会话；都没有则保持空态，不自动建会话
  const savedSid = Number(lsGet(lastSidKey(savedMode)))
  const saved = list.find((x) => x.id === savedSid && sessionMode(x) === savedMode)
  const target = saved || filteredSessions.value[0]
  if (target) await openSession(target.id)
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
  // 记住本次打开的会话，刷新后回到这里（按模式分开记）
  lsSet(lastSidKey(mode.value), id)
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
  if (inputRef.value) inputRef.value.style.height = 'auto'
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
    window.dispatchEvent(new Event('billing-changed'))
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
    // 从点击处向上找所在 AI 正文块，避免多条消息同时展开面板时定位到别家
    const bub = ev && ev.target ? ev.target.closest('.cm-ai') : null
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

/* 空状态「猜你想问」chips */
.ce-chip {
  max-width: 260px; padding: 6px 13px; border-radius: 999px;
  border: 1px solid var(--border); background: var(--bg); color: var(--muted);
  font-size: 12px; font-family: inherit; cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  transition: color .15s ease, border-color .15s ease, background .15s ease;
}
.ce-chip:hover {
  color: var(--text); border-color: var(--primary); background: var(--accent-subtle);
}

/* ===== 会话侧栏（ChatGPT 扁平风格，侧栏底色用全局 .side 的 --soft） ===== */
.new-chat {
  display: flex; align-items: center; justify-content: center; gap: 7px;
  width: 100%; padding: 9px 12px; margin: 2px 0 10px;
  border: 1px solid var(--border); border-radius: 10px;
  background: var(--bg); color: var(--text);
  font-size: 13.5px; font-weight: 600; font-family: inherit; cursor: pointer;
  transition: background .15s ease, border-color .15s ease;
}
.new-chat:hover { background: var(--hover); border-color: var(--muted); }
.side-hint { font-size: 12px; color: var(--warn); margin: 0 2px 8px; }
.sess-sec {
  font-size: 11px; font-weight: 600; color: var(--muted);
  letter-spacing: .05em; margin: 4px 4px 6px;
}
/* 侧栏底色是 --soft，悬停换 --hover 才看得见 */
.sess:hover { background: var(--hover); }
/* 课程分组头：可点击收起/展开 */
.sess-group-head {
  display: flex; align-items: center; justify-content: space-between;
  width: 100%; margin: 10px 4px 4px; padding: 4px 8px;
  border: none; background: transparent; cursor: pointer; border-radius: 6px;
  font-size: 13px; font-weight: 600; color: var(--text); font-family: inherit;
  transition: background .15s ease;
}
.sess-group-head:hover { background: var(--hover); }
.sess-group:first-of-type .sess-group-head { margin-top: 2px; }
.sess-group-meta { display: inline-flex; align-items: center; gap: 5px; flex: none; }
.sess-group-n { font-size: 11px; font-weight: 500; color: var(--muted); }
.grp-arrow { color: var(--muted); transition: transform .2s ease; }
.grp-arrow.open { transform: rotate(180deg); }
.sess-group-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ===== 顶栏工具行：会话标题 + 配额/闪卡/模式开关 ===== */
.chat-topbar {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; border-bottom: 1px solid var(--border); flex-shrink: 0;
}
.ct-title {
  font-size: 14px; font-weight: 600; letter-spacing: -.01em;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ct-ops { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.mode-pill {
  display: inline-flex; align-items: center;
  padding: 5px 12px; border: 1px solid var(--border); border-radius: 999px;
  background: var(--bg); color: var(--muted);
  font-size: 12.5px; font-weight: 600; cursor: pointer; user-select: none;
  transition: color .15s ease, border-color .15s ease, background .15s ease;
}
.mode-pill input { display: none; }
.mode-pill:has(input:checked) { color: var(--primary); border-color: var(--primary); background: var(--accent-subtle); }
.mode-banner {
  display: flex; align-items: center; gap: 10px; flex-shrink: 0;
  padding: 6px 16px; font-size: 12px; color: var(--muted);
  background: var(--accent-subtle);
}
.mode-banner .link { margin-left: auto; font-size: 12px; }

/* ===== 消息区（ChatGPT 形态：用户右侧灰气泡，AI 全宽正文无气泡） ===== */
.msgs { padding: 24px 20px 10px; }
.msgs.is-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; }
.cm { max-width: 760px; margin: 0 auto 26px; }
.cm.me { display: flex; justify-content: flex-end; }
.cm-bub {
  max-width: 85%; padding: 10px 16px; border-radius: 18px;
  background: var(--soft); border: 1px solid var(--border);
  line-height: 1.75; white-space: pre-wrap; word-break: break-word;
}
.cm-ai { min-width: 0; }
.cm-body { line-height: 1.75; word-break: break-word; }
.cm-ops { display: flex; gap: 4px; margin-top: 4px; }
.ai-op {
  border: none; background: transparent; color: var(--muted);
  font-size: 12px; font-family: inherit; cursor: pointer;
  padding: 3px 9px; border-radius: 6px;
  transition: background .15s ease, color .15s ease;
}
.ai-op:hover { background: var(--hover); color: var(--text); }

/* ===== 胶囊输入条 ===== */
.composer { flex-shrink: 0; background: var(--bg); padding: 6px 16px 10px; }
.composer-box {
  max-width: 760px; margin: 0 auto;
  display: flex; align-items: flex-end; gap: 10px;
  border: 1px solid var(--border); border-radius: 24px;
  background: var(--card); padding: 8px 8px 8px 16px;
  box-shadow: 0 2px 14px rgba(31,35,40,.06);
  transition: border-color .15s ease;
}
.composer-box:focus-within { border-color: var(--muted); }
.composer-box textarea {
  flex: 1; border: none; outline: none; box-shadow: none; background: transparent;
  padding: 6px 0; min-height: 24px; max-height: 200px;
  line-height: 1.6; resize: none;
}
/* 发送按钮：ChatGPT 式深色圆形箭头（跟随主题文字色） */
.composer-send {
  flex: none; width: 36px; height: 36px; border-radius: 50%; border: none;
  background: var(--text); color: var(--bg); cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: opacity .15s ease, transform .15s ease;
}
.composer-send svg { width: 18px; height: 18px; }
.composer-send:disabled { background: var(--soft); color: var(--muted); cursor: not-allowed; }
.composer-send:not(:disabled):hover { transform: translateY(-1px); }
.composer-note {
  max-width: 760px; margin: 6px auto 0; text-align: center;
  font-size: 11px; color: var(--muted);
}

/* 移动端：内容列收窄、气泡放宽、工具行紧凑 */
@media (max-width: 768px) {
  .cm { margin-bottom: 20px; }
  .cm-bub { max-width: 92%; }
  .chat-topbar { padding: 8px 12px; }
  .ct-ops { gap: 6px; }
  .mode-pill { padding: 4px 10px; font-size: 12px; }
  .composer { padding: 4px 10px 8px; }
  .composer-box { border-radius: 20px; }
}

html.dark .fu-chip:hover { background: rgba(56,139,253,.3); }
</style>
