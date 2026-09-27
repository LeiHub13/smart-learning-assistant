<template>
  <view class="wrap">
    <view class="hero">
      <view class="logo">🤖</view>
      <view class="title">智能学习助手</view>
      <view class="sub">移动端 · AI 答疑 / 练习 / 错题本</view>
    </view>

    <view class="card">
      <input v-model="username" class="ipt" placeholder="用户名" placeholder-class="ph" />
      <input v-model="password" class="ipt" password placeholder="密码" placeholder-class="ph" @confirm="doLogin" />
      <view class="srv-row" @click="srvOpen = !srvOpen">
        <text class="srv-toggle">{{ srvOpen ? '▾' : '▸' }} 服务器地址</text>
        <text class="srv-val">{{ baseUrl }}</text>
      </view>
      <input v-if="srvOpen" v-model="baseUrl" class="ipt" placeholder="http://192.168.x.x:8080" placeholder-class="ph" />
      <view v-if="err" class="err">{{ err }}</view>
      <button class="btn" :disabled="loading" @click="doLogin">{{ loading ? '登录中…' : '登 录' }}</button>
      <view class="demo">演示账号：pg13 / 123456</view>
    </view>
  </view>
</template>

<script>
import { api, setToken, setUser } from '../../utils/api'
import { getBaseUrl, setBaseUrl } from '../../config'

export default {
  data() {
    return { username: '', password: '', err: '', loading: false, baseUrl: '', srvOpen: false }
  },
  onLoad() {
    this.baseUrl = getBaseUrl()
  },
  methods: {
    async doLogin() {
      if (this.loading) return
      const username = this.username.trim()
      const password = this.password
      if (!username || !password) {
        this.err = '请输入用户名和密码'
        return
      }
      if (/^https?:\/\/.+/.test(this.baseUrl.trim())) setBaseUrl(this.baseUrl.trim())
      this.loading = true
      this.err = ''
      try {
        const data = await api('/api/auth/login', { method: 'POST', body: { username, password } })
        if (!data || !(data.token || data.accessToken)) throw new Error('登录响应缺少 token')
        setToken(data.token || data.accessToken)
        setUser(data)
        uni.reLaunch({ url: '/pages/chat/chat' })
      } catch (e) {
        this.err = e.message || '登录失败'
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style>
.wrap {
  min-height: 100vh;
  background: linear-gradient(160deg, #4f6ef7 0%, #6d8bff 45%, #f5f6fa 100%);
  padding: 120rpx 60rpx 60rpx;
  box-sizing: border-box;
}
.hero { text-align: center; margin-bottom: 80rpx; }
.logo { font-size: 100rpx; }
.title { color: #ffffff; font-size: 44rpx; font-weight: 700; margin-top: 16rpx; }
.sub { color: rgba(255, 255, 255, 0.85); font-size: 26rpx; margin-top: 10rpx; }
.card {
  background: #ffffff;
  border-radius: 28rpx;
  padding: 50rpx 44rpx;
  box-shadow: 0 12rpx 40rpx rgba(31, 45, 90, 0.12);
}
.ipt {
  height: 92rpx;
  background: #f5f6fa;
  border-radius: 18rpx;
  padding: 0 28rpx;
  margin-bottom: 26rpx;
  font-size: 30rpx;
}
.ph { color: #b3b9c4; }
.err { color: #e5484d; font-size: 26rpx; margin: -6rpx 0 18rpx; }
.btn {
  margin-top: 10rpx;
  height: 92rpx;
  line-height: 92rpx;
  border-radius: 18rpx;
  background: #4f6ef7;
  color: #ffffff;
  font-size: 32rpx;
  font-weight: 600;
}
.demo { text-align: center; color: #9aa0ab; font-size: 24rpx; margin-top: 30rpx; }
.srv-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4rpx 6rpx 18rpx;
}
.srv-toggle { color: #6b7280; font-size: 25rpx; }
.srv-val {
  color: #9aa0ab; font-size: 22rpx; max-width: 60%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
</style>
