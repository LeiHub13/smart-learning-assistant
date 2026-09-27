<template>
  <view class="wrap">
    <!-- 打卡日历 -->
    <view class="card">
      <view class="cal-head">
        <text class="card-title">打卡日历 · {{ calMonth }}</text>
        <view class="cal-ops">
          <text class="cal-arrow" @click="shiftMonth(-1)">‹</text>
          <text class="cal-arrow" @click="shiftMonth(1)">›</text>
        </view>
      </view>
      <view class="cal-grid">
        <text v-for="d in weekHeads" :key="'h' + d" class="cal-h">{{ d }}</text>
        <view v-for="b in calFirstOffset" :key="'b' + b" class="cal-cell blank" />
        <view v-for="d in calDays" :key="d.date" class="cal-cell" :class="calLevel(d)">
          <text class="cal-day">{{ Number(d.date.slice(-2)) }}</text>
          <text v-if="d.total" class="cal-cnt">{{ d.done }}/{{ d.total }}</text>
        </view>
      </view>
      <view class="cal-legend">绿=全部完成 · 橙=部分完成 · 灰=无任务</view>
    </view>

    <!-- 创建新计划 -->
    <view class="card">
      <view class="card-title">创建新计划</view>
      <input
        v-model="goal"
        class="ipt"
        placeholder="输入学习目标，如：掌握 Java 集合框架"
        placeholder-class="ph"
      />
      <view class="pick-row">
        <picker class="pick-item" mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
          <view class="pick-box">
            <text class="lab">课程</text>
            <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
          </view>
        </picker>
        <picker class="pick-item" mode="selector" :range="dayOptions" :value="dayIndex" @change="onDayChange">
          <view class="pick-box">
            <text class="lab">天数</text>
            <view class="pick-val">{{ dayOptions[dayIndex] }} 天</view>
          </view>
        </picker>
      </view>
      <button class="btn" :disabled="creating || !goal.trim() || !courseId" @click="createPlan">
        {{ creating ? 'AI 生成中…' : '生成计划' }}
      </button>
    </view>

    <!-- 我的计划 -->
    <view v-for="p in plans" :key="p.id" class="card plan-card">
      <view class="plan-head" @click="toggle(p.id)">
        <view class="plan-head-l">
          <text class="plan-goal" user-select>{{ p.goal }}</text>
          <text class="tag" :class="p.status === 'done' ? 'ok' : ''">{{ p.status === 'done' ? '已完成' : '进行中' }}</text>
        </view>
        <text class="plan-days">{{ p.days }} 天</text>
      </view>
      <template v-if="openMap[p.id] && details[p.id]">
        <view class="progress">
          <view class="progress-fill" :style="{ width: progress(p.id) + '%' }" />
        </view>
        <view
          v-for="t in details[p.id].tasks"
          :key="t.id"
          class="task"
          :class="{ done: t.done }"
          @click="checkIn(t)"
        >
          <text class="cb">{{ t.done ? '✅' : '⬜' }}</text>
          <view class="task-body">
            <view class="task-title"><text class="task-day">第 {{ t.dayNo }} 天</text>{{ t.title }}</view>
            <view class="task-desc" user-select>{{ t.tasks }}</view>
            <text v-if="t.focusKp" class="tag small-tag">重点：{{ t.focusKp }}</text>
          </view>
        </view>
        <view class="plan-del" @click="askDelete(p)">删除计划</view>
      </template>
    </view>

    <view v-if="!plans.length && loaded" class="card empty-card">
      <view class="empty-ico">📅</view>
      <view class="empty-t">还没有学习计划</view>
      <view class="empty-d">设定一个目标，AI 为你生成逐日计划</view>
    </view>

    <view v-if="error" class="err">{{ error }}</view>
  </view>
</template>

<script>
import { api, getCourses } from '../../utils/api'

const DAY_OPTIONS = [3, 5, 7, 10, 14, 21, 30]

