<template>
  <div class="hall-wrap">
    <div class="card hall">
      <div class="hall-head">
        <div class="hall-title">
          <h3>对话厅</h3>
          <div class="hall-status" :class="{ off: !connected }">
            <template v-if="connected">在线 {{ onlineCount }} 人{{ onlineNames ? '：' + onlineNames : '' }}</template>
            <template v-else-if="reconnecting">连接断开，正在重连…</template>
            <template v-else>连接中…</template>
          </div>
        </div>
        <button v-if="!connected && !reconnecting" class="btn small" @click="connect">重新连接</button>
      </div>
      <div class="msgs" ref="bodyRef">
        <div v-if="!messages.length" class="empty">
          <div style="font-size:16px;font-weight:600;margin-bottom:8px">欢迎来到对话厅</div>
          <div style="margin:10px 0 16px">所有登录用户都在这里，打个招呼吧</div>
        </div>
        <div v-for="m in messages" :key="m.key">
          <div v-if="m.kind === 'system'" class="sys-line">{{ m.text }}</div>
          <div v-else class="m" :class="{ me: Number(m.userId) === meId }">
            <div class="av" :style="Number(m.userId) === meId ? {} : { background: avatarColor(m) }">{{ avatarChar(m) }}</div>
            <div class="bub-wrap">
              <div class="meta">
                <span class="nick">{{ m.userId === meId ? '我' : m.nickname }}</span>
                <span class="time">{{ fmtTime(m.sentAt) }}</span>
              </div>
              <div class="bub hall-bub">{{ m.content }}</div>
            </div>
          </div>
        </div>
      </div>
      <div class="input-bar">
        <textarea v-model="input" :placeholder="connected ? '和大家聊聊学习心得，Enter 发送' : '未连接'" :disabled="!connected"
                  @keydown.enter.exact.prevent="send"></textarea>
        <button class="btn" :disabled="!connected || !input.trim()" @click="send">
          {{ connected ? '发送' : '未连接' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { api, getToken } from '../api'

defineOptions({ name: 'HallView' })

const messages = ref([])
const input = ref('')
const meId = ref(null)
const connected = ref(false)
const reconnecting = ref(false)
const onlineUsers = ref([])
const bodyRef = ref(null)

let ws = null
let hbTimer = null
let retryTimer = null
let manualClose = false
let retries = 0
let seq = 0

const onlineCount = computed(() => onlineUsers.value.length)
const onlineNames = computed(() => onlineUsers.value.map((u) => u.nickname).join('、'))

const wsUrl = () => {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  // 浏览器 WebSocket API 带不了 Authorization 头，token 走握手 URL 参数（服务端握手时校验）
  return `${proto}//${location.host}/ws/hall?token=${encodeURIComponent(getToken())}`
}

const scrollDown = () => {
  nextTick(() => { if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight })
}

const pushSys = (text) => {
  messages.value.push({ kind: 'system', key: 's' + seq++, text })
  scrollDown()
}

const applyOnline = (list) => {
  onlineUsers.value = Array.isArray(list) ? list : []
}

const onServerEvent = (data) => {
  if (data.type === 'chat') {
    messages.value.push({
      kind: 'chat', key: 'c' + data.id, id: data.id, userId: data.userId,
      nickname: data.nickname, content: data.content, sentAt: data.sentAt
    })
    scrollDown()
  } else if (data.type === 'system') {
    // join/leave 事件都携带全量在线名单，客户端直接整体替换
    applyOnline(data.online)
    pushSys(data.event === 'join' ? `${data.nickname} 进入对话厅` : `${data.nickname} 离开了对话厅`)
  } else if (data.type === 'error') {
    pushSys(data.message || '发送失败')
  }
}

const connect = () => {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  manualClose = false
  try {
    ws = new WebSocket(wsUrl())
  } catch {
    scheduleReconnect()
    return
  }
  ws.onopen = () => {
    connected.value = true
    reconnecting.value = false
    retries = 0
    // 心跳保活：服务端 90s 无消息会清死连接
    hbTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ping' }))
    }, 25000)
  }
  ws.onmessage = (ev) => {
    try { onServerEvent(JSON.parse(ev.data)) } catch { /* 非 JSON 帧忽略 */ }
  }
  ws.onclose = () => {
    connected.value = false
    clearInterval(hbTimer)
    hbTimer = null
    if (!manualClose) scheduleReconnect()
  }
}

