<template>
  <view class="wrap">
    <view class="card toolbar">
      <picker class="toolbar-course" mode="selector" :range="courseOptions" :value="courseIndex" @change="onCourseChange">
        <view class="pick-val">{{ courseOptions[courseIndex] }}</view>
      </picker>
      <view class="stat">
        <text class="stat-num">{{ favList.length }}</text>
        <text class="stat-txt">道收藏</text>
      </view>
      <button class="retrain" :disabled="!favList.length" @click="goRetrain">一键重练</button>
    </view>

    <view v-for="(q, i) in favList" :key="q.questionId" class="card fi-card">
      <view class="fi-head">
        <text class="fi-no">{{ i + 1 }}</text>
        <text class="tag">{{ q.type }}</text>
        <text v-if="q.kpName" class="tag">{{ q.kpName }}</text>
        <text v-if="!courseId && q.courseName" class="tag">{{ q.courseName }}</text>
      </view>

      <view class="fi-stem" user-select>{{ q.stem }}</view>

      <view v-if="q.options" class="fi-opts">
        <view v-for="o in parseOptions(q.options)" :key="o.k" class="opt-row" :class="markOption(q, o.k)">
          <view class="opt-k">{{ o.k }}</view>
          <text class="opt-v">{{ o.v }}</text>
          <text v-if="markOption(q, o.k) === 'correct'" class="opt-flag ok">正确答案</text>
          <text v-else-if="markOption(q, o.k) === 'wrong'" class="opt-flag bad">你的答案</text>
        </view>
      </view>

      <view class="fi-cmp">
        <view v-if="hasRecord(q)" class="cmp-row">
          <text class="cmp-lab">你的答案</text>
          <text class="cmp-val" :class="q.lastCorrect ? 'ok' : 'bad'" user-select>{{ q.lastAnswer || '（未作答）' }}</text>
        </view>
        <view class="cmp-row">
          <text class="cmp-lab">正确答案</text>
          <text class="cmp-val ok" user-select>{{ q.answer || '—' }}</text>
        </view>
      </view>

      <view v-if="q.analysis">
        <view class="analysis-toggle" @click="toggleAnalysis(q.questionId)">
          {{ expandedId === q.questionId ? '收起解析 ▴' : '查看解析 ▾' }}
        </view>
        <view v-if="expandedId === q.questionId" class="fi-analysis" user-select>{{ q.analysis }}</view>
      </view>

      <view class="fi-foot">
        <text class="unfav" @click="remove(q)">{{ removingId === q.questionId ? '移除中…' : '☆ 取消收藏' }}</text>
      </view>
    </view>

    <view v-if="!favList.length && loaded && !error" class="card empty-card">
      <view class="empty-ico">⭐</view>
      <view class="empty-t">{{ courseId ? '该课程暂无收藏' : '暂无收藏' }}</view>
      <view class="empty-d">{{ courseId ? '切换课程或去练习页收藏好题' : '在练习报告页点击收藏，好题会集中到这里' }}</view>
    </view>

    <view v-if="error" class="err">{{ error }}</view>
  </view>
</template>

<script>
import { api, getCourses } from '../../utils/api'
import { parseOptions } from '../../utils/format'

const PENDING_KEY = 'la_practice_pending'

