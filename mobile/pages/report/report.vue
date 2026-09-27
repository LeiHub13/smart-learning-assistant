<template>
  <view class="wrap">
    <view class="card">
      <view class="toolbar">
        <picker class="toolbar-course" mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
          <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
        </picker>
        <button class="gen-btn" :disabled="loading" @click="genReport">{{ loading ? '生成中…' : '生成周报' }}</button>
      </view>
    </view>

    <view v-if="reports.length" class="card">
      <view class="card-title">报告列表</view>
      <view v-for="r in reports" :key="r.id" class="report-item">
        <view class="report-head" @click="show(r)">
          <text class="report-title" user-select>{{ r.title }}</text>
          <text class="report-time">{{ fmtTime(r.createdAt) }}</text>
        </view>
        <view v-if="current && current.id === r.id" class="report-body">
          <view class="report-md">
            <mp-html :content="mdToHtml(current.content)" :selectable="true" />
          </view>
          <view class="pdf-tip">PDF 导出请在网页端操作</view>
        </view>
      </view>
    </view>

    <view v-if="!reports.length && loaded && !error" class="card empty-card">
      <view class="empty-t">还没有学习报告</view>
      <view class="empty-d">选择课程点「生成周报」，AI 聚合一周学习数据</view>
    </view>

    <view v-if="error" class="err">{{ error }}</view>
  </view>
</template>

<script>
import { api, getCourses } from '../../utils/api'
import { fmtTime } from '../../utils/format'
import { mdToHtml } from '../../utils/md'

export default {
  data() {
    return {
      courses: [],
      courseId: null,
      courseIndex: 0,
      reports: [],
      current: null,
      loading: false,
      loaded: false,
      error: ''
    }
  },
  computed: {
    courseNames() {
      return this.courses.map((c) => c.name)
    }
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
    if (!this.courses.length) this.init()
  },
  methods: {
    fmtTime,
    mdToHtml,
    async init() {
      this.error = ''
      try {
        this.courses = await getCourses()
        if (this.courses.length && !this.courseId) this.courseId = this.courses[0].id
        this.reports = await api('/api/reports')
        this.loaded = true
      } catch (e) {
        this.error = e.message
      }
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
    },
    async genReport() {
      if (this.loading || !this.courseId) {
        if (!this.courseId) uni.showToast({ title: '请先选择课程', icon: 'none' })
        return
      }
      this.loading = true
      this.error = ''
      try {
        const r = await api('/api/reports/weekly?courseId=' + this.courseId, { method: 'POST' })
        this.reports.unshift(r)
        this.current = r
        uni.showToast({ title: '周报已生成', icon: 'success' })
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    show(r) {
      this.current = this.current && this.current.id === r.id ? null : r
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
.card-title { font-size: 29rpx; font-weight: 700; margin-bottom: 14rpx; }
.toolbar { display: flex; align-items: center; gap: 18rpx; }
.toolbar-course { flex: 1; }
.pick-val {
  height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.gen-btn {
  margin: 0; height: 72rpx; line-height: 72rpx; padding: 0 30rpx;
  border-radius: 14rpx; font-size: 26rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.gen-btn[disabled] { background: #c3cdfb; }
.report-item { border: 1rpx solid #eceef2; border-radius: 14rpx; margin-bottom: 16rpx; overflow: hidden; }
.report-head {
  display: flex; justify-content: space-between; align-items: center; gap: 14rpx;
  padding: 22rpx 26rpx; background: #f7f8fc;
}
.report-title { font-weight: 700; font-size: 27rpx; flex: 1; min-width: 0; }
.report-time { color: #9aa0ab; font-size: 22rpx; flex-shrink: 0; }
.report-body { padding: 22rpx 26rpx; }
.report-md { font-size: 26rpx; }
.pdf-tip { color: #b3b9c4; font-size: 22rpx; margin-top: 16rpx; text-align: right; }
.empty-card { text-align: center; padding: 80rpx 30rpx; }
.empty-t { font-size: 29rpx; font-weight: 700; }
.empty-d { color: #9aa0ab; font-size: 24rpx; margin-top: 12rpx; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
