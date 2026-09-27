<template>
  <view class="wrap">
    <view class="hello">
      <text class="hello-t">你好，{{ me.nickname || me.username || '同学' }} 👋</text>
      <text class="hello-s">今天也保持学习节奏 —— 概览、推荐与快捷入口</text>
    </view>

    <!-- 数据概览 -->
    <view class="card">
      <view class="stat-grid">
        <view class="stat"><text class="num">{{ fmtHours(study.totalMinutes) }}</text><text class="stat-lab">学习时长</text></view>
        <view class="stat"><text class="num">{{ streak }} 天</text><text class="stat-lab">连续学习</text></view>
        <view class="stat"><text class="num">{{ study.activeDays || 0 }}</text><text class="stat-lab">活跃天数</text></view>
        <view class="stat"><text class="num">{{ practiceCount }}</text><text class="stat-lab">练习次数</text></view>
        <view class="stat"><text class="num">{{ favCount }}</text><text class="stat-lab">收藏题目</text></view>
        <view class="stat"><text class="num">{{ unread }}</text><text class="stat-lab">未读通知</text></view>
      </view>
    </view>

    <!-- 快捷入口 -->
    <view class="card quick-card">
      <view class="quick-row">
        <view class="quick-btn" @click="goPractice">📝 开始练习</view>
        <view class="quick-btn" @click="goTab('/pages/mistake/mistake')">🎯 错题本{{ mistakeTotal ? '（' + mistakeTotal + '）' : '' }}</view>
        <view class="quick-btn" @click="goPage('/pages/favorites/favorites')">⭐ 收藏夹{{ favCount ? '（' + favCount + '）' : '' }}</view>
        <view class="quick-btn ghost" @click="goPage('/pages/exam/exam')">✒️ 进入考试</view>
        <view class="quick-btn ghost" @click="goTab('/pages/chat/chat')">💬 智能答疑</view>
        <view class="quick-btn ghost" @click="goPage('/pages/progress/progress')">📈 查看学情</view>
      </view>
    </view>

    <!-- 成绩曲线 -->
    <view class="card">
      <view class="card-title">最近成绩曲线</view>
      <template v-if="seriesList.length">
        <line-chart :series="seriesList" :active-id="activeId" />
        <view class="legend">
          <view
            v-for="s in seriesList"
            :key="s.id"
            class="legend-item"
            :class="{ active: activeId === s.id }"
            @click="toggleSeries(s.id)"
          >
            <view class="legend-dot" :style="{ background: s.color }" />
            <text class="legend-name">{{ s.name }}</text>
          </view>
        </view>
      </template>
      <view v-else class="empty-sm">暂无练习数据</view>
    </view>

    <!-- 学习热力图 -->
    <view class="card">
      <view class="hm-top">
        <text class="card-title">学习热力图（近 12 周）</text>
      </view>
      <view v-if="heat">
        <view class="hm-stats">累计 <text class="b">{{ fmtHeat(heat.total) }}</text> · 活跃 <text class="b">{{ heat.activeDays }}</text> 天 · 最长连续 <text class="b">{{ heat.best }}</text> 天</view>
        <scroll-view scroll-x class="hm-scroll" :show-scrollbar="false">
          <view class="hm-inner">
            <view class="hm-dow"><text>一</text><text>三</text><text>五</text></view>
            <view class="heatmap">
              <template v-for="(w, wi) in heat.weeks">
                <view
                  v-for="(c, ci) in w"
                  :key="wi + '-' + ci"
                  class="hm-cell"
                  :class="c ? hmLevel(c.minutes) : 'pad'"
                />
              </template>
            </view>
          </view>
        </scroll-view>
        <view class="hm-legend">少 <view class="hm-cell hm-0"/><view class="hm-cell hm-1"/><view class="hm-cell hm-2"/><view class="hm-cell hm-3"/><view class="hm-cell hm-4"/> 多（按当日学习分钟数分档）</view>
      </view>
      <view v-else class="empty-sm">暂无数据（使用系统期间每分钟自动记录）</view>
    </view>

    <!-- 今日推荐 -->
    <view class="card">
      <view class="card-title">今日推荐</view>
      <view v-if="!recommend.length" class="empty-sm">暂无推荐，做一组练习后生成</view>
      <view v-for="(r, i) in recommend" :key="i" class="rc-item" :class="{ clickable: r.action }" @click="openRecommend(r)">
        <text class="rc-icon">{{ rcIcon(r.type) }}</text>
        <view class="rc-body">
          <view class="rc-title" user-select>{{ r.title }}</view>
          <view class="rc-reason" user-select>{{ r.reason }}</view>
          <view v-if="r.snippet" class="rc-snippet" @click.stop="expandSnippet(i)">{{ snippetOpen[i] ? '收起资料原文 ▴' : '展开资料原文 ▾' }}</view>
          <view v-if="r.snippet && snippetOpen[i]" class="rc-snippet-txt" user-select>{{ r.snippet }}</view>
        </view>
        <text v-if="r.action" class="rc-go">{{ goLabel(r.type) }}</text>
      </view>
    </view>
  </view>
