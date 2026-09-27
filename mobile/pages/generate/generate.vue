<template>
  <view class="wrap">
    <view class="card">
      <view class="card-title">生成配置</view>
      <picker class="course-pick" mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
        <view class="pick-box">
          <text class="lab">课程</text>
          <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
        </view>
      </picker>
      <input v-model="kp" class="ipt" placeholder="知识点（可选），如：HashMap / 多线程" placeholder-class="ph" />
      <input v-model="topic" class="ipt" placeholder="讲义主题，如：Java 集合框架" placeholder-class="ph" />
      <view class="ops">
        <button class="op-btn" :disabled="busy || !courseId" @click="genLecture">
          {{ busy && genType === 'lecture' ? '生成中…' : '生成讲义' }}
        </button>
        <button class="op-btn ghost" :disabled="busy || !courseId" @click="genQuestions">
          {{ busy && genType === 'questions' ? '生成中…' : '生成练习题' }}
        </button>
      </view>
    </view>

    <!-- 讲义：流式中纯文本，结束后 Markdown 渲染 -->
    <view v-if="lecture || streaming" class="card">
      <view class="sec-head">
        <text class="card-title">讲义预览</text>
        <text v-if="lectureId" class="tag">已保存</text>
      </view>
      <view v-if="streaming" class="stream-box">
        <text user-select>{{ lecture }}<text class="cursor">▍</text></text>
      </view>
      <view v-else class="md-box">
        <mp-html :content="mdToHtml(lecture)" :selectable="true" />
      </view>
      <button
        v-if="lectureId && !streaming"
        class="kb-btn"
        :disabled="kbSaving || kbSaved"
        @click="saveToKb"
      >
        {{ kbSaved ? '✓ 已入知识库' : kbSaving ? '入库中…' : '存入知识库' }}
      </button>
      <view v-if="kbSaved && kbName" class="kb-tip">已存入「{{ kbName }}」，智能答疑可直接引用该讲义内容</view>
    </view>

    <!-- 生成的练习题 -->
    <view v-if="questions.length" class="card">
      <view class="sec-head">
        <text class="card-title">生成的题目</text>
        <text class="tag ok">{{ questions.length }} 题已入库</text>
      </view>
      <view v-for="(q, i) in questions" :key="i" class="qi">
        <view class="qi-head">
          <text class="tag">{{ q.type }}</text>
          <text v-if="q.kpName" class="qi-kp">{{ q.kpName }}</text>
        </view>
        <view class="qi-stem" user-select>{{ i + 1 }}. {{ q.stem }}</view>
        <view v-if="q.options" class="qi-opts">
          <view v-for="o in parseOptions(q.options)" :key="o.k" class="qi-opt">{{ o.k }}. {{ o.v }}</view>
        </view>
        <view class="qi-ans" user-select>
          <view><text class="ans-lab">参考答案：</text>{{ q.answer }}</view>
          <view v-if="q.analysis"><text class="ans-lab">解析：</text>{{ q.analysis }}</view>
        </view>
      </view>
    </view>

    <view v-if="error" class="err">{{ error }}</view>
  </view>
</template>

<script>
import { api, getCourses } from '../../utils/api'
import { sseStream } from '../../utils/sse'
import { parseOptions } from '../../utils/format'
import { mdToHtml } from '../../utils/md'

