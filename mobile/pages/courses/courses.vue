<template>
  <view class="wrap">
    <view v-if="!list.length && !error && !loading" class="card empty-card">
      <view class="empty-t">还没有课程入驻 Hub</view>
      <view class="empty-d">在网页端「我的课程」创建课程，或把已创建的课程加入 Hub</view>
    </view>

    <view v-for="c in list" :key="c.id" class="card course-card">
      <view class="course-head">
        <text class="course-name" user-select>{{ c.name }}</text>
        <text v-if="c.role === 'creator'" class="tag">我创建的</text>
        <text v-if="c.memberCount" class="member">{{ c.memberCount }} 人在学</text>
      </view>
      <view v-if="c.description" class="course-desc" user-select>{{ c.description }}</view>
      <view class="course-ops">
        <template v-if="c.enrolled">
          <button class="op-btn" @click="goPractice(c)">去练习</button>
          <button
            v-if="c.role !== 'creator'"
            class="op-btn ghost"
            :disabled="busyId === c.id"
            @click="leave(c)"
          >
            {{ busyId === c.id ? '退出中…' : '退出课程' }}
          </button>
        </template>
        <button v-else class="op-btn" :disabled="busyId === c.id" @click="join(c)">
          {{ busyId === c.id ? '加入中…' : '加入课程' }}
        </button>
      </view>
    </view>

    <view v-if="loading" class="loading">加载中…</view>
    <view v-if="error" class="err">{{ error }}</view>
  </view>
</template>

<script>
import { api } from '../../utils/api'

const PENDING_KEY = 'la_practice_pending'

export default {
  data() {
    return { list: [], loading: false, busyId: null, error: '' }
  },
  onLoad() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
    }
  },
  onShow() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.load()
  },
  methods: {
    async load() {
      this.loading = true
      this.error = ''
      try {
        this.list = await api('/api/courses/hub')
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    async join(c) {
      if (this.busyId) return
      this.busyId = c.id
      this.error = ''
      try {
        await api('/api/courses/' + c.id + '/enroll', { method: 'POST', body: {} })
        c.enrolled = true
        c.memberCount = (c.memberCount || 0) + 1
        uni.showToast({ title: '已加入', icon: 'success' })
      } catch (e) {
        this.error = e.message
      } finally {
        this.busyId = null
      }
    },
    async leave(c) {
      if (this.busyId) return
      this.busyId = c.id
      this.error = ''
      try {
        await api('/api/courses/' + c.id + '/enroll', { method: 'DELETE' })
        c.enrolled = false
        c.memberCount = Math.max((c.memberCount || 1) - 1, 0)
        uni.showToast({ title: '已退出', icon: 'none' })
      } catch (e) {
        this.error = e.message
      } finally {
        this.busyId = null
      }
    },
    goPractice(c) {
      // 只预选课程不自动开卷，练习页 onShow 消费
      uni.setStorageSync(PENDING_KEY, { courseId: c.id })
      uni.switchTab({ url: '/pages/practice/practice' })
    }
  }
}
</script>

<style>
.wrap { padding: 20rpx 24rpx 60rpx; }
.card {
  background: #ffffff; border-radius: 20rpx;
  padding: 28rpx 30rpx; margin-bottom: 22rpx;
  box-shadow: 0 2rpx 10rpx rgba(31, 45, 90, 0.05);
}
.course-head { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; }
.course-name { font-size: 30rpx; font-weight: 700; flex: 1; min-width: 0; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.member { color: #9aa0ab; font-size: 22rpx; }
.course-desc { color: #6b7280; font-size: 25rpx; line-height: 1.7; margin-top: 12rpx; }
.course-ops { display: flex; gap: 18rpx; margin-top: 22rpx; }
.op-btn {
  margin: 0; flex: 1; height: 72rpx; line-height: 72rpx;
  border-radius: 14rpx; font-size: 26rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.op-btn.ghost { background: #ffffff; color: #6b7280; border: 1rpx solid #dcdfe6; }
.op-btn[disabled] { background: #c3cdfb; }
.empty-card { text-align: center; padding: 80rpx 30rpx; }
.empty-t { font-size: 29rpx; font-weight: 700; }
.empty-d { color: #9aa0ab; font-size: 24rpx; margin-top: 12rpx; line-height: 1.7; }
.loading { text-align: center; color: #9aa0ab; font-size: 25rpx; padding: 40rpx 0; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
