<template>
  <view class="wrap">
    <!-- ===== 列表/创建视图 ===== -->
    <template v-if="stage === 'home'">
      <view class="card">
        <view class="toolbar">
          <picker class="toolbar-course" mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
            <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
          </picker>
          <button class="ghost-btn" @click="toggleCreate">{{ createOpen ? '收起' : '创建考试' }}</button>
        </view>

        <template v-if="createOpen">
          <input v-model="form.title" class="ipt" placeholder="考试标题，如：期中测验" placeholder-class="ph" />
          <view class="pick-row">
            <view class="pick-item">
              <text class="lab">时长</text>
              <picker mode="selector" :range="durationOptions" :value="durationIndex" @change="onDurationChange">
                <view class="pick-val">{{ durationOptions[durationIndex] }}</view>
              </picker>
            </view>
            <view class="pick-item">
              <text class="lab">组卷方式</text>
              <picker mode="selector" :range="['随机抽题', '手动选题']" :value="modeIndex" @change="onModeChange">
                <view class="pick-val">{{ modeIndex === 0 ? '随机抽题' : '手动选题' }}</view>
              </picker>
            </view>
          </view>
          <view v-if="modeIndex === 0" class="pick-row">
            <view class="pick-item">
              <text class="lab">题目数量</text>
              <picker mode="selector" :range="countOptions" :value="countIndex" @change="onCountChange">
                <view class="pick-val">{{ countOptions[countIndex] }} 题</view>
              </picker>
            </view>
            <view class="pick-item" />
          </view>
          <button class="btn" :disabled="creating || !form.title.trim()" @click="create">
            {{ creating ? '创建中…' : '确认创建' }}
          </button>
          <view v-if="modeIndex === 1 && bank.length" class="bank">
            <view
              v-for="q in bank"
              :key="q.id"
              class="bank-item"
              :class="{ picked: pickedIds.includes(q.id) }"
              @click="togglePick(q)"
            >
              <view class="bank-stem">【{{ q.type }}】{{ q.stem }}</view>
              <text class="bank-meta">{{ q.kpName || '未标注知识点' }} · {{ q.difficulty || '未标注难度' }}</text>
            </view>
          </view>
          <view v-else-if="modeIndex === 1" class="empty-sm">题库为空，无法手动选题</view>
        </template>
      </view>

      <view class="card">
        <view class="card-title">考试列表</view>
        <view v-if="!exams.length && loaded" class="empty-sm">暂无考试，点「创建考试」开一场</view>
        <view v-for="e in exams" :key="e.id" class="exam-row">
          <view class="exam-info">
            <text class="exam-title" user-select>{{ e.title }}</text>
            <text class="exam-meta">{{ e.courseName }} · {{ e.durationMin ? e.durationMin + ' 分钟' : '不限时' }} · 共 {{ e.totalScore }} 分</text>
            <text class="exam-meta">{{ e.attemptCount ? '最好 ' + e.myBestScore + ' / ' + e.totalScore + '（' + e.attemptCount + ' 次）' : '未参加' }}</text>
          </view>
          <button class="enter-btn" @click="enter(e)">进入</button>
        </view>
      </view>
    </template>

    <!-- ===== 作答视图 ===== -->
    <template v-else-if="stage === 'paper'">
      <view class="paper-head">
        <text class="paper-title">{{ examMeta.title }} · {{ paper.length }} 题</text>
        <text class="timer" :class="{ danger: remainSec < 120 }">{{ remainText }}</text>
      </view>

      <view v-for="(q, i) in paper" :key="q.id" class="card q-card">
        <view class="q-head">
          <text class="q-type">{{ i + 1 }}. 【{{ q.type }}】</text>
          <text v-if="q.kpName" class="tag">{{ q.kpName }}（{{ q.score }} 分）</text>
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
        {{ submitting ? '批改中…' : '交卷' }}
      </button>
    </template>

    <!-- ===== 成绩单视图 ===== -->
    <template v-else>
      <view class="card">
        <view class="stat-row">
          <view class="stat"><text class="num">{{ report.record.score }} / {{ report.record.totalScore }}</text><text class="stat-lab">总分</text></view>
          <view class="stat"><text class="num">{{ okCount }} / {{ report.items.length }}</text><text class="stat-lab">答对</text></view>
          <view class="stat"><text class="num">{{ rate }}%</text><text class="stat-lab">正确率</text></view>
          <view class="stat"><text class="num">{{ useTime }}</text><text class="stat-lab">用时</text></view>
        </view>
        <button class="back-btn" @click="back">返回考试列表</button>
      </view>

      <view v-for="(it, i) in report.items" :key="i" class="card q-card" :class="it.answer.correct ? 'pass' : 'fail'">
        <view class="q-head">
          <text class="q-type">{{ i + 1 }}. 【{{ it.question.type }}】</text>
          <text class="tag" :class="it.answer.correct ? 'ok' : 'bad'">{{ it.answer.correct ? '正确 +' + it.answer.score : '错误' }}</text>
          <text class="fav" @click="toggleFav(it.question)">{{ it.question.favorited ? '★ 已收藏' : '☆ 收藏' }}</text>
        </view>
        <view class="q-stem" user-select>{{ it.question.stem }}</view>
        <view class="ans" user-select>
          <view><text class="ans-lab">你的答案：</text>{{ it.answer.userAnswer || '（未作答）' }}</view>
          <view><text class="ans-lab">参考答案：</text>{{ it.question.answer }}</view>
          <view v-if="it.question.analysis"><text class="ans-lab">解析：</text>{{ it.question.analysis }}</view>
        </view>
        <view v-if="it.answer.review" class="review" user-select>
          <template v-if="parseReview(it.answer.review).points.length">
            <view class="rv-summary">AI 点评：{{ parseReview(it.answer.review).summary }}</view>
            <view v-for="(p, pi) in parseReview(it.answer.review).points" :key="pi" class="rv-line">
              <text :class="p.hit ? 'hit' : 'miss'">{{ p.hit ? '✓' : '✗' }}</text>
              <text class="rv-p">{{ p.point }}</text>
              <text v-if="p.note" class="rv-n">{{ p.note }}</text>
            </view>
          </template>
          <view v-else>AI 点评：{{ it.answer.review }}</view>
        </view>
      </view>
    </template>

    <view v-if="error" class="err">{{ error }}</view>
  </view>
