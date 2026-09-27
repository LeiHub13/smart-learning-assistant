<template>
  <view class="wrap">
    <view class="card toolbar">
      <view class="filter">
        <text class="lab">课程</text>
        <picker mode="selector" :range="courseOptions" :value="courseIndex" @change="onCourseChange">
          <view class="pick-val">{{ courseOptions[courseIndex] }}</view>
        </picker>
      </view>
      <view class="toolbar-right">
        <view v-if="total" class="stat">
          <text class="stat-num">{{ total }}</text>
          <text class="stat-txt">道待攻克</text>
        </view>
        <button class="retrain" :disabled="!total" @click="goRetrain">开始重练</button>
      </view>
    </view>

    <view v-for="(m, i) in records" :key="m.question.id" class="card mi-card">
      <view class="mi-head">
        <text class="tag">{{ m.question.type }}</text>
        <text v-if="m.question.kpName" class="tag">{{ m.question.kpName }}</text>
        <text v-if="m.question.difficulty" class="tag">{{ m.question.difficulty }}</text>
        <text class="tag bad">错 {{ m.wrongCount }} 次</text>
        <text class="mi-time">{{ fmtTime(m.lastWrongAt) }}</text>
      </view>

      <view class="mi-stem" user-select>{{ m.question.stem }}</view>

      <view v-if="m.question.options" class="mi-opts">
        <view v-for="o in parseOptions(m.question.options)" :key="o.k" class="opt-row" :class="markOption(m, o.k)">
          <view class="opt-k">{{ o.k }}</view>
          <text class="opt-v">{{ o.v }}</text>
          <text v-if="markOption(m, o.k) === 'correct'" class="opt-flag ok">正确答案</text>
          <text v-else-if="markOption(m, o.k) === 'wrong'" class="opt-flag bad">你的答案</text>
        </view>
      </view>

      <view class="mi-cmp">
        <view v-if="m.lastWrongAnswer" class="cmp-row">
          <text class="cmp-lab">你的答案</text>
          <text class="cmp-val bad" user-select>{{ m.lastWrongAnswer }}</text>
        </view>
        <view class="cmp-row">
          <text class="cmp-lab">正确答案</text>
          <text class="cmp-val ok" user-select>{{ m.question.answer || '—' }}</text>
        </view>
      </view>

      <view v-if="m.question.analysis" class="analysis-toggle" @click="toggleAnalysis(m.question.id)">
        {{ expandedId === m.question.id ? '收起解析 ▴' : '查看解析 ▾' }}
      </view>
      <view v-if="expandedId === m.question.id && m.question.analysis" class="mi-analysis" user-select>
        {{ m.question.analysis }}
      </view>

      <view class="mi-foot">
        <button class="variants-btn" :disabled="generatingId === m.question.id" @click="openVariants(m)">
          {{ generatingId === m.question.id ? 'AI 出题中…' : '✦ 举一反三' }}
        </button>
        <text class="mi-foot-tip">AI 围绕该题考点出变式题</text>
      </view>
    </view>

    <view v-if="!records.length && !error" class="card empty-card">
      <view class="empty-ico">🎯</view>
      <view class="empty-t">{{ courseId ? '该课程暂无错题' : '暂无错题' }}</view>
      <view class="empty-d">{{ courseId ? '这门课掌握得不错，保持下去！' : '完成一次练习后，答错的题会自动归集到这里' }}</view>
    </view>

    <view v-if="error" class="err">{{ error }}</view>

    <view v-if="total" class="pager">
      <text class="pg-btn" :class="{ off: page <= 1 }" @click="load(page - 1)">上一页</text>
      <text class="pg-info">{{ page }} / {{ pages }} 页 · 共 {{ total }} 题</text>
      <text class="pg-btn" :class="{ off: page >= pages }" @click="load(page + 1)">下一页</text>
    </view>
  </view>
</template>

<script>
import { api, getCourses } from '../../utils/api'
import { fmtTime, parseOptions } from '../../utils/format'

const PAGE_SIZE = 10
const PENDING_KEY = 'la_practice_pending'