</template>

<script>
import { api } from '../../utils/api'
import LineChart from '../../components/line-chart/line-chart.vue'

const PALETTE = ['#0969da', '#4a6cf7', '#3aa675', '#c05a4d', '#8b6fc0', '#4bacc6', '#f59e0b']
const DOW = ['一', '二', '三', '四', '五', '六', '日']
const PENDING_KEY = 'la_practice_pending'

export default {
  components: { LineChart },
  data() {
    return {
      me: {},
      study: {},
      recommend: [],
      seriesList: [],
      activeId: null,
      practiceCount: 0,
      favCount: 0,
      unread: 0,
      mistakeTotal: 0,
      snippetOpen: {}
    }
  },
  computed: {
    // 连续学习天数：今天还没学不打断，从昨天起算
    streak() {
      const map = new Map((this.study.calendar || []).map((c) => [c.date, c.minutes || 0]))
      const fmt = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
      const cur = new Date()
      if (!(map.get(fmt(cur)) > 0)) cur.setDate(cur.getDate() - 1)
      let n = 0
      while (map.get(fmt(cur)) > 0) {
        n++
        cur.setDate(cur.getDate() - 1)
      }
      return n
    },
    // 84 天日历 → 周对齐热力图
    heat() {
      const cal = this.study.calendar || []
      if (!cal.length) return null
      const cells = cal.map((c) => {
        const dow = (new Date(c.date + 'T00:00:00').getDay() + 6) % 7
        return { ...c, dow }
      })
      const weeks = []
      let week = Array(cells[0].dow).fill(null)
      for (const c of cells) {
        week.push(c)
        if (week.length === 7) {
          weeks.push(week)
          week = []
        }
      }
      if (week.length) weeks.push(week.concat(Array(7 - week.length).fill(null)))
      const total = cal.reduce((s, c) => s + (c.minutes || 0), 0)
      const activeDays = cal.filter((c) => c.minutes > 0).length
      let run = 0
      let best = 0
      for (const c of cal) {
        run = c.minutes > 0 ? run + 1 : 0
        if (run > best) best = run
      }
      return { weeks, total, activeDays, best }
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
    this.refresh()
  },
  methods: {
    async refresh() {
      api('/api/auth/me').then((m) => { this.me = m || {} }).catch(() => {})
      api('/api/study/summary').then((s) => { this.study = s || {} }).catch(() => {})
      api('/api/notifications/unread-count').then((c) => { this.unread = (c && c.count) || 0 }).catch(() => {})
      api('/api/practice/history?size=1').then((h) => { this.practiceCount = (h && h.total) || 0 }).catch(() => {})
      api('/api/favorites/count').then((c) => { this.favCount = (c && c.total) || 0 }).catch(() => {})
      api('/api/mistakes?size=1').then((m) => { this.mistakeTotal = (m && m.total) || 0 }).catch(() => {})
      try {
        const courses = await api('/api/courses')
        const first = courses.length ? courses[0].id : null
        if (first) {
          api('/api/recommend?courseId=' + first).then((r) => { this.recommend = r || [] }).catch(() => {})
          Promise.all(courses.map(async (c, i) => {
            try {
              const t = await api('/api/practice/trend?courseId=' + c.id)
              if (!t.length) return null
              return { id: c.id, name: c.name, color: PALETTE[i % PALETTE.length], points: t.map((x) => [x.date, x.rate]) }
            } catch (e) {
              return null
            }
          })).then((list) => { this.seriesList = list.filter(Boolean) })
        }
      } catch (e) { /* 课程为空时仅展示统计 */ }
    },
    fmtHours(m) {
      if (!m) return '0h'
      return m >= 60 ? Math.round(m / 6) / 10 + 'h' : m + 'min'
    },
    fmtHeat(m) {
      return m >= 60 ? Math.round(m / 6) / 10 + ' 小时' : m + ' 分钟'
    },
    hmLevel(m) {
      return m >= 120 ? 'hm-4' : m >= 60 ? 'hm-3' : m >= 30 ? 'hm-2' : m >= 10 ? 'hm-1' : 'hm-0'
    },
    rcIcon(t) {
      return { startup: '🚀', review_kp: '⏰', practice_kp: '📝', document: '📄' }[t] || '💡'
    },
    goLabel(t) {
      return t === 'startup' ? '去摸底 →' : '去做题 →'
    },
    expandSnippet(i) {
      this.snippetOpen[i] = !this.snippetOpen[i]
    },
    toggleSeries(id) {
      this.activeId = this.activeId === id ? null : id
    },
    goTab(url) {
      uni.switchTab({ url })
    },
    goPage(url) {
      uni.navigateTo({ url })
    },
    goPractice() {
      // 今日推荐外的主入口：只预选，不自动开卷
      uni.switchTab({ url: '/pages/practice/practice' })
    },
    openRecommend(r) {
      if (!r.action) return
      const a = r.action
      if (a.indexOf('/practice') === 0) {
        const kp = (a.match(/[?&]kp=([^&]+)/) || [])[1]
        const courseId = (a.match(/[?&]courseId=([^&]+)/) || [])[1]
        uni.setStorageSync(PENDING_KEY, { kp: kp ? decodeURIComponent(kp) : '', courseId: courseId ? Number(courseId) : null })
        uni.switchTab({ url: '/pages/practice/practice' })
      } else if (a.indexOf('/mistakes') === 0) {
        uni.switchTab({ url: '/pages/mistake/mistake' })
      } else if (a.indexOf('/chat') === 0) {
        uni.switchTab({ url: '/pages/chat/chat' })
      } else if (a.indexOf('/favorites') === 0) {
        uni.navigateTo({ url: '/pages/favorites/favorites' })
      } else if (a.indexOf('/progress') === 0) {
        uni.navigateTo({ url: '/pages/progress/progress' })
      } else if (a.indexOf('/exam') === 0) {
        uni.navigateTo({ url: '/pages/exam/exam' })
      } else {
        uni.showToast({ title: '请在网页端完成该操作', icon: 'none' })
      }
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
.hello { padding: 6rpx 6rpx 22rpx; display: flex; flex-direction: column; }
.hello-t { font-size: 36rpx; font-weight: 800; }
.hello-s { color: #9aa0ab; font-size: 24rpx; margin-top: 8rpx; }
.card-title { font-size: 29rpx; font-weight: 700; }
.stat-grid { display: flex; flex-wrap: wrap; }
.stat { width: 33.33%; text-align: center; padding: 14rpx 0; }
.num { font-size: 34rpx; font-weight: 800; display: block; }
.stat-lab { color: #9aa0ab; font-size: 22rpx; margin-top: 6rpx; }
.quick-row { display: flex; flex-wrap: wrap; gap: 16rpx; }
.quick-btn {
  padding: 16rpx 26rpx; border-radius: 14rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 25rpx; font-weight: 600;
}
.quick-btn.ghost { background: #ffffff; color: #6b7280; border: 1rpx solid #dcdfe6; }
.legend { display: flex; flex-wrap: wrap; gap: 14rpx; margin-top: 14rpx; }
.legend-item { display: flex; align-items: center; gap: 8rpx; padding: 6rpx 16rpx; border-radius: 10rpx; }
.legend-item.active { background: #f5f6fa; }
.legend-dot { width: 16rpx; height: 16rpx; border-radius: 50%; }
.legend-name { font-size: 22rpx; color: #6b7280; max-width: 200rpx; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-sm { color: #b3b9c4; font-size: 25rpx; text-align: center; padding: 24rpx 0; }
.hm-stats { color: #9aa0ab; font-size: 23rpx; margin: 14rpx 0; }
.hm-stats .b { color: #2b2f36; font-weight: 700; }
.hm-scroll { width: 100%; }
.hm-inner { display: flex; gap: 8rpx; padding: 4rpx 2rpx 8rpx; }
.hm-dow { display: flex; flex-direction: column; gap: 6rpx; width: 26rpx; }
.hm-dow text { font-size: 17rpx; color: #9aa0ab; line-height: 22rpx; height: 22rpx; }
.heatmap { display: flex; flex-direction: column; flex-wrap: wrap; gap: 6rpx; height: 196rpx; }
.hm-cell { width: 22rpx; height: 22rpx; border-radius: 5rpx; background: #ebedf0; }
.hm-cell.pad { visibility: hidden; }
.hm-1 { background: #9be9a8; }
.hm-2 { background: #40c463; }
.hm-3 { background: #30a14e; }
.hm-4 { background: #216e39; }
.hm-legend { display: flex; align-items: center; gap: 8rpx; margin-top: 14rpx; font-size: 20rpx; color: #9aa0ab; }
.hm-legend .hm-cell { width: 20rpx; height: 20rpx; }
.rc-item { display: flex; gap: 16rpx; padding: 16rpx 0; border-bottom: 1rpx solid #eceef2; }
.rc-item:last-child { border-bottom: none; }
.rc-icon { font-size: 30rpx; }
.rc-body { flex: 1; min-width: 0; }
.rc-title { font-weight: 700; font-size: 26rpx; }
.rc-reason { color: #9aa0ab; font-size: 23rpx; margin-top: 4rpx; line-height: 1.6; }
.rc-snippet { color: #4f6ef7; font-size: 22rpx; margin-top: 10rpx; }
.rc-snippet-txt {
  font-size: 22rpx; color: #57606a; line-height: 1.7;
  background: #f7f8fc; border: 1rpx solid #eceef2; border-radius: 10rpx;
  padding: 12rpx 16rpx; margin-top: 8rpx;
}
.rc-go { flex-shrink: 0; align-self: center; font-size: 22rpx; font-weight: 600; color: #4f6ef7; }
</style>