const scheduleReconnect = () => {
  reconnecting.value = true
  if (retries >= 5) return
  retryTimer = setTimeout(() => {
    retries++
    connect()
  }, 3000 * (retries + 1))
}

const send = () => {
  const text = input.value.trim()
  if (!text || !ws || ws.readyState !== WebSocket.OPEN) return
  // 不本地回显：服务端会广播回自己（含消息 id），顺序与他人一致
  ws.send(JSON.stringify({ type: 'chat', content: text.slice(0, 500) }))
  input.value = ''
}

const fmtTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const avatarChar = (m) => (Number(m.userId) === meId.value ? '我' : String(m.nickname || '?').charAt(0).toUpperCase())

/* 其他人头像底色：全局样式只定义了 .me/.ai 两种，对话厅的普通成员消息没有底色（白字白底看不见）。
   按 userId 从固定色板取色，同一人颜色稳定，多人聊天也可区分 */
const AVATAR_COLORS = ['#4f6ef7', '#0ea5e9', '#10b981', '#f59e0b', '#e11d48', '#8b5cf6', '#0d9488', '#d97706']
const avatarColor = (m) => AVATAR_COLORS[Math.abs(Number(m.userId) || 0) % AVATAR_COLORS.length]

onMounted(async () => {
  try {
    const me = await api('/api/auth/me')
    // /api/auth/me 的 id 是字符串、WS 广播的 userId 是数字：统一转数值再比较
    meId.value = Number(me.id)
  } catch { /* 拿不到当前用户只影响「我」的气泡方向 */ }
  try {
    const list = await api('/api/hall/messages?limit=50')
    messages.value = (list || []).map((m) => ({ kind: 'chat', key: 'c' + m.id, ...m }))
    scrollDown()
  } catch { /* 历史拉不到不挡实时 */ }
  connect()
})

onUnmounted(() => {
  manualClose = true
  clearInterval(hbTimer)
  clearTimeout(retryTimer)
  if (ws) {
    ws.onclose = null
    ws.close()
  }
})
</script>

<style scoped>
.hall-wrap { display: flex; justify-content: center; height: calc(100vh - 60px - 68px); }
.hall {
  display: flex; flex-direction: column; width: 100%; max-width: 860px;
  overflow: hidden; box-shadow: none; transition: none;
}
.hall-head {
  display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid var(--border); padding-bottom: 10px;
}
.hall-title h3 { margin-bottom: 2px; }
.hall-status { font-size: 12.5px; color: var(--muted); }
.hall-status.off { color: var(--warn); }

/* 复用全局 .m/.av/.bub 气泡。wrap 只做宽度约束（block，禁止再嵌套 flex：
   .bub 的全局 max-width:76% 以 wrap 为基准，wrap 一旦是收缩的 flex 容器，
   气泡宽度会塌到约一个字宽，短消息被排成竖的） */
.bub-wrap { max-width: 72%; }
.hall-bub { width: fit-content; max-width: 100%; white-space: pre-wrap; word-break: break-word; }
.meta { font-size: 12px; color: var(--muted); margin-bottom: 3px; display: flex; gap: 6px; }
.meta .nick { font-weight: 600; }
.m.me .meta { justify-content: flex-end; }

.sys-line {
  text-align: center; font-size: 12px; color: var(--muted);
  margin: 6px 0 14px;
}
</style>