export default {
  data() {
    return {
      courses: [],
      courseId: null,
      courseIndex: 0,
      records: [],
      page: 1,
      total: 0,
      expandedId: null,
      generatingId: null,
      error: ''
    }
  },
  computed: {
    pages() {
      return Math.max(1, Math.ceil(this.total / PAGE_SIZE))
    },
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
    // 首次进入走 init（拉课程+列表），之后每次显示刷新列表；瞬时失败靠下次进入补拉
    if (!this.courses.length) this.init()
    else this.load(1)
  },
  methods: {
    fmtTime,
    parseOptions,
    async init() {
      try {
        this.courses = await getCourses()
      } catch (e) { /* 未建课程时为空列表 */ }
      this.load(1)
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = i === 0 ? null : (this.courses[i - 1] ? this.courses[i - 1].id : null)
      this.load(1)
    },
    async load(p = 1) {
      this.error = ''
      try {
        let url = '/api/mistakes?page=' + p + '&size=' + PAGE_SIZE
        if (this.courseId) url += '&courseId=' + this.courseId
        const d = await api(url)
        this.records = d.records || []
        this.total = d.total || 0
        this.page = p
      } catch (e) {
        this.error = e.message
      }
    },
    /** 选项标记：正确项=correct，用户选错的项=wrong，其余中性 */
    markOption(m, k) {
      const c = (m.question.answer || '').toUpperCase()
      const u = (m.lastWrongAnswer || '').toUpperCase()
      if (c.includes(k.toUpperCase()) && u.includes(k.toUpperCase())) return 'correct'
      if (c.includes(k.toUpperCase())) return 'correct'
      if (u.includes(k.toUpperCase())) return 'wrong'
      return ''
    },
    toggleAnalysis(id) {
      this.expandedId = this.expandedId === id ? null : id
    },
    goRetrain() {
      if (!this.total) return
      // switchTab 不能带参：把意图写入本地暂存，练习页 onShow 消费
      uni.setStorageSync(PENDING_KEY, { mistake: true, courseId: this.courseId })
      uni.switchTab({ url: '/pages/practice/practice' })
    },
    async openVariants(m) {
      if (!m.question.courseId) {
        uni.showToast({ title: '该题未关联课程，无法生成变式', icon: 'none' })
        return
      }
      this.generatingId = m.question.id
      uni.setStorageSync(PENDING_KEY, { variants: m.question.id, courseId: m.question.courseId, kp: m.question.kpName })
      this.generatingId = null
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
.toolbar { display: flex; align-items: center; justify-content: space-between; }
.filter { flex: 1; margin-right: 20rpx; }
.lab { font-size: 24rpx; color: #9aa0ab; }
.pick-val {
  margin-top: 10rpx; height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.toolbar-right { display: flex; align-items: center; gap: 18rpx; }
.stat { display: flex; align-items: baseline; gap: 6rpx; }
.stat-num { font-size: 40rpx; font-weight: 800; color: #4f6ef7; }
.stat-txt { font-size: 21rpx; color: #9aa0ab; }
.retrain {
  margin: 0; height: 72rpx; line-height: 72rpx; padding: 0 28rpx;
  border-radius: 14rpx; font-size: 26rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.retrain[disabled] { background: #c3cdfb; }
.mi-head { display: flex; align-items: center; flex-wrap: wrap; gap: 10rpx; margin-bottom: 14rpx; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.tag.bad { background: #f9e2e0; color: #b3423a; }
.mi-time { margin-left: auto; color: #9aa0ab; font-size: 22rpx; }
.mi-stem { font-size: 29rpx; font-weight: 600; line-height: 1.7; margin-bottom: 18rpx; }
.mi-opts { display: flex; flex-direction: column; gap: 12rpx; margin-bottom: 18rpx; }
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
.mi-cmp { border: 1rpx solid #eceef2; border-radius: 14rpx; overflow: hidden; margin-bottom: 16rpx; }
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
.mi-analysis {
  margin-top: 14rpx; padding: 16rpx 22rpx;
  background: #f7f8fc; border-radius: 12rpx;
  font-size: 25rpx; line-height: 1.75; color: #565b6e;
}
.mi-foot {
  display: flex; align-items: center; gap: 16rpx;
  margin-top: 18rpx; padding-top: 18rpx; border-top: 1rpx dashed #eceef2;
}
.variants-btn {
  margin: 0; height: 60rpx; line-height: 60rpx; padding: 0 26rpx;
  border-radius: 12rpx; font-size: 24rpx;
  background: #ffffff; color: #4f6ef7; border: 1rpx solid #c3cdfb;
}
.variants-btn[disabled] { color: #9aa0ab; }
.mi-foot-tip { color: #9aa0ab; font-size: 21rpx; }
.empty-card { text-align: center; padding: 80rpx 30rpx; }
.empty-ico { font-size: 70rpx; }
.empty-t { font-size: 29rpx; font-weight: 700; margin-top: 18rpx; }
.empty-d { color: #9aa0ab; font-size: 24rpx; margin-top: 10rpx; line-height: 1.7; }
.pager { display: flex; align-items: center; justify-content: center; gap: 30rpx; padding: 10rpx 0; }
.pg-btn { color: #4f6ef7; font-size: 25rpx; }
.pg-btn.off { color: #c3c9d4; }
.pg-info { color: #9aa0ab; font-size: 24rpx; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