export default {
  data() {
    return {
      courses: [],
      courseId: null,
      courseIndex: 0,
      favList: [],
      expandedId: null,
      removingId: null,
      loaded: false,
      error: ''
    }
  },
  computed: {
    // 第 0 项是「全部课程」
    courseOptions() {
      return ['全部课程', ...this.courses.map((c) => c.name)]
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
    else this.load()
  },
  methods: {
    parseOptions,
    async init() {
      try {
        this.courses = await getCourses()
      } catch (e) { /* 课程为空也可浏览 */ }
      this.load()
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = i === 0 ? null : (this.courses[i - 1] ? this.courses[i - 1].id : null)
      this.load()
    },
    async load() {
      this.error = ''
      try {
        this.favList = await api('/api/favorites' + (this.courseId ? '?courseId=' + this.courseId : ''))
        this.loaded = true
      } catch (e) {
        this.error = e.message
      }
    },
    /** 该题是否有作答记录（收藏的题可能从未做过） */
    hasRecord(q) {
      return q.lastAnswer !== null && q.lastAnswer !== undefined
    },
    /** 选项标记：正确项=correct，作答中选错的项=wrong */
    markOption(q, k) {
      const kk = String(k).toUpperCase()
      if ((q.answer || '').toUpperCase().includes(kk)) return 'correct'
      if (this.hasRecord(q) && (q.lastAnswer || '').toUpperCase().includes(kk)) return 'wrong'
      return ''
    },
    toggleAnalysis(id) {
      this.expandedId = this.expandedId === id ? null : id
    },
    async remove(q) {
      if (this.removingId) return
      this.removingId = q.questionId
      this.error = ''
      try {
        await api('/api/favorites/toggle', { method: 'POST', body: { questionId: q.questionId } })
        await this.load()
      } catch (e) {
        this.error = e.message
      } finally {
        this.removingId = null
      }
    },
    goRetrain() {
      if (!this.favList.length) return
      // switchTab 不能带参：写入本地暂存，练习页 onShow 消费
      uni.setStorageSync(PENDING_KEY, { favorite: true, courseId: this.courseId })
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
.toolbar { display: flex; align-items: center; gap: 18rpx; }
.toolbar-course { flex: 1; }
.pick-val {
  height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.stat { display: flex; align-items: baseline; gap: 6rpx; }
.stat-num { font-size: 40rpx; font-weight: 800; color: #4f6ef7; }
.stat-txt { font-size: 21rpx; color: #9aa0ab; }
.retrain {
  margin: 0; height: 72rpx; line-height: 72rpx; padding: 0 26rpx;
  border-radius: 14rpx; font-size: 26rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.retrain[disabled] { background: #c3cdfb; }
.fi-head { display: flex; align-items: center; flex-wrap: wrap; gap: 10rpx; margin-bottom: 14rpx; }
.fi-no {
  min-width: 40rpx; height: 40rpx; border-radius: 10rpx;
  display: flex; align-items: center; justify-content: center;
  background: #eef1fe; color: #4f6ef7;
  font-size: 22rpx; font-weight: 700;
}
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.fi-stem { font-size: 29rpx; font-weight: 600; line-height: 1.7; margin-bottom: 18rpx; }
.fi-opts { display: flex; flex-direction: column; gap: 12rpx; margin-bottom: 18rpx; }
.opt-row {
  display: flex; align-items: center; gap: 16rpx;
  padding: 16rpx 22rpx; border-radius: 14rpx;
  border: 1rpx solid #eceef2; background: #ffffff;
  font-size: 26rpx; line-height: 1.6;
}
.opt-row.correct { background: #f2faf4; border-color: #c3e6cd; }
.opt-row.wrong { background: #fdf3f2; border-color: #f2d2ce; }
.opt-k {
  flex-shrink: 0; width: 40rpx; height: 40rpx; border-radius: 10rpx;
  display: flex; align-items: center; justify-content: center;
  background: #ffffff; border: 1rpx solid #d1d9e0;
  font-size: 22rpx; font-weight: 700; color: #4f6ef7;
}
.opt-v { flex: 1; }
.opt-flag { flex-shrink: 0; font-size: 21rpx; font-weight: 700; padding: 4rpx 14rpx; border-radius: 999rpx; }
.opt-flag.ok { background: #dff3e5; color: #1f7a41; }
.opt-flag.bad { background: #f9e2e0; color: #b3423a; }
.fi-cmp { border: 1rpx solid #eceef2; border-radius: 14rpx; overflow: hidden; margin-bottom: 16rpx; }
.cmp-row { display: flex; gap: 20rpx; padding: 16rpx 22rpx; font-size: 25rpx; line-height: 1.7; }
.cmp-row + .cmp-row { border-top: 1rpx solid #eaeef2; }
.cmp-lab { flex-shrink: 0; width: 110rpx; font-size: 22rpx; font-weight: 700; color: #9aa0ab; padding-top: 4rpx; }
.cmp-val { flex: 1; word-break: break-word; }
.cmp-val.bad { color: #b3423a; font-weight: 600; }
.cmp-val.ok { color: #1f7a41; font-weight: 700; }
.analysis-toggle {
  display: inline-block; color: #4f6ef7; font-size: 24rpx;
  padding: 8rpx 20rpx; border-radius: 12rpx; background: #eef1fe;
}
.fi-analysis {
  margin-top: 14rpx; padding: 16rpx 22rpx;
  background: #f7f8fc; border-radius: 12rpx;
  font-size: 25rpx; line-height: 1.75; color: #565b6e;
}
.fi-foot { margin-top: 18rpx; padding-top: 16rpx; border-top: 1rpx dashed #eceef2; text-align: right; }
.unfav { color: #6b7280; font-size: 24rpx; }
.empty-card { text-align: center; padding: 80rpx 30rpx; }
.empty-ico { font-size: 70rpx; }
.empty-t { font-size: 29rpx; font-weight: 700; margin-top: 18rpx; }
.empty-d { color: #9aa0ab; font-size: 24rpx; margin-top: 10rpx; line-height: 1.7; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
