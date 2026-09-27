<template>
  <view class="wrap">
    <view class="card">
      <view class="filter-row">
        <view class="filter-item">
          <text class="lab">课程</text>
          <picker mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
            <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
          </picker>
        </view>
        <view class="filter-item">
          <text class="lab">类型</text>
          <picker mode="selector" :range="typeOptions" :value="typeIndex" @change="onTypeChange">
            <view class="pick-val">{{ typeOptions[typeIndex] }}</view>
          </picker>
        </view>
      </view>
      <view class="filter-row">
        <view class="filter-item">
          <text class="lab">难度</text>
          <picker mode="selector" :range="diffOptions" :value="diffIndex" @change="onDiffChange">
            <view class="pick-val">{{ diffOptions[diffIndex] }}</view>
          </picker>
        </view>
        <view class="filter-item kw">
          <text class="lab">关键词</text>
          <input
            v-model="keyword"
            class="kw-ipt"
            placeholder="题干 / 知识点"
            placeholder-class="ph"
            confirm-type="search"
            @confirm="search"
          />
        </view>
      </view>
      <button class="search-btn" :disabled="loading" @click="search">{{ loading ? '查询中…' : '搜索' }}</button>
    </view>

    <view v-if="!records.length && !error && !loading" class="card empty-card">本课程暂无符合条件的题目</view>

    <view v-for="q in records" :key="q.id" class="card q-card" @click="toggleDetail(q.id)">
      <view class="q-head">
        <text class="tag">{{ q.type }}</text>
        <text v-if="q.difficulty" class="tag">{{ q.difficulty }}</text>
        <text v-if="q.kpName" class="tag">{{ q.kpName }}</text>
        <text class="tag" :class="{ ok: q.source === 'AI' }">{{ sourceName(q.source) }}</text>
      </view>
      <view class="q-stem" user-select>{{ q.stem }}</view>

      <template v-if="expandedId === q.id">
        <view v-if="parseOptions(q.options).length" class="detail-opts">
          <view v-for="o in parseOptions(q.options)" :key="o.k" class="opt-row">
            <text class="opt-k">{{ o.k }}</text>
            <text class="opt-v">{{ o.v }}</text>
          </view>
        </view>
        <view class="detail-ans" user-select>
          <view><text class="ans-lab">答案：</text>{{ q.answer || '—' }}</view>
          <view v-if="q.analysis"><text class="ans-lab">解析：</text>{{ q.analysis }}</view>
          <view class="ans-time">录入于 {{ fmtTime(q.createdAt) }}</view>
        </view>
        <view class="detail-ops">
          <text class="danger-link" @click.stop="askDelete(q)">删除此题</text>
        </view>
      </template>
      <view v-else class="expand-hint">点击展开答案与解析 ▾</view>
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
const TYPES = ['单选', '多选', '判断', '问答']
const DIFFS = ['基础', '进阶', '综合']