</template>

<script>
import { api, getCourses } from '../../utils/api'
import { parseOptions, parseReview } from '../../utils/format'

const DURATIONS = [
  { label: '不限时', value: null },
  { label: '15 分钟', value: 15 },
  { label: '30 分钟', value: 30 },
  { label: '45 分钟', value: 45 },
  { label: '60 分钟', value: 60 },
  { label: '90 分钟', value: 90 }
]
const COUNTS = [5, 10, 15, 20]

export default {
  data() {
    return {
      stage: 'home', // home | paper | report
      courses: [],
      courseId: null,
      courseIndex: 0,
      exams: [],
      loaded: false,
      createOpen: false,
      creating: false,
      form: { title: '' },
      durationOptions: DURATIONS.map((d) => d.label),
      durationIndex: 0,
      modeIndex: 0,
      countOptions: COUNTS,
      countIndex: 0,
      bank: [],
      pickedIds: [],
      paper: [],
      examMeta: {},
      record: null,
      answers: {},
      submitting: false,
      remainSec: null,
      report: null,
      error: ''
    }
  },
  computed: {
    courseNames() {
      return this.courses.map((c) => c.name)
    },
    okCount() {
      return this.report ? this.report.items.filter((i) => i.answer.correct).length : 0
    },
    rate() {
      return this.report && this.report.items.length
        ? Math.round((this.okCount / this.report.items.length) * 100)
        : 0
    },
    useTime() {
      if (!this.report) return ''
      const sec = Math.round((new Date(this.report.record.submittedAt) - new Date(this.report.record.startedAt)) / 1000)
      return Math.floor(sec / 60) + '分' + (sec % 60) + '秒'
    },
    remainText() {
      if (this.remainSec == null) return '不限时'
      const m = Math.floor(this.remainSec / 60)
      const s = this.remainSec % 60
      return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
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
  onUnload() {
    this.stopTimer()
  },
  onHide() {
    this.stopTimer()
  },
  methods: {
    parseOptions,
    parseReview,
    async init() {
      this.error = ''
      try {
        this.courses = await getCourses()
        if (this.courses.length) this.courseId = this.courses[0].id
        await this.loadExams()
      } catch (e) {
        this.error = e.message
      }
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
      this.loadExams()
    },
    async loadExams() {
      this.error = ''
      try {
        this.exams = await api('/api/exams' + (this.courseId ? '?courseId=' + this.courseId : ''))
        this.loaded = true
      } catch (e) {
        this.error = e.message
      }
    },
    async toggleCreate() {
      this.createOpen = !this.createOpen
      if (this.createOpen && this.courseId && !this.bank.length) {
        try {
          this.bank = await api('/api/exams/bank?courseId=' + this.courseId)
        } catch (e) { /* 题库为空不阻塞 */ }
      }
    },
    onDurationChange(e) {
      this.durationIndex = Number(e.detail.value)
    },
    onModeChange(e) {
      this.modeIndex = Number(e.detail.value)
      if (this.modeIndex === 1 && this.courseId && !this.bank.length) {
        api('/api/exams/bank?courseId=' + this.courseId).then((b) => { this.bank = b }).catch(() => {})
      }
    },
    onCountChange(e) {
      this.countIndex = Number(e.detail.value)
    },
    togglePick(q) {
      const i = this.pickedIds.indexOf(q.id)
      if (i >= 0) this.pickedIds.splice(i, 1)
      else this.pickedIds.push(q.id)
    },
    async create() {
      if (this.creating || !this.form.title.trim()) return
      this.creating = true
      this.error = ''
      try {
        const base = {
          courseId: this.courseId,
          title: this.form.title.trim(),
          durationMin: DURATIONS[this.durationIndex].value
        }
        if (this.modeIndex === 0) {
          await api('/api/exams/auto', { method: 'POST', body: { ...base, count: COUNTS[this.countIndex] } })
        } else {
          if (!this.pickedIds.length) throw new Error('请至少勾选一道题目')
          await api('/api/exams', { method: 'POST', body: { ...base, questionIds: this.pickedIds } })
        }
        this.createOpen = false
        this.form.title = ''
        this.pickedIds = []
        uni.showToast({ title: '考试已创建', icon: 'success' })
        await this.loadExams()
      } catch (e) {
        this.error = e.message
      } finally {
        this.creating = false
      }
    },
    async enter(e) {
      this.error = ''
      try {
        const detail = await api('/api/exams/' + e.id)
        const r = await api('/api/exams/' + e.id + '/start', { method: 'POST' })
        this.examMeta = detail.exam
        this.paper = detail.questions
        this.record = r
        this.answers = {}
        this.stage = 'paper'
        // deadlineAt 格式 'yyyy-MM-dd HH:mm:ss'，按本地时间解析
        if (r.deadlineAt) {
          const deadline = new Date(r.deadlineAt.replace(' ', 'T')).getTime()
          this.remainSec = Math.max(0, Math.floor((deadline - Date.now()) / 1000))
          this.startTimer()
        } else {
          this.remainSec = null
        }
        uni.pageScrollTo({ scrollTop: 0, duration: 100 })
      } catch (err) {
        this.error = err.message
      }
    },
    startTimer() {
      this.stopTimer()
      this._timer = setInterval(() => {
        if (this.remainSec == null) return
        this.remainSec = Math.max(0, this.remainSec - 1)
      }, 1000)
    },
    stopTimer() {
      if (this._timer) {
        clearInterval(this._timer)
        this._timer = null
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
    async submit() {
      if (this.submitting) return
      this.submitting = true
      this.error = ''
      try {
        const items = this.paper.map((q) => ({ questionId: q.id, answer: this.answers[q.id] || '' }))
        await api('/api/exams/records/' + this.record.id + '/submit', { method: 'POST', body: { items } })
        this.stopTimer()
        this.report = await api('/api/exams/records/' + this.record.id + '/report')
        this.paper = []
        this.stage = 'report'
        this.loadExams()
        uni.pageScrollTo({ scrollTop: 0, duration: 100 })
      } catch (e) {
        this.error = e.message
      } finally {
        this.submitting = false
      }
    },
    back() {
      this.report = null
      this.paper = []
      this.stage = 'home'
    },
    async toggleFav(q) {
      try {
        const r = await api('/api/favorites/toggle', { method: 'POST', body: { questionId: q.id } })
        q.favorited = r.favorited
      } catch (e) { /* 静默失败 */ }
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
.card-title { font-size: 29rpx; font-weight: 700; margin-bottom: 10rpx; }
.toolbar { display: flex; align-items: center; gap: 18rpx; }
.toolbar-course { flex: 1; }
.pick-val {
  height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.ghost-btn {
  margin: 0; height: 72rpx; line-height: 72rpx; padding: 0 26rpx;
  border-radius: 14rpx; font-size: 26rpx;
  background: #ffffff; color: #4f6ef7; border: 1rpx solid #c3cdfb;
}
.ipt {
  margin-top: 18rpx; height: 80rpx;
  background: #f5f6fa; border-radius: 14rpx;
  padding: 0 26rpx; font-size: 27rpx;
}
.ph { color: #b3b9c4; }
.pick-row { display: flex; gap: 20rpx; margin-top: 18rpx; }
.pick-item { flex: 1; }
.lab { font-size: 22rpx; color: #9aa0ab; }
.btn {
  margin-top: 22rpx; height: 82rpx; line-height: 82rpx;
  border-radius: 14rpx; font-size: 29rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.btn[disabled] { background: #c3cdfb; }
.bank { max-height: 480rpx; overflow: hidden; margin-top: 16rpx; border-top: 1rpx solid #eceef2; padding-top: 8rpx; }
.bank-item { padding: 14rpx 8rpx; border-bottom: 1rpx solid #eceef2; }
.bank-item.picked { background: #eef1fe; }
.bank-stem { font-size: 24rpx; line-height: 1.6; }
.bank-meta { color: #9aa0ab; font-size: 21rpx; }
.exam-row {
  display: flex; align-items: center; gap: 16rpx;
  padding: 20rpx 4rpx; border-bottom: 1rpx solid #eceef2;
}
.exam-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.exam-title { font-weight: 700; font-size: 27rpx; }
.exam-meta { color: #9aa0ab; font-size: 22rpx; }
.enter-btn {
  margin: 0; height: 64rpx; line-height: 64rpx; padding: 0 28rpx;
  border-radius: 12rpx; font-size: 25rpx;
  background: #4f6ef7; color: #ffffff;
}
.paper-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 6rpx 6rpx 20rpx;
}
.paper-title { font-size: 28rpx; font-weight: 700; flex: 1; min-width: 0; }
.timer { font-size: 34rpx; font-weight: 800; letter-spacing: 0.04em; }
.timer.danger { color: #ff4d4f; }
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
.submit { width: 100%; }
.stat-row { display: flex; }
.stat { flex: 1; text-align: center; }
.num { font-size: 32rpx; font-weight: 800; display: block; }
.stat-lab { color: #9aa0ab; font-size: 22rpx; margin-top: 6rpx; }
.back-btn {
  margin: 24rpx 0 0; height: 72rpx; line-height: 72rpx;
  border-radius: 14rpx; font-size: 26rpx;
  background: #ffffff; color: #6b7280; border: 1rpx solid #dcdfe6;
}
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
.empty-sm { color: #b3b9c4; font-size: 25rpx; text-align: center; padding: 20rpx 0; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