export default {
  data() {
    return {
      weekHeads: ['一', '二', '三', '四', '五', '六', '日'],
      calMonth: '',
      calDays: [],
      calFirstOffset: 0,
      courses: [],
      courseId: null,
      courseIndex: 0,
      dayOptions: DAY_OPTIONS,
      dayIndex: 2,
      goal: '',
      plans: [],
      openMap: {},
      details: {},
      creating: false,
      loaded: false,
      checking: {},
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
      return
    }
    // 本地时间拼月份：toISOString 是 UTC，月初清晨会算成上个月
    const d = new Date()
    this.calMonth = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0')
  },
  onShow() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (!this.courses.length) this.load()
    this.loadCalendar()
  },
  methods: {
    async load() {
      this.error = ''
      try {
        this.courses = await getCourses()
        if (this.courses.length && !this.courseId) this.courseId = this.courses[0].id
        this.plans = await api('/api/plans')
        if (this.plans.length && !Object.keys(this.openMap).length) {
          this.openMap[this.plans[0].id] = true
          await this.loadDetail(this.plans[0].id)
        }
        this.loaded = true
      } catch (e) {
        this.error = e.message
      }
    },
    async loadCalendar() {
      try {
        const days = await api('/api/plans/calendar?month=' + this.calMonth)
        this.calDays = days
        const [y, m] = this.calMonth.split('-').map(Number)
        const dow = new Date(y, m - 1, 1).getDay()
        this.calFirstOffset = (dow + 6) % 7 // 周一开头
      } catch (e) {
        this.error = '打卡日历加载失败：' + e.message
      }
    },
    shiftMonth(delta) {
      const [y, m] = this.calMonth.split('-').map(Number)
      const dt = new Date(y, m - 1 + delta, 1)
      this.calMonth = dt.getFullYear() + '-' + String(dt.getMonth() + 1).padStart(2, '0')
      this.loadCalendar()
    },
    calLevel(d) {
      if (!d.total) return 'c0'
      if (d.done >= d.total) return 'c2'
      if (d.done > 0) return 'c1'
      return 'c0'
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
    },
    onDayChange(e) {
      this.dayIndex = Number(e.detail.value)
    },
    async createPlan() {
      if (this.creating || !this.goal.trim() || !this.courseId) return
      this.creating = true
      this.error = ''
      try {
        await api('/api/plans', { method: 'POST', body: { goal: this.goal.trim(), days: DAY_OPTIONS[this.dayIndex], courseId: this.courseId } })
        this.goal = ''
        uni.showToast({ title: '计划已生成', icon: 'success' })
        this.openMap = {}
        this.details = {}
        await Promise.all([this.load(), this.loadCalendar()])
      } catch (e) {
        this.error = '计划生成失败：' + e.message
      } finally {
        this.creating = false
      }
    },
    async toggle(id) {
      this.openMap[id] = !this.openMap[id]
      if (this.openMap[id] && !this.details[id]) await this.loadDetail(id)
    },
    async loadDetail(id) {
      try {
        this.details[id] = await api('/api/plans/' + id)
      } catch (e) {
        this.error = e.message
      }
    },
    progress(id) {
      const d = this.details[id]
      if (!d || !d.tasks.length) return 0
      return Math.round((d.doneCount / d.tasks.length) * 100)
    },
    async checkIn(t) {
      // 打卡是 toggle 接口，锁在途请求防连点造成「打卡又取消」
      if (this.checking[t.id]) return
      this.checking[t.id] = true
      try {
        await api('/api/plans/tasks/' + t.id + '/checkin', { method: 'POST' })
        await Promise.all([this.loadDetail(t.planId), this.loadCalendar()])
      } catch (e) {
        this.error = '打卡失败：' + e.message
      } finally {
        this.checking[t.id] = false
      }
    },
    askDelete(p) {
      uni.showModal({
        title: '删除计划',
        content: '确定删除「' + p.goal + '」吗？打卡记录将一并删除。',
        confirmColor: '#e5484d',
        success: (r) => {
          if (r.confirm) this.doDelete(p.id)
        }
      })
    },
    async doDelete(id) {
      try {
        await api('/api/plans/' + id, { method: 'DELETE' })
        this.openMap = {}
        this.details = {}
        await Promise.all([this.load(), this.loadCalendar()])
      } catch (e) {
        this.error = '删除失败：' + e.message
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
.card-title { font-size: 29rpx; font-weight: 700; }
.cal-head { display: flex; align-items: center; justify-content: space-between; }
.cal-ops { display: flex; gap: 22rpx; }
.cal-arrow { color: #4f6ef7; font-size: 40rpx; line-height: 1; padding: 0 8rpx; }
.cal-grid { display: flex; flex-wrap: wrap; gap: 8rpx; margin-top: 20rpx; }
.cal-h { width: calc((100% - 48rpx) / 7); text-align: center; font-size: 21rpx; color: #9aa0ab; }
.cal-cell {
  width: calc((100% - 48rpx) / 7); height: 76rpx;
  border: 1rpx solid #eceef2; border-radius: 10rpx;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.cal-cell.blank { border: none; }
.cal-day { font-size: 23rpx; font-weight: 600; }
.cal-cnt { font-size: 18rpx; color: #6b7280; }
.cal-cell.c0 { background: #f7f8fc; }
.cal-cell.c1 { background: #fdf3e4; border-color: #e8c98f; }
.cal-cell.c2 { background: #eef7ee; border-color: #b5d8b5; }
.cal-legend { color: #9aa0ab; font-size: 21rpx; margin-top: 16rpx; }
.ipt {
  margin-top: 18rpx; height: 84rpx;
  background: #f5f6fa; border-radius: 14rpx;
  padding: 0 26rpx; font-size: 28rpx;
}
.ph { color: #b3b9c4; }
.pick-row { display: flex; gap: 20rpx; margin-top: 18rpx; }
.pick-item { flex: 1; }
.lab { font-size: 22rpx; color: #9aa0ab; }
.pick-val {
  margin-top: 8rpx; height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 12rpx; padding: 0 22rpx;
  font-size: 25rpx;
}
.btn {
  margin-top: 24rpx; height: 84rpx; line-height: 84rpx;
  border-radius: 16rpx; font-size: 30rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.btn[disabled] { background: #c3cdfb; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.tag.ok { background: #e2f4e7; color: #1f7a41; }
.plan-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4rpx 0 16rpx;
}
.plan-head-l { display: flex; align-items: center; gap: 14rpx; flex: 1; min-width: 0; }
.plan-goal { font-size: 29rpx; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plan-days { color: #9aa0ab; font-size: 24rpx; flex-shrink: 0; }
.progress { height: 12rpx; background: #f5f6fa; border-radius: 999rpx; margin-bottom: 20rpx; overflow: hidden; }
.progress-fill { height: 100%; background: #4f6ef7; border-radius: 999rpx; }
.task {
  display: flex; gap: 16rpx; padding: 18rpx 20rpx;
  border: 1rpx solid #eceef2; border-radius: 14rpx; margin-bottom: 14rpx;
  background: #ffffff;
}
.task.done { opacity: 0.7; background: #f7f8fc; }
.cb { font-size: 30rpx; }
.task-body { flex: 1; min-width: 0; }
.task-title { font-size: 27rpx; line-height: 1.6; }
.task-day { font-weight: 700; margin-right: 12rpx; }
.task-desc { color: #6b7280; font-size: 24rpx; line-height: 1.6; margin: 6rpx 0 8rpx; }
.small-tag { font-size: 20rpx; }
.plan-del { text-align: center; color: #e5484d; font-size: 26rpx; padding: 18rpx 0 4rpx; }
.empty-card { text-align: center; padding: 80rpx 30rpx; }
.empty-ico { font-size: 70rpx; }
.empty-t { font-size: 29rpx; font-weight: 700; margin-top: 18rpx; }
.empty-d { color: #9aa0ab; font-size: 24rpx; margin-top: 10rpx; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
