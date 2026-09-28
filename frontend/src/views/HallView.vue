<template>
  <div class="hall-page">
    <aside class="side">
      <div class="side-sec">
        <div class="side-head">频道</div>
        <div v-for="c in channelList" :key="'ch' + (c.courseId ?? 'pub')"
             class="ch-item" :class="{ on: active.kind === 'channel' && active.courseId === c.courseId }"
             @click="openChannel(c)">
          # {{ c.name }}
        </div>
        <div v-if="!channelList.length" class="muted" style="font-size:12px;padding:4px 10px">加载中…</div>
      </div>

      <div class="side-sec">
        <div class="side-head">
          <span>好友</span>
          <button class="btn ghost small" @click="addOpen = !addOpen">+ 加好友</button>
        </div>
        <div v-if="addOpen" class="add-row">
          <input v-model="addName" type="text" placeholder="对方用户名" @keyup.enter="sendRequest" />
          <button class="btn small" :disabled="!addName.trim() || adding" @click="sendRequest">请求</button>
        </div>
        <div v-if="addMsg" class="muted" style="font-size:12px;padding:2px 10px 6px">{{ addMsg }}</div>

        <div v-for="r in incoming" :key="'r' + r.requestId" class="req-item">
          <span class="shrink">{{ r.nickname }}</span>
          <span class="row" style="gap:4px;margin-left:auto">
            <button class="btn small" @click="acceptReq(r)">同意</button>
            <button class="btn ghost small" @click="rejectReq(r)">拒绝</button>
          </span>
        </div>
        <div v-for="o in outgoing" :key="'o' + o.requestId" class="req-item muted">
          <span class="shrink">待同意：{{ o.nickname }}</span>
        </div>

        <div v-for="f in friends" :key="'f' + f.userId"
             class="fr-item" :class="{ on: active.kind === 'dm' && active.peerId === f.userId }"
             @click="openDm(f)">
          <span class="dot" :class="{ on: f.online }" />
          <span class="shrink">{{ f.nickname }}</span>
          <span v-if="unread[f.userId]" class="badge-n">{{ unread[f.userId] > 99 ? '99+' : unread[f.userId] }}</span>
        </div>
        <div v-if="!friends.length && !incoming.length" class="muted" style="font-size:12px;padding:4px 10px">
          还没有好友，点上方「+ 加好友」
        </div>
      </div>
    </aside>

    <section class="chat">
      <div class="chat-head">
        <div>
          <h3>{{ active.kind === 'dm' ? active.name : '# ' + active.name }}</h3>
          <div class="hall-status" :class="{ off: !connected }">
            <template v-if="active.kind === 'dm'">{{ peerOnline ? '对方在线' : '对方不在线（消息会通知对方）' }}</template>
            <template v-else-if="connected">在线 {{ onlineCount }} 人{{ onlineNames ? '：' + onlineNames : '' }}</template>
            <template v-else-if="reconnecting">连接断开，正在重连…</template>
            <template v-else>连接中…</template>
          </div>
        </div>
        <span class="row" style="gap:8px">
          <button v-if="active.kind === 'dm'" class="btn danger small" @click="askRemove = true">删除好友</button>
          <button v-if="!connected && !reconnecting" class="btn small" @click="connect">重新连接</button>
        </span>
      </div>

      <div v-if="error" class="err" style="margin:0 16px">{{ error }}</div>

      <div v-if="askRemove" class="row" style="margin:8px 16px 0;background:var(--bad-soft,#fdebec);border-radius:8px;padding:8px 12px">
        <span class="shrink" style="font-size:13px">确认删除好友「{{ active.name }}」？聊天记录将保留但无法继续私聊。</span>
        <span class="row" style="gap:8px;margin-left:auto">
          <button class="btn danger small" @click="doRemove">确认删除</button>
          <button class="btn ghost small" @click="askRemove = false">取消</button>
        </span>
      </div>

      <div class="msgs" ref="bodyRef">
        <div v-if="!messages.length" class="empty">
          <div style="font-size:16px;font-weight:600;margin-bottom:8px">{{ emptyTitle }}</div>
          <div style="margin:10px 0 16px">{{ emptyHint }}</div>
        </div>
        <div v-for="m in messages" :key="m.key">
          <div v-if="m.kind === 'system'" class="sys-line">{{ m.text }}</div>
          <div v-else-if="m.kind === 'dm'" class="m" :class="{ me: m.mine }">
            <div class="av" :style="m.mine ? {} : { background: avatarColor(m.fromUserId) }">{{ avatarChar(m) }}</div>
            <div class="bub-wrap">
              <div class="meta">
                <span class="nick">{{ m.mine ? '我' : m.nickname }}</span>
                <span class="time">{{ fmtTime(m.sentAt) }}</span>
              </div>
              <div class="bub hall-bub">{{ m.content }}</div>
            </div>
          </div>
          <div v-else class="m" :class="{ me: Number(m.userId) === meId }">
            <div class="av" :style="Number(m.userId) === meId ? {} : { background: avatarColor(m.userId) }">{{ avatarChar(m) }}</div>
            <div class="bub-wrap">
              <div class="meta">
                <span class="nick">{{ Number(m.userId) === meId ? '我' : m.nickname }}</span>
                <span class="time">{{ fmtTime(m.sentAt) }}</span>
              </div>
              <div class="bub hall-bub">{{ m.content }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="input-bar">
        <textarea v-model="input" :placeholder="connected ? inputHint : '未连接'" :disabled="!connected"
                  @keydown.enter.exact.prevent="send"></textarea>
        <button class="btn" :disabled="!connected || !input.trim()" @click="send">
          {{ connected ? '发送' : '未连接' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, onActivated, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, getToken } from '../api'

defineOptions({ name: 'HallView' })

const route = useRoute()

const channelList = ref([])
const friends = ref([])
const incoming = ref([])
const outgoing = ref([])
const unread = ref({})
const active = ref({ kind: 'channel', courseId: null, name: '公共大厅' })

const messages = ref([])
const input = ref('')
const meId = ref(null)
const connected = ref(false)
const reconnecting = ref(false)
const onlineUsers = ref([])
const bodyRef = ref(null)
const error = ref('')

const addOpen = ref(false)
const addName = ref('')
const addMsg = ref('')
const adding = ref(false)
const askRemove = ref(false)

let ws = null
let hbTimer = null
let retryTimer = null
let friendsTimer = null
let manualClose = false
let retries = 0
let seq = 0

const onlineCount = computed(() => onlineUsers.value.length)
const onlineNames = computed(() => onlineUsers.value.map((u) => u.nickname).join('、'))
const peerOnline = computed(() => {
  const f = friends.value.find((x) => x.userId === active.value.peerId)
  return !!f && !!f.online
})
const emptyTitle = computed(() =>
  active.value.kind === 'dm' ? `与 ${active.value.name} 的私聊` : `欢迎来到 ${active.value.name}`)
const emptyHint = computed(() =>
  active.value.kind === 'dm' ? '聊点什么吧，只有你们两个人能看到' : '打个招呼吧')
const inputHint = computed(() =>
  active.value.kind === 'dm' ? '悄悄话，Enter 发送' : '和大家聊聊学习心得，Enter 发送')

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
    // 只渲染当前频道的消息（服务端本就只发给同课程成员/全员）
    if (active.value.kind !== 'channel') return
    if ((data.courseId ?? null) !== (active.value.courseId ?? null)) return
    messages.value.push({
      kind: 'chat', key: 'c' + data.id, id: data.id, userId: data.userId,
      nickname: data.nickname, content: data.content, sentAt: data.sentAt
    })
    scrollDown()
  } else if (data.type === 'dm') {
    const mine = Number(data.fromUserId) === meId.value
    const peerId = mine ? Number(data.toUserId) : Number(data.fromUserId)
    if (active.value.kind === 'dm' && active.value.peerId === peerId) {
      messages.value.push({
        kind: 'dm', key: 'd' + data.id, id: data.id, mine,
        fromUserId: data.fromUserId, nickname: data.fromNickname,
        content: data.content, sentAt: data.sentAt
      })
      scrollDown()
    } else if (!mine) {
      unread.value[peerId] = (unread.value[peerId] || 0) + 1
    }
  } else if (data.type === 'system') {
    // join/leave 事件都携带全量在线名单，客户端直接整体替换
    applyOnline(data.online)
    if (active.value.kind === 'channel') {
      pushSys(data.event === 'join' ? `${data.nickname} 进入对话厅` : `${data.nickname} 离开了对话厅`)
    }
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
    sendView()
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

/* 上报当前正在看的会话：服务端据此对 DM 决定「已读不打铃 / 落通知」 */
const sendView = () => {
  if (!ws || ws.readyState !== WebSocket.OPEN) return
  if (active.value.kind === 'dm') {
    ws.send(JSON.stringify({ type: 'view', kind: 'dm', peerUserId: active.value.peerId }))
  } else {
    ws.send(JSON.stringify({ type: 'view', kind: 'channel' }))
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
  // 不本地回显：服务端会把消息推回给自己（含消息 id），顺序与他人一致
  if (active.value.kind === 'dm') {
    ws.send(JSON.stringify({ type: 'dm', toUserId: active.value.peerId, content: text.slice(0, 500) }))
  } else {
    ws.send(JSON.stringify({ type: 'chat', courseId: active.value.courseId, content: text.slice(0, 500) }))
  }
  input.value = ''
}

/* ---------- 左栏数据 ---------- */

const loadChannels = async () => {
  try {
    channelList.value = await api('/api/hall/channels')
  } catch (e) {
    error.value = e.message
  }
}

const loadFriends = async () => {
  try {
    const d = await api('/api/friends')
    friends.value = d.friends || []
    incoming.value = d.incoming || []
    outgoing.value = d.outgoing || []
    // 未读徽标以服务端计数为准；正开着的会话视为已读
    const map = {}
    for (const f of friends.value) {
      map[f.userId] = active.value.kind === 'dm' && active.value.peerId === f.userId
        ? 0 : (f.unread || 0)
    }
    unread.value = map
  } catch (e) {
    error.value = e.message
  }
}

const openChannel = async (c) => {
  askRemove.value = false
  active.value = { kind: 'channel', courseId: c.courseId ?? null, name: c.name }
  messages.value = []
  error.value = ''
  sendView()
  try {
    const q = c.courseId == null ? '' : '&courseId=' + c.courseId
    const list = await api('/api/hall/messages?limit=50' + q)
    messages.value = (list || []).map((m) => ({ kind: 'chat', key: 'c' + m.id, ...m }))
  } catch (e) {
    error.value = e.message
  }
  scrollDown()
}

const openDm = async (f) => {
  askRemove.value = false
  active.value = { kind: 'dm', peerId: f.userId, name: f.nickname }
  unread.value[f.userId] = 0
  messages.value = []
  error.value = ''
  sendView()
  try {
    const list = await api('/api/dm/messages?peerUserId=' + f.userId)
    messages.value = (list || []).map((m) => ({
      kind: 'dm', key: 'd' + m.id, id: m.id, mine: Number(m.fromUserId) === meId.value,
      fromUserId: m.fromUserId, nickname: f.nickname, content: m.content, sentAt: m.createdAt
    }))
  } catch (e) {
    error.value = e.message
  }
  scrollDown()
}

const sendRequest = async () => {
  const name = addName.value.trim()
  if (!name) return
  adding.value = true
  addMsg.value = ''
  try {
    await api('/api/friends/request', { method: 'POST', body: { username: name } })
    addMsg.value = `已向「${name}」发出好友请求`
    addName.value = ''
    await loadFriends()
  } catch (e) {
    addMsg.value = e.message
  } finally {
    adding.value = false
  }
}

const acceptReq = async (r) => {
  try {
    await api('/api/friends/' + r.requestId + '/accept', { method: 'POST', body: {} })
    await loadFriends()
  } catch (e) {
    error.value = e.message
  }
}

const rejectReq = async (r) => {
  try {
    await api('/api/friends/' + r.requestId + '/reject', { method: 'POST', body: {} })
    await loadFriends()
  } catch (e) {
    error.value = e.message
  }
}

const doRemove = async () => {
  const f = friends.value.find((x) => x.userId === active.value.peerId)
  askRemove.value = false
  if (!f) return
  try {
    await api('/api/friends/' + f.requestId, { method: 'DELETE' })
    await loadFriends()
    await openChannel(channelList.value[0] || { courseId: null, name: '公共大厅' })
  } catch (e) {
    error.value = e.message
  }
}

/* ---------- 展示工具 ---------- */

const fmtTime = (iso) => {
  if (!iso) return ''
  const d = new Date(iso)
  if (isNaN(d.getTime())) return ''
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const avatarChar = (m) => {
  if (m.kind === 'dm') return m.mine ? '我' : String(m.nickname || '?').charAt(0).toUpperCase()
  return Number(m.userId) === meId.value ? '我' : String(m.nickname || '?').charAt(0).toUpperCase()
}

/* 其他人头像底色：按 userId 从固定色板取色，同一人颜色稳定 */
const AVATAR_COLORS = ['#4f6ef7', '#0ea5e9', '#10b981', '#f59e0b', '#e11d48', '#8b5cf6', '#0d9488', '#d97706']
const avatarColor = (userId) => AVATAR_COLORS[Math.abs(Number(userId) || 0) % AVATAR_COLORS.length]

/* 铃铛 DM 通知跳转：/hall?peer=userId，直接切到该好友会话 */
const openPeerFromQuery = async () => {
  const peer = Number(route.query.peer)
  if (!peer) return
  const f = friends.value.find((x) => x.userId === peer)
  if (f) {
    await openDm(f)
  } else {
    await openDm({ userId: peer, nickname: '用户' + peer })
  }
}

watch(() => route.query.peer, () => { openPeerFromQuery() })

onMounted(async () => {
  try {
    const me = await api('/api/auth/me')
    // /api/auth/me 的 id 是字符串、WS 广播的 userId 是数字：统一转数值再比较
    meId.value = Number(me.id)
  } catch { /* 拿不到当前用户只影响「我」的气泡方向 */ }
  await loadChannels()
  await loadFriends()
  await openChannel(channelList.value[0] || { courseId: null, name: '公共大厅' })
  await openPeerFromQuery()
  connect()
  // 好友在线状态与未读徽标定时校准（徽标的实时增量走 WS dm 帧）
  friendsTimer = setInterval(loadFriends, 30000)
})

onActivated(() => { loadFriends() })

onUnmounted(() => {
  manualClose = true
  clearInterval(hbTimer)
  clearInterval(friendsTimer)
  clearTimeout(retryTimer)
  if (ws) {
    ws.onclose = null
    ws.close()
  }
})
</script>

<style scoped>
/* 铺满详情页：负 margin 抵消 .main 内边距，高度只留顶栏 */
.hall-page {
  display: flex;
  height: calc(100vh - 60px);
  margin: -24px -32px -40px;
  overflow: hidden;
}
.side {
  width: 250px; flex: none; overflow-y: auto;
  border-right: 1px solid var(--border); background: var(--bg);
}
.side-sec { padding: 12px 10px; border-bottom: 1px solid var(--border); }
.side-head {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 12px; font-weight: 600; color: var(--muted);
  letter-spacing: .04em; margin-bottom: 8px; padding: 0 4px;
}
.add-row { display: flex; gap: 6px; margin: 0 4px 8px; }
.add-row input { flex: 1; min-width: 0; font-size: 13px; padding: 4px 8px; }

.ch-item {
  padding: 7px 10px; border-radius: 8px; cursor: pointer;
  font-size: 13.5px; overflow-wrap: anywhere;
}
.ch-item:hover { background: var(--soft); }
.ch-item.on { background: var(--soft); color: var(--accent-deep); font-weight: 600; }

.fr-item {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; border-radius: 8px; cursor: pointer; font-size: 13.5px;
}
.fr-item:hover { background: var(--soft); }
.fr-item.on { background: var(--soft); }
.dot {
  width: 8px; height: 8px; border-radius: 50%; flex: none;
  background: var(--border); opacity: .8;
}
.dot.on { background: #1a7f37; opacity: 1; }
.badge-n {
  margin-left: auto; background: #d1242f; color: #fff;
  font-size: 10px; border-radius: 8px; padding: 0 5px; line-height: 14px;
}
.req-item {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 10px; font-size: 13px; overflow-wrap: anywhere;
}

.chat { flex: 1; min-width: 0; display: flex; flex-direction: column; background: var(--bg); }
.chat-head {
  display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid var(--border); padding: 10px 16px;
}
.chat-head h3 { margin-bottom: 2px; }
.hall-status { font-size: 12.5px; color: var(--muted); }
.hall-status.off { color: var(--warn); }

.msgs { flex: 1; overflow-y: auto; padding: 16px 18px; }

.input-bar {
  display: flex; gap: 10px; align-items: flex-end;
  border-top: 1px solid var(--border); padding: 12px 16px;
}
.input-bar textarea {
  flex: 1; min-height: 44px; max-height: 120px; resize: none;
  font: inherit; padding: 9px 12px;
}

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

@media (max-width: 1024px) {
  .hall-page { margin: -18px -16px; height: calc(100vh - 60px); }
  .side { width: 210px; }
}
@media (max-width: 768px) {
  .hall-page { margin: -14px -12px calc(-22px - env(safe-area-inset-bottom)); height: calc(100vh - 60px); }
  .side { width: 180px; }
  .bub-wrap { max-width: 85%; }
}
</style>
