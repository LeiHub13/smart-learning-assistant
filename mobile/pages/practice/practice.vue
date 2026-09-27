<template>
  <view class="wrap">
    <!-- ========== 首页：设置 + 历史 + 曲线 ========== -->
    <template v-if="stage === 'home'">
      <view class="card">
        <view class="pick-row">
          <view class="pick-item">
            <text class="lab">课程</text>
            <picker mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
              <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
            </picker>
          </view>
          <view class="pick-item">
            <text class="lab">题目数量</text>
            <picker mode="selector" :range="counts" :value="countIndex" @change="onCountChange">
              <view class="pick-val">{{ counts[countIndex] }} 题</view>
            </picker>
          </view>
        </view>
        <button class="btn" :disabled="loading || !courseId" @click="start(false)">
          {{ loading ? '出题中…' : '开始练习' }}
        </button>
        <view class="bank-entry" @click="openBank">浏览题库 ›</view>
      </view>

      <view class="card">
        <view class="card-title">最近练习</view>
        <view v-if="history.length">
          <view v-for="p in history" :key="p.id" class="his-row" @click="viewReport(p.id)">
            <text class="his-time">{{ fmtTime(p.createdAt) }}</text>
            <text class="his-score">{{ p.score }} / {{ p.totalScore }}</text>
            <text class="his-link">报告 ›</text>
          </view>
          <view class="pager">
            <text class="pg-btn" :class="{ off: historyPage <= 1 }" @click="loadHistory(historyPage - 1)">上一页</text>
            <text class="pg-info">{{ historyPage }} / {{ historyPages }} 页</text>
            <text class="pg-btn" :class="{ off: historyPage >= historyPages }" @click="loadHistory(historyPage + 1)">下一页</text>
          </view>
        </view>
        <view v-else class="empty-sm">暂无练习记录</view>
      </view>

      <view class="card" v-if="trend.length">
        <view class="card-title">正确率曲线（最近 {{ trend.length }} 次）</view>
        <view class="trend">
          <view v-for="(t, i) in trendBars" :key="i" class="trend-col">
            <view class="trend-bar-box">
              <view class="trend-bar" :style="{ height: t.rate + '%' }" />
            </view>
            <text class="trend-lab">{{ t.rate }}%</text>
          </view>
        </view>
        <view class="trend-meta">最近一次：{{ trend[0].date }} · {{ trend[0].title }} · {{ trend[0].score }}/{{ trend[0].totalScore }} 分</view>
      </view>
    </template>

    <!-- ========== 作答中 ========== -->
    <template v-else-if="paper.length">
      <view class="paper-head">
        <view class="paper-title">
          {{ modeName }} · {{ paper.length }} 题
          <text v-if="kp" class="tag">专项：{{ kp }}</text>
        </view>
        <view class="paper-exit" @click="askExit">退出</view>
      </view>

      <view v-for="(q, i) in paper" :key="q.id" class="card q-card">
        <view class="q-head">
          <text class="q-type">{{ i + 1 }}. 【{{ q.type }}】</text>
          <text v-if="q.kpName" class="tag">{{ q.kpName }}</text>
        </view>
        <view class="q-stem" user-select>{{ q.stem }}</view>
        <view
          v-for="o in parseOptions(q.options)"
          :key="o.k"
          class="opt"
          :class="{ on: isSel(q, o.k) }"
          @click="toggle(q, o.k, q.type)"
        >
          <view class="opt-k" :class="{ on: isSel(q, o.k) }">{{ o.k }}</view>
          <text class="opt-v">{{ o.v }}</text>
        </view>
        <textarea
          v-if="q.type === '问答'"
          v-model="answers[q.id]"
          class="q-essay"
          auto-height
          placeholder="请输入你的答案…"
          placeholder-class="ph"
        />
      </view>

      <button class="btn submit" :disabled="submitting" @click="submit">
        {{ submitting ? '批改中…' : '提交并 AI 批改' }}
      </button>
    </template>

    <!-- ========== 报告 ========== -->
    <template v-else-if="report">
      <view class="card stat-row">
        <view class="stat">
          <view class="num">{{ report.score }} / {{ report.totalScore }}</view>
          <view class="stat-lab">总分</view>
        </view>
        <view class="stat">
          <view class="num">{{ okCount }} / {{ report.items.length }}</view>
          <view class="stat-lab">答对</view>
        </view>
        <view class="stat">
          <view class="num">{{ rate }}%</view>
          <view class="stat-lab">正确率</view>
        </view>
      </view>
      <button class="btn again" @click="again">再做一组</button>

      <view v-for="(it, i) in report.items" :key="it.pq.id" class="card q-card" :class="it.pq.correct ? 'pass' : 'fail'">
        <view class="q-head">
          <text class="q-type">{{ i + 1 }}. 【{{ it.q.type }}】</text>
          <text class="tag" :class="it.pq.correct ? 'ok' : 'bad'">{{ it.pq.correct ? '正确 +' + it.pq.score : '错误' }}</text>
          <text class="fav" @click="toggleFav(it.q)">{{ it.q.favorited ? '★ 已收藏' : '☆ 收藏' }}</text>
        </view>
        <view class="q-stem" user-select>{{ it.q.stem }}</view>
        <view class="ans" user-select>
          <view><text class="ans-lab">你的答案：</text>{{ it.pq.userAnswer || '（未作答）' }}</view>
          <view><text class="ans-lab">参考答案：</text>{{ it.q.answer }}</view>
          <view v-if="it.q.analysis"><text class="ans-lab">解析：</text>{{ it.q.analysis }}</view>
        </view>
        <view v-if="it.pq.review" class="review" user-select>
          <template v-if="parseReview(it.pq.review).points.length">
            <view class="rv-summary">AI 点评：{{ parseReview(it.pq.review).summary }}</view>
            <view v-for="(p, pi) in parseReview(it.pq.review).points" :key="pi" class="rv-line">
              <text :class="p.hit ? 'hit' : 'miss'">{{ p.hit ? '✓' : '✗' }}</text>
              <text class="rv-p">{{ p.point }}</text>
              <text v-if="p.note" class="rv-n">{{ p.note }}</text>
            </view>
          </template>
          <view v-else>AI 点评：{{ it.pq.review }}</view>
        </view>
      </view>
    </template>

    <view v-if="error" class="err">{{ error }}</view>
  </view>