export default {
  data() {
    return {
      courses: [],
      courseId: null,
      courseIndex: 0,
      kp: '',
      topic: '',
      busy: false,
      genType: '', // lecture | questions
      streaming: false,
      lecture: '',
      lectureId: null,
      kbSaving: false,
      kbSaved: false,
      kbName: '',
      questions: [],
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
    parseOptions,
    mdToHtml,
    async init() {
      try {
        this.courses = await getCourses()
        if (this.courses.length) {
          this.courseId = this.courses[0].id
          this.courseIndex = 0
        }
      } catch (e) { /* 未建课程时按钮禁用 */ }
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
    },
    async genLecture() {
      if (this.busy) return
      if (!this.courseId || !this.topic.trim()) {
        this.error = '请选择课程并填写讲义主题'
        return
      }
      this.busy = true
      this.genType = 'lecture'
      this.streaming = true
      this.error = ''
      this.questions = []
      this.lecture = ''
      this.lectureId = null
      this.kbSaved = false
      this.kbName = ''
      try {
        await sseStream(
          '/api/generate/lecture/stream',
          { courseId: this.courseId, topic: this.topic.trim(), kp: this.kp.trim() },
          (delta) => { this.lecture += delta },
          (savedId) => { this.lectureId = savedId || null }
        )
      } catch (e) {
        this.error = e.message
      } finally {
        this.streaming = false
        this.busy = false
      }
    },
    async genQuestions() {
      if (this.busy) return
      if (!this.courseId) {
        this.error = '请选择课程'
        return
      }
      this.busy = true
      this.genType = 'questions'
      this.streaming = false
      this.error = ''
      this.lecture = ''
      this.lectureId = null
      try {
        this.questions = await api('/api/generate/questions', {
          method: 'POST',
          body: { courseId: this.courseId, kp: this.kp.trim(), count: 5 }
        })
      } catch (e) {
        this.error = e.message
      } finally {
        this.busy = false
      }
    },
    async saveToKb() {
      if (!this.lectureId || this.kbSaved || this.kbSaving) return
      this.kbSaving = true
      this.error = ''
      try {
        const r = await api('/api/generate/content/' + this.lectureId + '/to-kb', { method: 'POST' })
        this.kbSaved = true
        this.kbName = (r && r.kbName) || '课程知识库'
        uni.showToast({ title: '已存入知识库', icon: 'success' })
      } catch (e) {
        this.error = e.message
      } finally {
        this.kbSaving = false
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
.course-pick { margin-top: 18rpx; }
.lab { font-size: 22rpx; color: #9aa0ab; }
.pick-val {
  margin-top: 8rpx; height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 12rpx; padding: 0 22rpx;
  font-size: 25rpx;
}
.ipt {
  margin-top: 18rpx; height: 80rpx;
  background: #f5f6fa; border-radius: 14rpx;
  padding: 0 26rpx; font-size: 27rpx;
}
.ph { color: #b3b9c4; }
.ops { display: flex; gap: 18rpx; margin-top: 22rpx; }
.op-btn {
  margin: 0; flex: 1; height: 80rpx; line-height: 80rpx;
  border-radius: 14rpx; font-size: 28rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.op-btn.ghost { background: #ffffff; color: #4f6ef7; border: 1rpx solid #c3cdfb; }
.op-btn[disabled] { background: #c3cdfb; color: #ffffff; }
.sec-head { display: flex; align-items: center; gap: 14rpx; margin-bottom: 16rpx; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.tag.ok { background: #e2f4e7; color: #1f7a41; }
.stream-box {
  background: #f7f8fc; border-radius: 12rpx;
  padding: 18rpx 22rpx; font-size: 26rpx; line-height: 1.75;
  min-height: 120rpx;
}
.cursor { animation: blink 1s steps(1) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.md-box { font-size: 26rpx; }
.kb-btn {
  margin-top: 20rpx; height: 76rpx; line-height: 76rpx;
  border-radius: 14rpx; font-size: 27rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.kb-btn[disabled] { background: #e2f4e7; color: #1f7a41; }
.kb-tip { color: #9aa0ab; font-size: 23rpx; margin-top: 14rpx; }
.qi { border: 1rpx solid #eceef2; border-radius: 14rpx; padding: 20rpx 24rpx; margin-bottom: 16rpx; }
.qi-head { display: flex; align-items: center; gap: 12rpx; margin-bottom: 10rpx; }
.qi-kp { color: #9aa0ab; font-size: 22rpx; }
.qi-stem { font-size: 27rpx; line-height: 1.7; margin-bottom: 12rpx; }
.qi-opts { display: flex; flex-direction: column; gap: 8rpx; margin-bottom: 12rpx; }
.qi-opt { font-size: 25rpx; color: #57606a; line-height: 1.6; }
.qi-ans {
  background: #f7f8fc; border-radius: 12rpx;
  padding: 14rpx 20rpx; font-size: 24rpx; line-height: 1.8;
}
.ans-lab { font-weight: 700; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
