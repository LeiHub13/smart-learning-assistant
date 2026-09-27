<template>
  <view class="wrap">
    <view class="card">
      <view class="toolbar">
        <picker class="toolbar-course" mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
          <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
        </picker>
        <button class="refresh-btn" :disabled="loadingSummary" @click="load">{{ loadingSummary ? '加载中…' : '刷新' }}</button>
      </view>
    </view>

    <view v-if="!summary && !loadingSummary" class="card empty-card">正在加载学情数据…</view>

    <template v-if="summary">
      <!-- 今日推荐 -->
      <view class="card">
        <view class="card-title">今日推荐</view>
        <view v-if="!recommend.length" class="empty-sm">暂无推荐，做一组练习后生成</view>
        <view v-for="(r, i) in recommend" :key="i" class="rc-item">
          <text class="rc-icon">{{ rcIcon(r.type) }}</text>
          <view class="rc-body">
            <view class="rc-title" user-select>{{ r.title }}</view>
            <view class="rc-reason" user-select>{{ r.reason }}</view>
          </view>
        </view>
      </view>

      <!-- 总体指标 -->
      <view class="card">
        <view class="card-title">总体指标</view>
        <view class="stat-row">
          <view class="stat"><text class="num">{{ Math.round(summary.averageMastery) }}%</text><text class="stat-lab">平均掌握度</text></view>
          <view class="stat"><text class="num">{{ summary.totalAttempts }}</text><text class="stat-lab">累计答题</text></view>
          <view class="stat"><text class="num">{{ summary.wrongBook.length }}</text><text class="stat-lab">错题数</text></view>
        </view>
      </view>

      <!-- 学习时长 -->
      <view class="card">
        <view class="card-title">学习时长</view>
        <view class="stat-row">
          <view class="stat"><text class="num">{{ fmtHours(study.totalMinutes) }}</text><text class="stat-lab">总时长</text></view>
          <view class="stat"><text class="num">{{ study.activeDays || 0 }}</text><text class="stat-lab">活跃天数</text></view>
        </view>
        <scroll-view v-if="study.calendar && study.calendar.length" scroll-x class="hm-scroll" :show-scrollbar="false">
          <view class="heatmap">
            <view v-for="c in study.calendar" :key="c.date" class="hm-cell" :class="hmLevel(c.minutes)" />
          </view>
        </scroll-view>
        <view v-if="study.calendar && study.calendar.length" class="hm-legend">近 12 周 ·
          <view class="hm-cell hm-0"/><view class="hm-cell hm-1"/><view class="hm-cell hm-2"/><view class="hm-cell hm-3"/><view class="hm-cell hm-4"/>
          无 → ≥2h
        </view>
        <view v-if="study.byCourse && study.byCourse.length" class="by-course">
          <view v-for="c in study.byCourse" :key="c.courseId" class="bc-row">
            <text class="bc-name">{{ c.courseName }}</text>
            <text class="bc-val">{{ fmtHours(c.minutes) }}</text>
          </view>
        </view>
        <view v-else class="empty-sm">暂无课程时长数据（页面停留期间每分钟自动记录）</view>
      </view>

      <!-- AI 复习建议 -->
      <view class="card">
        <view class="sec-head">
          <text class="card-title">AI 复习建议</text>
          <text v-if="summary.adviceCached" class="tag">缓存 · 7天内有效</text>
          <button
            v-if="summary.masteries.length"
            class="advice-btn"
            :disabled="refreshingAdvice"
            @click="refreshAdvice"
          >
            {{ refreshingAdvice ? 'AI 分析中…' : (summary.advice ? '重新生成' : '开始分析') }}
          </button>
        </view>
        <view v-if="summary.advice" class="advice-md">
          <mp-html :content="mdToHtml(summary.advice)" :selectable="true" />
        </view>
        <view v-else-if="refreshingAdvice" class="empty-sm">AI 正在分析学情，请稍候…</view>
        <view v-else-if="!summary.masteries.length" class="empty-sm">暂无练习数据，去「练习」做一组题后再来分析</view>
        <view v-else class="empty-sm">尚未分析，点「开始分析」让 AI 基于掌握度给出复习建议</view>
      </view>

      <!-- 知识点掌握度 -->
      <view class="card">
        <view class="card-title">知识点掌握度</view>
        <view v-if="!summary.masteries.length" class="empty-sm">暂无练习数据，去「练习」做一组题后生成</view>
        <view v-for="m in summary.masteries" :key="m.id" class="mb">
          <view class="mb-head">
            <text class="mb-name">{{ m.kpName }}</text>
            <text class="mb-val">{{ Math.round(m.mastery) }}%</text>
          </view>
          <view class="mb-track"><view class="mb-fill" :style="{ width: m.mastery + '%' }" /></view>
          <text class="mb-ct">{{ m.correctCount }}/{{ m.attempts }} 对</text>
        </view>
      </view>

      <!-- 错题本 -->
      <view class="card">
        <view class="card-title">错题本</view>
        <view v-if="!summary.wrongBook.length" class="empty-sm">太棒了，暂无错题</view>
        <view v-for="(w, i) in summary.wrongBook" :key="i" class="wb-card">
          <view class="wb-head">
            <text class="wb-no">#{{ i + 1 }}</text>
            <text v-if="w.kpName" class="tag bad">{{ w.kpName }}</text>
            <text class="wb-time">{{ fmtTime(w.wrongAt) }}</text>
          </view>
          <view class="wb-stem" user-select>{{ w.stem }}</view>
          <view class="wb-ans wrong">
            <view class="wb-ans-label">✗ 我的答案</view>
            <view class="wb-ans-text" user-select>{{ w.userAnswer || '未作答' }}</view>
          </view>
          <view class="wb-ans right">
            <view class="wb-ans-label">✓ 参考答案</view>
            <view class="wb-ans-text" user-select>{{ w.answer }}</view>
          </view>
        </view>
      </view>
    </template>
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
      summary: null,
      study: {},
      recommend: [],
      refreshingAdvice: false,
      loadingSummary: false,
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
      try {
        this.courses = await getCourses()
        if (this.courses.length) {
          this.courseId = this.courses[0].id
          this.load()
        }
      } catch (e) { /* 课程为空 */ }
      api('/api/study/summary').then((s) => { this.study = s || {} }).catch(() => {})
      this.loadRecommend()
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
      this.load()
      this.loadRecommend()
    },
    loadRecommend() {
      if (!this.courseId) return
      api('/api/recommend?courseId=' + this.courseId)
        .then((r) => { this.recommend = r || [] })
        .catch(() => { this.recommend = [] })
    },
    async load() {
      if (!this.courseId) return
      this.loadingSummary = true
      try {
        this.summary = await api('/api/progress/summary?courseId=' + this.courseId)
      } catch (e) {
        this.error = e.message
      } finally {
        this.loadingSummary = false
      }
    },
    async refreshAdvice() {
      if (!this.courseId || this.refreshingAdvice) return
      this.refreshingAdvice = true
      try {
        const r = await api('/api/progress/advice/refresh?courseId=' + this.courseId, { method: 'POST' })
        if (this.summary) {
          this.summary.advice = r.advice
          this.summary.adviceCached = false
        }
      } catch (e) { /* 保留旧建议 */ } finally {
        this.refreshingAdvice = false
      }
    },
    fmtHours(m) {
      if (!m) return '0h'
      return m >= 60 ? Math.round(m / 6) / 10 + 'h' : m + 'min'
    },
    hmLevel(minutes) {
      if (!minutes) return 'hm-0'
      if (minutes < 30) return 'hm-1'
      if (minutes < 60) return 'hm-2'
      if (minutes < 120) return 'hm-3'
      return 'hm-4'
    },
    rcIcon(t) {
      return { startup: '🚀', review_kp: '⏰', practice_kp: '📝', document: '📄' }[t] || '💡'
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
.card-title { font-size: 29rpx; font-weight: 700; }
.toolbar { display: flex; align-items: center; gap: 18rpx; }
.toolbar-course { flex: 1; }
.pick-val {
  height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.refresh-btn {
  margin: 0; height: 72rpx; line-height: 72rpx; padding: 0 30rpx;
  border-radius: 14rpx; font-size: 26rpx;
  background: #ffffff; color: #4f6ef7; border: 1rpx solid #c3cdfb;
}
.stat-row { display: flex; margin-top: 14rpx; }
.stat { flex: 1; text-align: center; }
.num { font-size: 34rpx; font-weight: 800; display: block; }
.stat-lab { color: #9aa0ab; font-size: 22rpx; margin-top: 6rpx; }
.hm-scroll { width: 100%; margin-top: 14rpx; }
.heatmap { display: flex; flex-direction: column; flex-wrap: wrap; gap: 6rpx; height: 190rpx; }
.hm-cell { width: 22rpx; height: 22rpx; border-radius: 5rpx; background: #ebedf0; }
.hm-1 { background: #9be9a8; }
.hm-2 { background: #40c463; }
.hm-3 { background: #30a14e; }
.hm-4 { background: #216e39; }
.hm-legend { display: flex; align-items: center; gap: 8rpx; margin: 14rpx 0; font-size: 20rpx; color: #9aa0ab; }
.hm-legend .hm-cell { width: 20rpx; height: 20rpx; }
.by-course { margin-top: 8rpx; }
.bc-row { display: flex; justify-content: space-between; padding: 14rpx 4rpx; border-bottom: 1rpx solid #eceef2; }
.bc-name { font-size: 26rpx; }
.bc-val { font-weight: 700; font-size: 26rpx; }
.sec-head { display: flex; align-items: center; gap: 14rpx; margin-bottom: 14rpx; flex-wrap: wrap; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.tag.bad { background: #f9e2e0; color: #b3423a; }
.advice-btn {
  margin: 0 0 0 auto; height: 60rpx; line-height: 60rpx; padding: 0 26rpx;
  border-radius: 12rpx; font-size: 24rpx;
  background: #4f6ef7; color: #ffffff;
}
.advice-btn[disabled] { background: #c3cdfb; }
.advice-md { font-size: 26rpx; }
.rc-item { display: flex; gap: 16rpx; padding: 14rpx 0; border-bottom: 1rpx solid #eceef2; }
.rc-item:last-child { border-bottom: none; }
.rc-icon { font-size: 28rpx; }
.rc-body { flex: 1; min-width: 0; }
.rc-title { font-weight: 700; font-size: 26rpx; }
.rc-reason { color: #9aa0ab; font-size: 23rpx; margin-top: 4rpx; line-height: 1.6; }
.mb { margin-bottom: 24rpx; }
.mb-head { display: flex; justify-content: space-between; margin-bottom: 10rpx; }
.mb-name { font-size: 26rpx; font-weight: 600; }
.mb-val { font-weight: 700; color: #4f6ef7; }
.mb-track { height: 14rpx; background: #f5f6fa; border-radius: 999rpx; overflow: hidden; }
.mb-fill { height: 100%; background: #4f6ef7; border-radius: 999rpx; }
.mb-ct { color: #9aa0ab; font-size: 21rpx; margin-top: 8rpx; display: block; }
.wb-card {
  border: 1rpx solid #eceef2; border-radius: 16rpx;
  padding: 22rpx 26rpx; margin-bottom: 18rpx; background: #ffffff;
}
.wb-head { display: flex; align-items: center; gap: 12rpx; margin-bottom: 12rpx; }
.wb-no {
  font-size: 20rpx; font-weight: 800; color: #ffffff;
  background: linear-gradient(135deg, #e06c5a, #c94f4f);
  border-radius: 999rpx; padding: 4rpx 16rpx;
}
.wb-time { margin-left: auto; font-size: 21rpx; color: #9aa0ab; }
.wb-stem {
  font-size: 26rpx; line-height: 1.7;
  background: #faf9f6; border: 1rpx solid #eeeae2; border-radius: 12rpx;
  padding: 16rpx 20rpx; margin-bottom: 14rpx;
}
.wb-ans { border-radius: 12rpx; padding: 16rpx 20rpx; margin-bottom: 12rpx; }
.wb-ans.wrong { background: #fdf1f0; border: 1rpx solid #f0cecb; }
.wb-ans.right { background: #f1f8f1; border: 1rpx solid #cfe4d1; }
.wb-ans-label { font-size: 22rpx; font-weight: 700; margin-bottom: 8rpx; }
.wb-ans.wrong .wb-ans-label { color: #c94f4f; }
.wb-ans.right .wb-ans-label { color: #3a8f52; }
.wb-ans-text { font-size: 25rpx; line-height: 1.7; word-break: break-word; }
.empty-sm { color: #b3b9c4; font-size: 25rpx; text-align: center; padding: 20rpx 0; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