</template>

<script>
import { api, getCourses } from '../../utils/api'
import { fmtTime, parseOptions, parseReview } from '../../utils/format'

const PAGE_SIZE = 5
const PENDING_KEY = 'la_practice_pending'

export default {
  data() {
    return {
      stage: 'home', // home | paper | report
      courses: [],
      courseId: null,
      courseIndex: 0,
      counts: [3, 5, 8, 10],
      countIndex: 1,
      paper: [],
      report: null,
      history: [],
      historyPage: 1,
      historyTotal: 0,
      mistake: false,
      kp: '',
      modeName: '题库练习',
      trend: [],
      answers: {},
      loading: false,
      submitting: false,
      error: ''
    }
  },
  computed: {
    courseNames() {
      return this.courses.map((c) => c.name)
    },
    okCount() {
      return this.report ? this.report.items.filter((i) => i.pq.correct).length : 0
    },
    historyPages() {
      return Math.max(1, Math.ceil(this.historyTotal / PAGE_SIZE))
    },
    rate() {
      if (!this.report || !this.report.items.length) return 0
      return Math.round((this.okCount / this.report.items.length) * 100)
    },
    // 最近 12 次的条形数据（趋势接口按时间倒序返回）
    trendBars() {
      return this.trend.slice(0, 12).reverse()
    }
  },
  watch: {
    courseId() {
      this.refreshTrend()
    }
  },
  onLoad() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
    }
  },
  async onShow() {
    if (!uni.getStorageSync('la_token')) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    // 先保证课程就绪（首次进入课程为空时 start 会因无 courseId 静默返回），再消费暂存
    if (!this.courses.length) await this.init()
    else if (!this.history.length) await this.loadHistory(1)
    const pending = uni.getStorageSync(PENDING_KEY)
    if (pending) {
      uni.removeStorageSync(PENDING_KEY)
      await this.consumePending(pending)
    }
  },
  methods: {
    fmtTime,
    parseOptions,
    parseReview,
    async init() {
      try {
        this.courses = await getCourses()
        if (this.courses.length && !this.courseId) this.courseId = this.courses[0].id
      } catch (e) { /* 未建课程时允许浏览空页 */ }
      this.loadHistory(1)
      this.refreshTrend()
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
    },
    onCountChange(e) {
      this.countIndex = Number(e.detail.value)
    },
    async consumePending(p) {
      this.stage = 'home'
      this.report = null
      this.paper = []
      this.answers = {}
      this.error = ''
      if (p.courseId && this.courses.some((c) => c.id === p.courseId)) {
        this.courseId = p.courseId
        this.courseIndex = this.courses.findIndex((c) => c.id === p.courseId)
      }
      this.kp = p.kp ? String(p.kp) : ''
      if (p.variants) {
        await this.startVariants(p.variants)
        return
      }
      this.mistake = !!p.mistake
      // 错题/收藏/专项/Agent 指定才自动开卷；课程中心「去练习」只预选课程
      if (this.mistake || p.favorite || this.kp || p.auto) this.start(!!p.favorite)
    },
    async loadHistory(page = 1) {
      try {
        const d = await api('/api/practice/history?page=' + page + '&size=' + PAGE_SIZE)
        this.history = d.records || []
        this.historyTotal = d.total || 0
        this.historyPage = page
      } catch (e) { /* 列表失败不阻塞 */ }
    },
    async refreshTrend() {
      if (!this.courseId) return
      try {
        this.trend = await api('/api/practice/trend?courseId=' + this.courseId)
      } catch (e) {
        this.trend = []
      }
    },
    async start(favMode = false) {
      if (!this.courseId) return
      this.loading = true
      this.error = ''
      this.answers = {}
      try {
        const url = '/api/practice/paper?courseId=' + this.courseId + '&count=' + this.counts[this.countIndex]
          + '&favorite=' + favMode + '&mistake=' + this.mistake
          + (this.kp ? '&kp=' + encodeURIComponent(this.kp) : '')
        this.paper = await api(url)
        this.modeName = this.mistake ? '错题重练' : this.kp ? '专项练习' : favMode ? '收藏重练' : '题库练习'
        this.stage = 'paper'
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    async startVariants(questionId) {
      this.loading = true
      this.error = ''
      this.answers = {}
      try {
        this.paper = await api('/api/mistakes/' + questionId + '/variants', { method: 'POST', body: { count: 2 } })
        this.modeName = '举一反三'
        this.stage = 'paper'
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    isSel(q, k) {
      const v = this.answers[q.id] || ''
      if (q.type === '多选') return v.split('').includes(k)
      return v === k
    },
    toggle(q, k, type) {
      if (type === '多选') {
        let v = (this.answers[q.id] || '').split('').filter((c) => c !== k).join('')
        if (!v.includes(k)) v = (v + k).split('').sort().join('')
        this.answers[q.id] = v
      } else {
        this.answers[q.id] = k
      }
    },
    askExit() {
      uni.showModal({
        title: '退出练习',
        content: '退出后本次作答不会保存，确定退出吗？',
        success: (r) => {
          if (r.confirm) this.doExit()
        }
      })
    },
    doExit() {
      this.paper = []
      this.answers = {}
      this.mistake = false
      this.error = ''
      this.stage = 'home'
    },
    async submit() {
      this.submitting = true
      this.error = ''
      try {
        const items = this.paper.map((q) => ({ questionId: q.id, answer: this.answers[q.id] || '' }))
        const p = await api('/api/practice/submit', { method: 'POST', body: { courseId: this.courseId, items } })
        this.paper = []
        this.loadHistory(1)
        this.refreshTrend()
        this.report = await api('/api/practice/' + p.id)
        this.stage = 'report'
        uni.pageScrollTo({ scrollTop: 0, duration: 100 })
      } catch (e) {
        this.error = e.message
      } finally {
        this.submitting = false
      }
    },
    async viewReport(id) {
      this.error = ''
      try {
        this.report = await api('/api/practice/' + id)
        this.stage = 'report'
      } catch (e) {
        this.error = e.message
      }
    },
    async toggleFav(q) {
      try {
        const r = await api('/api/favorites/toggle', { method: 'POST', body: { questionId: q.id } })
        q.favorited = r.favorited
      } catch (e) { /* 静默失败 */ }
    },
    again() {
      this.report = null
      this.paper = []
      this.answers = {}
      this.mistake = false
      this.kp = ''
      this.refreshTrend()
      this.stage = 'home'
    },
    openBank() {
      uni.navigateTo({ url: '/pages/bank/bank' })
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
.card-title { font-size: 29rpx; font-weight: 700; margin-bottom: 18rpx; }
.pick-row { display: flex; gap: 20rpx; }
.pick-item { flex: 1; }
.lab { font-size: 24rpx; color: #9aa0ab; }
.pick-val {
  margin-top: 10rpx; height: 76rpx; line-height: 76rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 27rpx;
}
.btn {
  margin-top: 26rpx; height: 84rpx; line-height: 84rpx;
  border-radius: 16rpx; font-size: 30rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.btn[disabled] { background: #c3cdfb; }
.bank-entry { text-align: center; color: #4f6ef7; font-size: 26rpx; padding: 22rpx 0 2rpx; }
.his-row {
  display: flex; align-items: center; gap: 16rpx;
  padding: 18rpx 4rpx; border-bottom: 1rpx solid #eceef2;
}
.his-time { flex: 1; color: #6b7280; font-size: 26rpx; }
.his-score { font-weight: 700; font-size: 27rpx; }
.his-link { color: #4f6ef7; font-size: 25rpx; }
.pager { display: flex; align-items: center; justify-content: center; gap: 30rpx; padding: 20rpx 0 2rpx; }
.pg-btn { color: #4f6ef7; font-size: 25rpx; }
.pg-btn.off { color: #c3c9d4; }
.pg-info { color: #9aa0ab; font-size: 24rpx; }
.empty-sm { color: #b3b9c4; font-size: 26rpx; text-align: center; padding: 20rpx 0; }
.trend { display: flex; align-items: flex-end; gap: 12rpx; height: 160rpx; }
.trend-col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend-bar-box {
  flex: 1; width: 100%; display: flex; align-items: flex-end;
  background: #f5f6fa; border-radius: 8rpx; overflow: hidden;
}
.trend-bar { width: 100%; background: #4f6ef7; border-radius: 8rpx 8rpx 0 0; }
.trend-lab { font-size: 19rpx; color: #9aa0ab; margin-top: 6rpx; }
.trend-meta { color: #9aa0ab; font-size: 23rpx; margin-top: 16rpx; }
.paper-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6rpx 6rpx 20rpx;
}
.paper-title { font-size: 29rpx; font-weight: 700; }
.paper-exit { color: #e5484d; font-size: 26rpx; }
.q-card.pass { border-left: 6rpx solid #34a853; }
.q-card.fail { border-left: 6rpx solid #e5484d; }
.q-head { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; margin-bottom: 12rpx; }
.q-type { font-weight: 700; font-size: 27rpx; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.tag.ok { background: #e2f4e7; color: #1f7a41; }
.tag.bad { background: #f9e2e0; color: #b3423a; }
.fav { margin-left: auto; color: #6b7280; font-size: 24rpx; }
.q-stem { font-size: 28rpx; line-height: 1.7; margin-bottom: 18rpx; }
.opt {
  display: flex; align-items: center; gap: 18rpx;
  padding: 18rpx 22rpx; border-radius: 14rpx;
  border: 1rpx solid #eceef2; margin-bottom: 14rpx;
  background: #fafbfc;
}
.opt.on { background: #eef1fe; border-color: #4f6ef7; }
.opt-k {
  width: 44rpx; height: 44rpx; border-radius: 10rpx;
  display: flex; align-items: center; justify-content: center;
  background: #ffffff; border: 1rpx solid #dcdfe6;
  font-size: 23rpx; font-weight: 700; color: #4f6ef7;
}
.opt-k.on { background: #4f6ef7; border-color: #4f6ef7; color: #ffffff; }
.opt-v { flex: 1; font-size: 26rpx; line-height: 1.6; }
.q-essay {
  width: 100%; min-height: 140rpx; box-sizing: border-box;
  background: #f5f6fa; border-radius: 14rpx;
  padding: 18rpx 22rpx; font-size: 27rpx; margin-top: 6rpx;
}
.ph { color: #b3b9c4; }
.submit { width: 100%; }
.stat-row { display: flex; }
.stat { flex: 1; text-align: center; }
.num { font-size: 38rpx; font-weight: 800; color: #2b2f36; }
.stat-lab { color: #9aa0ab; font-size: 23rpx; margin-top: 6rpx; }
.again { margin-bottom: 22rpx; }
.ans { background: #f7f8fc; border-radius: 12rpx; padding: 16rpx 20rpx; font-size: 25rpx; line-height: 1.8; }
.ans-lab { font-weight: 700; }
.review {
  margin-top: 14rpx; padding: 16rpx 20rpx;
  background: #f4f7ff; border-radius: 12rpx;
  font-size: 25rpx; line-height: 1.8;
}
.rv-summary { margin-bottom: 8rpx; }
.rv-line { display: flex; gap: 12rpx; align-items: baseline; }
.rv-line .hit { color: #1f7a41; font-weight: 800; }
.rv-line .miss { color: #e5484d; font-weight: 800; }
.rv-p { font-weight: 600; }
.rv-n { color: #9aa0ab; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