export default {
  data() {
    return {
      courses: [],
      courseId: null,
      courseIndex: 0,
      typeOptions: ['全部', ...TYPES],
      diffOptions: ['全部', ...DIFFS],
      typeIndex: 0,
      diffIndex: 0,
      keyword: '',
      records: [],
      page: 1,
      total: 0,
      expandedId: null,
      loading: false,
      error: ''
    }
  },
  computed: {
    pages() {
      return Math.max(1, Math.ceil(this.total / PAGE_SIZE))
    },
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
    parseOptions,
    async init() {
      try {
        this.courses = await getCourses()
        if (this.courses.length) this.courseId = this.courses[0].id
      } catch (e) { /* 未建课程时为空 */ }
      this.load(1)
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
      this.typeIndex = 0
      this.diffIndex = 0
      this.keyword = ''
      this.load(1)
    },
    onTypeChange(e) {
      this.typeIndex = Number(e.detail.value)
      this.load(1)
    },
    onDiffChange(e) {
      this.diffIndex = Number(e.detail.value)
      this.load(1)
    },
    search() {
      this.load(1)
    },
    async load(p = 1) {
      if (!this.courseId) return
      this.loading = true
      this.error = ''
      try {
        const type = this.typeIndex > 0 ? this.typeOptions[this.typeIndex] : ''
        const diff = this.diffIndex > 0 ? this.diffOptions[this.diffIndex] : ''
        const url = '/api/questions?courseId=' + this.courseId + '&page=' + p + '&size=' + PAGE_SIZE
          + '&type=' + encodeURIComponent(type)
          + '&difficulty=' + encodeURIComponent(diff)
          + '&keyword=' + encodeURIComponent(this.keyword.trim())
        const d = await api(url)
        this.records = d.records || []
        this.total = d.total || 0
        this.page = p
        this.expandedId = null
      } catch (e) {
        this.error = e.message
      } finally {
        this.loading = false
      }
    },
    toggleDetail(id) {
      this.expandedId = this.expandedId === id ? null : id
    },
    sourceName(s) {
      return { AI: 'AI 生成', SEED: '内置', 手动: '手动' }[s] || s || '-'
    },
    askDelete(q) {
      uni.showModal({
        title: '删除题目',
        content: '确定删除这道「' + q.type + '」题吗？若已被练习/考试记录或收藏引用将无法删除。',
        confirmColor: '#e5484d',
        success: (r) => {
          if (r.confirm) this.doDelete(q)
        }
      })
    },
    async doDelete(q) {
      try {
        await api('/api/questions/' + q.id, { method: 'DELETE' })
        uni.showToast({ title: '已删除', icon: 'none' })
        this.load(this.records.length === 1 && this.page > 1 ? this.page - 1 : this.page)
      } catch (e) {
        uni.showToast({ title: e.message, icon: 'none' })
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
.filter-row { display: flex; gap: 20rpx; margin-bottom: 8rpx; }
.filter-item { flex: 1; }
.filter-item.kw { flex: 1.6; }
.lab { font-size: 24rpx; color: #9aa0ab; }
.pick-val {
  margin-top: 10rpx; height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.kw-ipt {
  margin-top: 10rpx; height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.ph { color: #b3b9c4; }
.search-btn {
  margin-top: 16rpx; height: 76rpx; line-height: 76rpx;
  border-radius: 14rpx; font-size: 28rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.search-btn[disabled] { background: #c3cdfb; }
.q-card { position: relative; }
.q-head { display: flex; align-items: center; flex-wrap: wrap; gap: 10rpx; margin-bottom: 12rpx; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.tag.ok { background: #e2f4e7; color: #1f7a41; }
.q-stem { font-size: 28rpx; font-weight: 600; line-height: 1.7; }
.expand-hint { color: #b3b9c4; font-size: 23rpx; margin-top: 14rpx; }
.detail-opts { display: flex; flex-direction: column; gap: 12rpx; margin-top: 18rpx; }
.opt-row {
  display: flex; gap: 16rpx; padding: 14rpx 20rpx;
  border: 1rpx solid #eceef2; border-radius: 12rpx;
  font-size: 25rpx; line-height: 1.6;
}
.opt-k { font-weight: 700; color: #4f6ef7; }
.opt-v { flex: 1; }
.detail-ans {
  margin-top: 16rpx; padding: 16rpx 22rpx;
  background: #f7f8fc; border-radius: 12rpx;
  font-size: 25rpx; line-height: 1.8;
}
.ans-lab { font-weight: 700; }
.ans-time { color: #9aa0ab; font-size: 22rpx; margin-top: 8rpx; }
.detail-ops { margin-top: 14rpx; text-align: right; }
.danger-link { color: #e5484d; font-size: 25rpx; }
.empty-card { text-align: center; color: #b3b9c4; font-size: 26rpx; padding: 60rpx 30rpx; }
.pager { display: flex; align-items: center; justify-content: center; gap: 30rpx; padding: 10rpx 0; }
.pg-btn { color: #4f6ef7; font-size: 25rpx; }
.pg-btn.off { color: #c3c9d4; }
.pg-info { color: #9aa0ab; font-size: 24rpx; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
