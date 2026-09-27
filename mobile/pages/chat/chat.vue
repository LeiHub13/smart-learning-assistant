<template>
  <view class="page">
    <view class="topbar">
      <view class="pill" @click="newChat">＋ 新对话</view>
      <text class="topbar-hint">{{ streaming ? 'AI 正在生成…' : '支持知识库答疑与引用来源' }}</text>
    </view>

    <scroll-view class="msgs" scroll-y :scroll-into-view="anchor" scroll-with-animation>
      <view v-if="!messages.length && !streaming" class="empty">
        <view class="empty-logo">💬</view>
        <view class="empty-txt">有什么学习问题，直接问我</view>
      </view>

      <view v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
        <view class="bub">
          <template v-if="m.role === 'assistant'">
            <!-- 流式中用纯文本增量显示，结束后交给 mp-html 渲染 Markdown -->
            <text v-if="!m._done" class="stream-txt" user-select>{{ m.content }}<text class="cursor">▍</text></text>
            <mp-html v-else-if="m.content" :content="mdToHtml(m.content)" :selectable="true" />
            <view v-if="m.sources && m._done" class="sources">引用来源：知识库片段 {{ m.sources.split(',').join('、') }}</view>
            <!-- Agent 提议动作：确认后 Java 侧才真正写库，与 Web 端一致 -->
            <view v-for="(a, ai) in m.actions || []" :key="ai" class="agent-action">
              <view v-if="a.state === 'done'" class="sources">{{ a.result }}</view>
              <template v-else>
                <view class="action-summary">{{ a.summary }}</view>
                <view class="action-ops">
                  <button class="op-btn" :disabled="a.busy" @click="confirmAction(m, a)">确认执行</button>
                  <button class="op-btn ghost" :disabled="a.busy" @click="cancelAction(m, a)">取消</button>
                </view>
              </template>
            </view>
            <!-- 追问推荐：与 Web 端一致，点一下直接发起该问题 -->
            <view v-if="m._done && m.followups && m.followups.length" class="followups">
              <text class="fu-label">继续问</text>
              <text v-for="(q, qi) in m.followups" :key="qi" class="fu-chip" @click="sendFollowup(q)">{{ q }}</text>
            </view>
          </template>
          <template v-else>
            <text user-select>{{ m.content }}</text>
          </template>
        </view>
      </view>
      <view id="msg-bottom" class="anchor" />
    </scroll-view>

    <view class="input-bar">
      <input
        v-model="input"
        class="ipt"
        placeholder="输入你的问题…"
        placeholder-class="ph"
        confirm-type="send"
        :disabled="streaming"
        :adjust-position="true"
        @confirm="send"
      />
      <button class="send" :disabled="streaming || !input.trim()" @click="send">{{ streaming ? '生成中' : '发送' }}</button>
    </view>
  </view>
</template>

<script>
import { api } from '../../utils/api'
import { sseStream } from '../../utils/sse'
import { mdToHtml } from '../../utils/md'

const SID_KEY = 'la_chat_sid'

