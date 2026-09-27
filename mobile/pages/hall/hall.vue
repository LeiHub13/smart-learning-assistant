<template>
  <view class="page">
    <view class="topbar">
      <text class="topbar-hint">{{ statusText }}</text>
    </view>

    <scroll-view class="msgs" scroll-y :scroll-into-view="anchor" scroll-with-animation>
      <view v-if="!messages.length && !connecting" class="empty">
        <view class="empty-logo">💬</view>
        <view class="empty-txt">所有登录用户都在这里，打个招呼吧</view>
      </view>

      <view v-for="m in messages" :key="m.key">
        <view v-if="m.kind === 'system'" class="sysline">{{ m.text }}</view>
        <view v-else class="msg" :class="Number(m.userId) === meId ? 'mine' : 'other'">
          <view class="meta">{{ (Number(m.userId) === meId ? '我' : m.nickname) + (m.time ? ' · ' + m.time : '') }}</view>
          <view class="bub">{{ m.content }}</view>
        </view>
      </view>
      <view id="hall-bottom" class="anchor" />
    </scroll-view>

    <view class="input-bar">
      <input
        v-model="input"
        class="ipt"
        placeholder="和大家聊聊学习心得…"
        placeholder-class="ph"
        confirm-type="send"
        :disabled="!connected"
        @confirm="send"
      />
      <button class="send" :disabled="!connected || !input.trim()" @click="send">{{ connected ? '发送' : '未连接' }}</button>
    </view>
  </view>
</template>

<script>
import { api } from '../../utils/api'
import { getBaseUrl } from '../../config.js'

const RECONNECT_MAX = 5

export default {
  data() {
    return {
      messages: [], input: '', meId: null,
      connected: false, connecting: true, gaveUp: false, onlineCount: 0,
      anchor: 'hall-bottom'
    }
  },
  computed: {
    statusText() {
      if (this.connected) return `在线 ${this.onlineCount} 人`
      if (this.gaveUp) return '重连失败，请退出页面后重试'
      return this.connecting ? '连接中…' : '连接断开，正在重连…'
    }
  },
  onLoad() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    // 重连状态都放实例上，不进 data（避免每秒心跳触发无谓渲染）
    this.task = null
    this.hbTimer = null
    this.retryTimer = null
    this.manualClose = false
    this.retries = 0
    this.seq = 0
    this.init()
  },
  onUnload() {
    this.manualClose = true
    clearInterval(this.hbTimer)
    clearTimeout(this.retryTimer)
    if (this.task) {
      try { this.task.close({ code: 1000 }) } catch (e) { /* 已断开忽略 */ }
      this.task = null
    }
  },
  methods: {
    async init() {
      try {
        const me = await api('/api/auth/me')
        // /api/auth/me 的 id 是字符串、WS 广播的 userId 是数字：统一转数值再比较
        this.meId = Number(me.id)
      } catch (e) { /* 拿不到只影响「我」的气泡方向 */ }
      try {
        const list = await api('/api/hall/messages?limit=50')
        this.messages = (list || []).map((m) => ({ ...m, kind: 'chat', key: 'c' + m.id, time: this.fmtTime(m.createdAt) }))
        this.scrollBottom()
      } catch (e) { /* 历史拉不到不挡实时 */ }
      this.connect()
    },
    wsUrl() {
      const base = (getBaseUrl() || '').replace(/^http/, 'ws').replace(/\/+$/, '')
      return base + '/ws/hall?token=' + encodeURIComponent(uni.getStorageSync('la_token'))
    },
    connect() {
      if (this.task) return
      this.gaveUp = false
      this.connecting = this.retries === 0
      this.manualClose = false
      const task = uni.connectSocket({
        url: this.wsUrl(),
        complete: () => {}
      })
      this.task = task
      task.onOpen(() => {
        this.connected = true
        this.connecting = false
        this.retries = 0
        // 心跳保活：服务端 90s 无消息清死连接
        this.hbTimer = setInterval(() => {
          if (this.task) this.task.send({ data: JSON.stringify({ type: 'ping' }) })
        }, 25000)
      })
      task.onMessage((res) => {
        let data
        try { data = JSON.parse(res.data) } catch (e) { return }
        this.handle(data)
      })
      task.onClose(() => this.dropped(task))
      task.onError(() => this.dropped(task))
    },
    dropped(sock) {
      // onError 与 onClose 会先后触发：只认当前这颗 socket，避免重复安排重连
      if (this.task !== sock) return
      this.task = null
      this.connected = false
      clearInterval(this.hbTimer)
      if (this.manualClose) return
      if (this.retries >= RECONNECT_MAX) {
        this.gaveUp = true
        this.connecting = false
        return
      }
      clearTimeout(this.retryTimer)
      this.retryTimer = setTimeout(() => {
        this.retries++
        this.connect()
      }, 3000 * (this.retries + 1))
    },
    handle(data) {
      if (data.type === 'chat') {
        this.messages.push({
          kind: 'chat', key: 'c' + data.id, id: data.id, userId: data.userId,
          nickname: data.nickname, content: data.content, time: this.fmtTime(data.sentAt)
        })
        this.scrollBottom()
      } else if (data.type === 'system') {
        // join/leave 事件都携带全量在线名单（按 userId 去重过）
        this.onlineCount = data.onlineCount || 0
        this.pushSys(data.event === 'join'
          ? `${data.nickname} 进入对话厅`
          : `${data.nickname} 离开了对话厅`)
      } else if (data.type === 'error') {
        uni.showToast({ title: data.message || '发送失败', icon: 'none' })
      }
    },
    pushSys(text) {
      this.messages.push({ kind: 'system', key: 's' + this.seq++, text })
      this.scrollBottom()
    },
    send() {
      const text = this.input.trim()
      if (!text || !this.task || !this.connected) return
      // 不本地回显：服务端会广播回自己（含消息 id），顺序与他人一致
      this.task.send({ data: JSON.stringify({ type: 'chat', content: text.slice(0, 500) }) })
      this.input = ''
    },
    fmtTime(iso) {
      if (!iso) return ''
      const d = new Date(iso)
      if (isNaN(d.getTime())) return ''
      const p = (n) => (n < 10 ? '0' + n : '' + n)
      return p(d.getHours()) + ':' + p(d.getMinutes())
    },
    scrollBottom() {
      this.anchor = ''
      this.$nextTick(() => { this.anchor = 'hall-bottom' })
    }
  }
}
</script>

