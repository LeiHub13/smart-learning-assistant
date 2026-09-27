<template>
  <view class="wrap">
    <view class="card">
      <view class="toolbar">
        <picker class="toolbar-course" mode="selector" :range="courseNames" :value="courseIndex" @change="onCourseChange">
          <view class="pick-val">{{ courseNames[courseIndex] || '选择课程' }}</view>
        </picker>
        <button class="new-btn" @click="toggleEditor(null)">{{ editing ? '收起' : '＋ 新建' }}</button>
      </view>

      <template v-if="editing">
        <input v-model="form.title" class="ipt" maxlength="100" placeholder="标题：一句话概括" placeholder-class="ph" />
        <input v-model="form.kpName" class="ipt" placeholder="知识点（可选），如：HashMap原理" placeholder-class="ph" />
        <textarea
          v-model="form.content"
          class="content-ta"
          auto-height
          placeholder="记录学习/练习中的收获、易错点、思路…"
          placeholder-class="ph"
        />
        <button class="btn" :disabled="saving || !form.title.trim()" @click="save">
          {{ saving ? '保存中…' : (editId ? '保存修改' : '保存笔记') }}
        </button>
      </template>
    </view>

    <view v-if="!notes.length && !editing && loaded" class="card empty-card">暂无笔记</view>

    <view v-for="n in notes" :key="n.id" class="card note-card">
      <view class="note-head" @click="toggle(n.id)">
        <view class="note-head-l">
          <text class="note-title" user-select>{{ n.title }}</text>
          <text v-if="n.kpName" class="tag">{{ n.kpName }}</text>
        </view>
        <text class="chev">{{ expandedMap[n.id] ? '▾' : '▸' }}</text>
      </view>
      <view v-if="expandedMap[n.id]" class="note-content">
        <mp-html :content="mdToHtml(n.content)" :selectable="true" />
      </view>
      <view v-else-if="preview(n.content)" class="note-preview">{{ preview(n.content) }}</view>
      <view class="note-foot">
        <text class="note-time">更新于 {{ fmtTime(n.updatedAt) }}</text>
        <view class="note-ops">
          <text class="op" @click.stop="toggleEditor(n)">编辑</text>
          <text class="op danger" @click.stop="askDelete(n)">删除</text>
        </view>
      </view>
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
      notes: [],
      expandedMap: {},
      editing: false,
      editId: null,
      form: { title: '', kpName: '', content: '' },
      saving: false,
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
        await this.load()
      } catch (e) {
        this.error = e.message
      }
    },
    onCourseChange(e) {
      const i = Number(e.detail.value)
      this.courseIndex = i
      this.courseId = this.courses[i] ? this.courses[i].id : null
      this.load()
    },
    async load() {
      this.error = ''
      try {
        this.notes = await api('/api/notes' + (this.courseId ? '?courseId=' + this.courseId : ''))
        this.expandedMap = {}
        this.loaded = true
      } catch (e) {
        this.error = e.message
      }
    },
    /** 折叠时的内容摘要：取第一行非空文字，超 60 字截断 */
    preview(s) {
      if (!s) return ''
      const first = s.split('\n').find((l) => l.trim()) || ''
      return first.length > 60 ? first.slice(0, 60) + '…' : first
    },
    toggle(id) {
      this.expandedMap[id] = !this.expandedMap[id]
    },
    toggleEditor(n) {
      if (n) {
        this.editId = n.id
        this.form = { title: n.title, kpName: n.kpName || '', content: n.content }
      } else {
        this.editId = null
        this.form = { title: '', kpName: '', content: '' }
      }
      this.editing = !this.editing
      if (!this.editing) this.editId = null
    },
    async save() {
      if (this.saving || !this.form.title.trim()) return
      this.saving = true
      this.error = ''
      try {
        const body = { ...this.form, courseId: this.courseId }
        if (this.editId) await api('/api/notes/' + this.editId, { method: 'PUT', body })
        else await api('/api/notes', { method: 'POST', body })
        this.editing = false
        this.editId = null
        uni.showToast({ title: '已保存', icon: 'success' })
        await this.load()
      } catch (e) {
        this.error = e.message
      } finally {
        this.saving = false
      }
    },
    askDelete(n) {
      uni.showModal({
        title: '删除笔记',
        content: '确定删除「' + n.title + '」吗？',
        confirmColor: '#e5484d',
        success: (r) => {
          if (r.confirm) this.doDelete(n)
        }
      })
    },
    async doDelete(n) {
      try {
        await api('/api/notes/' + n.id, { method: 'DELETE' })
        await this.load()
      } catch (e) {
        this.error = e.message
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
.toolbar { display: flex; align-items: center; gap: 18rpx; }
.toolbar-course { flex: 1; }
.pick-val {
  height: 72rpx; line-height: 72rpx;
  background: #f5f6fa; border-radius: 14rpx; padding: 0 24rpx;
  font-size: 26rpx;
}
.new-btn {
  margin: 0; height: 72rpx; line-height: 72rpx; padding: 0 28rpx;
  border-radius: 14rpx; font-size: 26rpx;
  background: #4f6ef7; color: #ffffff;
}
.ipt {
  margin-top: 18rpx; height: 80rpx;
  background: #f5f6fa; border-radius: 14rpx;
  padding: 0 26rpx; font-size: 27rpx;
}
.ph { color: #b3b9c4; }
.content-ta {
  width: 100%; min-height: 220rpx; box-sizing: border-box;
  margin-top: 18rpx; padding: 20rpx 26rpx;
  background: #f5f6fa; border-radius: 14rpx;
  font-size: 27rpx; line-height: 1.7;
}
.btn {
  margin-top: 20rpx; height: 80rpx; line-height: 80rpx;
  border-radius: 14rpx; font-size: 28rpx; font-weight: 600;
  background: #4f6ef7; color: #ffffff;
}
.btn[disabled] { background: #c3cdfb; }
.tag {
  padding: 4rpx 16rpx; border-radius: 999rpx;
  background: #eef1fe; color: #4f6ef7; font-size: 22rpx;
}
.note-head { display: flex; align-items: center; justify-content: space-between; }
.note-head-l { display: flex; align-items: center; gap: 14rpx; flex: 1; min-width: 0; }
.note-title { font-size: 29rpx; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chev { color: #b3b9c4; font-size: 24rpx; flex-shrink: 0; }
.note-content { margin: 18rpx 0 6rpx; font-size: 26rpx; line-height: 1.8; }
.note-preview { color: #94a3b8; font-size: 24rpx; margin: 14rpx 0 4rpx; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.note-foot {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 16rpx; padding-top: 14rpx; border-top: 1rpx solid #eceef2;
}
.note-time { color: #9aa0ab; font-size: 22rpx; }
.note-ops { display: flex; gap: 28rpx; }
.op { color: #4f6ef7; font-size: 24rpx; }
.op.danger { color: #e5484d; }
.empty-card { text-align: center; color: #b3b9c4; font-size: 26rpx; padding: 60rpx 30rpx; }
.err { color: #e5484d; font-size: 26rpx; padding: 20rpx 6rpx; }
</style>