export default {
  data() {
    return { sessionId: null, messages: [], input: '', streaming: false, anchor: 'msg-bottom' }
  },
  onLoad() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.init()
  },
  methods: {
    mdToHtml,
    async init() {
      const saved = uni.getStorageSync(SID_KEY)
      if (saved) {
        try {
          const list = await api('/api/chat/sessions/' + saved + '/messages')
          this.sessionId = saved
          // followups 在库里是 JSON 数组串，统一解析成数组供渲染（与 Web 端同约定）
          this.messages = (list || []).map((m) => ({ ...m, actions: m.actions || [], followups: this.parseFollowups(m.followups), _done: true }))
          this.scrollBottom()
          return
        } catch (e) { /* 会话可能已被删，落新建 */ }
      }
      await this.newSession()
    },
    async newSession() {
      const s = await api('/api/chat/sessions', { method: 'POST', body: {} })
      this.sessionId = s.id
      this.messages = []
      uni.setStorageSync(SID_KEY, s.id)
    },
    async newChat() {
      if (this.streaming) return
      try {
        await this.newSession()
        uni.showToast({ title: '已新建对话', icon: 'none' })
      } catch (e) {
        uni.showToast({ title: e.message, icon: 'none' })
      }
    },
    scrollBottom() {
      this.anchor = ''
      this.$nextTick(() => { this.anchor = 'msg-bottom' })
    },
    /* 追问推荐：done 事件与历史消息中的 followups 均为 JSON 数组串，解析失败回落为空数组 */
    parseFollowups(raw) {
      try {
        const arr = JSON.parse(raw || '[]')
        return Array.isArray(arr) ? arr.filter((s) => typeof s === 'string' && s.trim()).slice(0, 3) : []
      } catch (e) {
        return []
      }
    },
    async sendFollowup(q) {
      if (this.streaming || !q) return
      this.input = q
      await this.send()
    },
    async send() {
      const text = this.input.trim()
      if (!text || this.streaming) return
      if (!this.sessionId) {
        try {
          await this.newSession()
        } catch (e) {
          uni.showToast({ title: e.message, icon: 'none' })
          return
        }
      }
      const sid = this.sessionId
      this.input = ''
      this.messages.push({ role: 'user', content: text })
      const a = { role: 'assistant', content: '', sources: '', followups: [], actions: [], _done: false }
      this.messages.push(a)
      this.streaming = true
      this.scrollBottom()
      try {
        await sseStream(
          '/api/chat/sessions/' + sid + '/stream',
          { message: text },
          (delta) => {
            a.content += delta
            this.scrollBottom()
          },
          (done) => {
            a.sources = done.sources || ''
            a.followups = this.parseFollowups(done.followups)
            a.actions = done.actions || []
          }
        )
      } catch (e) {
        a.content += (a.content ? '\n\n' : '') + '[请求出错] ' + e.message
      }
      a._done = true
      this.streaming = false
      this.scrollBottom()
    },
    async confirmAction(m, a) {
      a.busy = true
      try {
        const r = await api('/api/agent/actions/' + a.id + '/confirm', { method: 'POST' })
        a.state = 'done'
        a.result = typeof r === 'string' ? r : (r && (r.result || r.message)) || '已执行'
      } catch (e) {
        uni.showToast({ title: e.message, icon: 'none' })
      }
      a.busy = false
    },
    async cancelAction(m, a) {
      a.busy = true
      try {
        await api('/api/agent/actions/' + a.id + '/cancel', { method: 'POST' })
        const list = m.actions || []
        list.splice(list.indexOf(a), 1)
      } catch (e) {
        uni.showToast({ title: e.message, icon: 'none' })
      }
      a.busy = false
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
.pill {
  padding: 8rpx 24rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 26rpx; font-weight: 600;
}
.topbar-hint { color: #9aa0ab; font-size: 24rpx; }
.msgs { flex: 1; min-height: 0; padding: 20rpx 24rpx 0; box-sizing: border-box; }
.anchor { height: 2rpx; }
.empty { text-align: center; padding-top: 220rpx; }
.empty-logo { font-size: 90rpx; }
.empty-txt { color: #9aa0ab; font-size: 28rpx; margin-top: 20rpx; }
.msg { display: flex; margin-bottom: 24rpx; }
.msg.user { justify-content: flex-end; }
.bub {
  max-width: 82%;
  padding: 20rpx 26rpx;
  border-radius: 22rpx;
  background: #ffffff;
  color: #2b2f36;
  font-size: 29rpx;
  line-height: 1.65;
  box-shadow: 0 2rpx 8rpx rgba(31, 45, 90, 0.05);
}
.msg.user .bub { background: #4f6ef7; color: #ffffff; }
.stream-txt { line-height: 1.65; }
.cursor { animation: blink 1s steps(1) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.sources {
  margin-top: 14rpx; padding-top: 12rpx;
  border-top: 1rpx solid #eceef2;
  color: #8a90a0; font-size: 23rpx;
}
.agent-action {
  margin-top: 16rpx; padding: 16rpx 20rpx;
  background: #f7f8fc; border: 1rpx solid #eceef2; border-radius: 14rpx;
}
.followups {
  margin-top: 14rpx; display: flex; flex-wrap: wrap; gap: 12rpx; align-items: center;
}
.fu-label { color: #8a90a0; font-size: 23rpx; }
.fu-chip {
  padding: 8rpx 20rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 24rpx; line-height: 1.4;
}
.action-summary { font-size: 26rpx; color: #2b2f36; }
.action-ops { display: flex; gap: 16rpx; margin-top: 14rpx; }
.op-btn {
  margin: 0; height: 60rpx; line-height: 60rpx; padding: 0 28rpx;
  font-size: 25rpx; border-radius: 12rpx;
  background: #4f6ef7; color: #ffffff;
}
.op-btn.ghost { background: #ffffff; color: #6b7280; border: 1rpx solid #dcdfe6; }
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