<style>
.page { display: flex; flex-direction: column; height: 100vh; background: #f5f6fa; }
.topbar {
  display: flex; align-items: center; gap: 16rpx;
  padding: 14rpx 24rpx;
  background: #ffffff; border-bottom: 1rpx solid #eceef2;
}
.topbar-hint { color: #9aa0ab; font-size: 24rpx; }
.msgs { flex: 1; min-height: 0; padding: 20rpx 24rpx 0; box-sizing: border-box; }
.anchor { height: 2rpx; }
.empty { text-align: center; padding-top: 220rpx; }
.empty-logo { font-size: 90rpx; }
.empty-txt { color: #9aa0ab; font-size: 28rpx; margin-top: 20rpx; }
.sysline {
  text-align: center; color: #9aa0ab; font-size: 23rpx;
  margin: 8rpx 0 20rpx;
}
.msg { margin-bottom: 24rpx; display: flex; flex-direction: column; align-items: flex-start; }
.msg.mine { align-items: flex-end; }
.meta { color: #8a90a0; font-size: 22rpx; margin-bottom: 6rpx; }
.bub {
  max-width: 82%;
  padding: 18rpx 26rpx;
  border-radius: 22rpx;
  background: #ffffff;
  color: #2b2f36;
  font-size: 29rpx;
  line-height: 1.6;
  box-shadow: 0 2rpx 8rpx rgba(31, 45, 90, 0.05);
  word-break: break-all;
}
.msg.mine .bub { background: #4f6ef7; color: #ffffff; }
.input-bar {
  display: flex; align-items: center; gap: 16rpx;
  padding: 16rpx 24rpx calc(16rpx + constant(safe-area-inset-bottom));
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  background: #ffffff; border-top: 1rpx solid #eceef2;
}
.ipt {
  flex: 1; height: 76rpx;
  background: #f5f6fa; border-radius: 999rpx;
  padding: 0 30rpx; font-size: 29rpx;
}
.ph { color: #b3b9c4; }
.send {
  margin: 0; width: 140rpx; height: 76rpx; line-height: 76rpx;
  border-radius: 999rpx; padding: 0;
  background: #4f6ef7; color: #ffffff; font-size: 28rpx;
}
.send[disabled] { background: #c3cdfb; color: #ffffff; }
</style>
