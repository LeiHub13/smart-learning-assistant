<template>
  <view class="wrap">
    <view class="user-card">
      <view class="avatar">{{ avatarChar }}</view>
      <view class="user-info">
        <view class="nickname">{{ user.nickname || user.username || '已登录' }}</view>
        <view class="username">{{ user.username || '' }}</view>
      </view>
    </view>

    <view class="section-title">学习功能</view>
    <view class="menu-card">
      <view v-for="m in menus" :key="m.url" class="menu-row" @click="goPage(m.url)">
        <text class="menu-ico">{{ m.ico }}</text>
        <text class="menu-name">{{ m.name }}</text>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="section-title">后端连接</view>
    <view class="cell-card">
      <view class="cell-label">服务器地址</view>
      <input v-model="baseUrl" class="cell-ipt" placeholder="http://192.168.x.x:8080" placeholder-class="ph" />
      <view class="cell-tip">真机调试请填电脑局域网 IP，改完点保存（App 内改地址无需重新打包）</view>
      <view class="ops">
        <button class="op" @click="saveBaseUrl">保存</button>
        <button class="op ghost" :disabled="testing" @click="testConn">{{ testing ? '测试中…' : '测试连接' }}</button>
      </view>
    </view>

    <button class="logout" @click="logout">退出登录</button>
    <view class="ver">智能学习助手移动端 v1.0.0</view>
  </view>
</template>

<script>
import { api, getUser, clearToken, clearUser } from '../../utils/api'
import { getBaseUrl, setBaseUrl, DEFAULT_BASE_URL } from '../../config'

export default {
  data() {
    return {
      user: {},
      baseUrl: '',
      testing: false,
      menus: [
        { ico: '💬', name: '对话厅', url: '/pages/hall/hall' },
        { ico: '📊', name: '学情总览', url: '/pages/dashboard/dashboard' },
        { ico: '📈', name: '学情分析', url: '/pages/progress/progress' },
        { ico: '📄', name: '学习报告', url: '/pages/report/report' },
        { ico: '✒️', name: '在线考试', url: '/pages/exam/exam' },
        { ico: '📚', name: '课程中心', url: '/pages/courses/courses' },
        { ico: '📝', name: '学习笔记', url: '/pages/notes/notes' },
        { ico: '⭐', name: '收藏夹', url: '/pages/favorites/favorites' },
        { ico: '✨', name: '讲义 / 练习题生成', url: '/pages/generate/generate' },
        { ico: '🏦', name: '题库浏览', url: '/pages/bank/bank' }
      ]
    }
  },
  computed: {
    avatarChar() {
      const n = this.user.nickname || this.user.username || '?'
      return String(n).charAt(0).toUpperCase()
    }
  },
  onLoad() {
    this.baseUrl = getBaseUrl() || DEFAULT_BASE_URL
  },
  onShow() {
    // tabBar 页实例化早于登录完成，用户信息每次显示时重读
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.user = getUser()
    // 登录响应只有 token，用户资料从 /api/auth/me 拉
    api('/api/auth/me')
      .then((me) => {
        if (me) {
          this.user = { ...this.user, ...me }
          setUser(this.user)
        }
      })
      .catch(() => {})
  },
  methods: {
    goPage(url) {
      uni.navigateTo({ url })
    },
    saveBaseUrl() {
      const url = this.baseUrl.trim()
      if (!/^https?:\/\/.+/.test(url)) {
        uni.showToast({ title: '地址需以 http(s):// 开头', icon: 'none' })
        return
      }
      setBaseUrl(url)
      this.baseUrl = getBaseUrl()
      uni.showToast({ title: '已保存', icon: 'success' })
    },
    async testConn() {
      this.testing = true
      try {
        await api('/api/courses')
        uni.showToast({ title: '连接成功', icon: 'success' })
      } catch (e) {
        uni.showModal({ title: '连接失败', content: e.message, showCancel: false })
      }
      this.testing = false
    },
    logout() {
      uni.showModal({
        title: '退出登录',
        content: '确定要退出当前账号吗？',
        success: (r) => {
          if (!r.confirm) return
          clearToken()
          clearUser()
          uni.removeStorageSync('la_chat_sid')
          uni.reLaunch({ url: '/pages/login/login' })
        }
      })
    }
  }
}
</script>

<style>
.wrap { padding: 30rpx 30rpx 60rpx; }
.user-card {
  display: flex; align-items: center; gap: 26rpx;
  background: #ffffff; border-radius: 24rpx;
  padding: 40rpx 36rpx;
  box-shadow: 0 4rpx 16rpx rgba(31, 45, 90, 0.05);
}
.avatar {
  width: 100rpx; height: 100rpx; border-radius: 50%;
  background: #4f6ef7; color: #ffffff;
  font-size: 44rpx; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.nickname { font-size: 34rpx; font-weight: 700; }
.username { font-size: 25rpx; color: #9aa0ab; margin-top: 6rpx; }
.section-title { color: #9aa0ab; font-size: 25rpx; margin: 36rpx 10rpx 16rpx; }
.menu-card { background: #ffffff; border-radius: 24rpx; overflow: hidden; }
.menu-row {
  display: flex; align-items: center; gap: 20rpx;
  padding: 28rpx 32rpx;
  border-bottom: 1rpx solid #f0f2f5;
}
.menu-row:last-child { border-bottom: none; }
.menu-ico { font-size: 34rpx; }
.menu-name { flex: 1; font-size: 28rpx; }
.menu-arrow { color: #c3c9d4; font-size: 32rpx; }
.cell-card { background: #ffffff; border-radius: 24rpx; padding: 30rpx 32rpx; }
.cell-label { font-size: 28rpx; font-weight: 600; }
.cell-ipt {
  margin-top: 20rpx; height: 84rpx;
  background: #f5f6fa; border-radius: 16rpx;
  padding: 0 26rpx; font-size: 27rpx;
}
.ph { color: #b3b9c4; }
.cell-tip { color: #9aa0ab; font-size: 23rpx; margin-top: 16rpx; line-height: 1.6; }
.ops { display: flex; gap: 18rpx; margin-top: 24rpx; }
.op {
  margin: 0; flex: 1; height: 72rpx; line-height: 72rpx;
  border-radius: 14rpx; font-size: 27rpx;
  background: #4f6ef7; color: #ffffff;
}
.op.ghost { background: #ffffff; color: #6b7280; border: 1rpx solid #dcdfe6; }
.logout {
  margin: 60rpx 0 0; height: 92rpx; line-height: 92rpx;
  border-radius: 20rpx; font-size: 30rpx;
  background: #ffffff; color: #e5484d;
  border: 1rpx solid #f2d5d6;
}
.ver { text-align: center; color: #b3b9c4; font-size: 23rpx; margin-top: 40rpx; }
</style>
