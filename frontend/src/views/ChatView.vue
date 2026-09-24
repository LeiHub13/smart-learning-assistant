<template>
  <div class="chat-wrap">
    <div class="side card">
      <h3>会话</h3>
      <button class="btn small" style="margin:8px 0" @click="onNewSession">+ 新建会话</button>
      <div v-if="hint" style="margin-bottom:8px;font-size:12px;color:#d97706">{{ hint }}</div>
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
            <span class="sess-mode-tag">{{ s.kbId ? '知识库' : '自由' }}</span>
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
      <div class="msgs" ref="bodyRef">
        <div v-if="!messages.length" class="empty">
          <div style="font-size:16px;font-weight:600;margin-bottom:8px">你好，我是 AI 学习助手</div>
          <div style="margin:10px 0 16px">
            {{ mode === 'kb' ? '选择课程知识库后提问，回答将基于资料并标注引用' : '学习问题随时问我' }}
          </div>
          <div v-if="mode === 'kb'">
            <button v-for="q in suggestions" :key="q" class="btn ghost small" style="margin:4px" @click="quickAsk(q)">{{ q }}</button>
          </div>
        </div>
        <div v-for="(m, i) in messages" :key="i" class="m" :class="m.role === 'user' ? 'me' : 'ai'">
          <div class="av">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="bub">
            <div v-if="streaming && i === messages.length - 1" class="stream-txt">{{ m.content }}<span class="cursor"></span></div>
            <div v-else-if="m.role === 'user'">{{ m.content }}</div>
            <div v-else v-html="mdToHtml(m.content)"></div>
            <div v-if="m.sources && m.role === 'assistant' && !(streaming && i === messages.length - 1)" class="sources">
              引用来源：知识库片段 {{ m.sources.split(',').join('、') }}
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
          </div>
        </div>
      </div>
      <div class="input-bar">
        <textarea v-model="input" placeholder="输入问题，Enter 发送" :disabled="streaming"
                  @keydown.enter.exact.prevent="send"></textarea>
        <button class="btn" :disabled="streaming || !input.trim()" @click="send">
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
import { ref, onMounted, computed } from 'vue'
import { api, sseStream, getCourses } from '../api'
import { mdToHtml } from '../utils'

defineOptions({ name: 'ChatView' })

const courses = ref([])
const kbs = ref([])
const sessions = ref([])
const sessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const mode = ref('kb')
const courseId = ref(null)
const kbId = ref(null)
const kbScope = ref('single')
const bodyRef = ref(null)
const suggestions = ['HashMap 的底层原理是什么？', 'Java 多线程有哪些核心要点？', '什么是受检异常？']

let raf = null
const scrollDown = () => {
  if (raf) return
  raf = requestAnimationFrame(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
    raf = null
  })
}

const sessionMode = (s) => (s && s.kbId ? 'kb' : 'free')
const filteredSessions = computed(() => sessions.value.filter((s) => sessionMode(s) === mode.value))

onMounted(async () => {
  courses.value = await getCourses()
  const list = await api('/api/chat/sessions')
  sessions.value = list
  if (list.length) await openSession(list[0].id)
})

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
  messages.value = await api('/api/chat/sessions/' + id + '/messages')
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

const quickAsk = (q) => {
  input.value = q
  send()
}

const send = async () => {
  const text = input.value.trim()
  if (!text || streaming.value) return
  if (mode.value === 'kb' && !kbId.value) {
    showHint('请先选择知识库再提问')
    return
  }
  if (!sessionId.value) await newSession()
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '' })
  input.value = ''
  streaming.value = true
  scrollDown()
  try {
    await sseStream('/api/chat/sessions/' + sessionId.value + '/stream', { message: text },
      (delta) => {
        messages.value[messages.value.length - 1].content += delta
        scrollDown()
      },
      (done) => {
        const last = messages.value[messages.value.length - 1]
        last.sources = (done && done.sources) || ''
        last.actions = (done && done.actions) || []
      })
  } catch (e) {
    messages.value[messages.value.length - 1].content = '（请求失败：' + e.message + '）'
  } finally {
    streaming.value = false
    sessions.value = await api('/api/chat/sessions')
    scrollDown()
  }
}

const confirmAction = async (m, a) => {
  if (a.busy) return
  a.busy = true
  try {
    const r = await api('/api/agent/actions/' + a.id + '/confirm', { method: 'POST' })
    a.state = 'done'
    a.result = r.result || '已执行'
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
  font-size: 12px; color: #8c6844; background: #f6efe5;
  border-radius: 8px 8px 0 0; padding: 6px 10px; line-height: 1.7;
}
.agent-action-ops {
  display: flex; gap: 8px; background: #f6efe5;
  border-radius: 0 0 8px 8px; padding: 6px 10px;
}
</style>