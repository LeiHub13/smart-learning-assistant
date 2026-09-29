<template>
  <div>
    <div class="page-title">讲义/练习题生成</div>
    <div class="page-sub">AI 一键生成讲义与练习题，题目自动进入题库供学生练习</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <h3>生成配置</h3>
      <div class="row" style="margin-bottom:12px">
        <div>
          <span class="label">课程</span>
          <select v-model="courseId" @change="loadKbs">
            <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div>
          <span class="label">知识库（可选）</span>
          <select v-model="kbChoice">
            <option value="">不使用知识库</option>
            <option v-if="kbs.length > 1" value="all">全部知识库（{{ kbs.length }} 个）</option>
            <option v-for="k in kbs" :key="k.id" :value="String(k.id)">{{ k.name }}</option>
          </select>
        </div>
        <div>
          <span class="label">知识点（可选）</span>
          <input v-model="kp" type="text" placeholder="如：HashMap / 多线程" />
        </div>
      </div>
      <div class="row">
        <div>
          <span class="label">讲义主题</span>
          <input v-model="topic" type="text" placeholder="如：Java 集合框架" />
        </div>
        <div>
          <span class="label">生成题目数</span>
          <select v-model.number="qCount">
            <option v-for="n in 10" :key="n" :value="n">{{ n }} 道</option>
          </select>
        </div>
        <div class="btns">
          <button class="btn" :disabled="busy" @click="genLecture">生成讲义</button>
          <button class="btn ghost" :disabled="busy" @click="genQuestions">生成练习题</button>
        </div>
      </div>
      <div v-if="kbChoice" class="muted small" style="margin-top:10px">
        将参考「{{ kbLabel }}」内与{{ activeKbHint }}相关的课程资料生成；不填{{ kbChoice && !topic ? '主题' : '知识点' }}时资料参考有限
      </div>
    </div>

    <div v-if="lecture" class="card">
      <div class="row" style="justify-content:space-between">
        <h3 style="margin:0">讲义预览 <span class="tag">已保存</span></h3>
        <div class="btns" v-if="lectureId">
          <button class="btn ghost small" :disabled="kbSaving" @click="saveToKb">
            {{ kbSaved ? '✓ 已入知识库' : kbSaving ? '入库中…' : '存入知识库' }}
          </button>
          <button class="btn ghost small" @click="downloadLecture">下载 .md</button>
        </div>
      </div>
      <div v-if="kbSaved && kbName" class="muted small" style="margin:6px 0 0">已存入「{{ kbName }}」，智能答疑可直接引用该讲义内容</div>
      <div class="md" v-html="mdToHtml(lecture)"></div>
    </div>

    <div v-if="questions.length" class="card">
      <h3>生成的题目 <span class="tag ok">{{ questions.length }} 题已入库</span></h3>
      <div v-for="(q, i) in questions" :key="i" class="qi">
        <div class="hd">
          <span class="tag">{{ q.type }}</span>
          <span v-if="q.kpName" style="color:var(--muted);font-size:12px">{{ q.kpName }}</span>
        </div>
        <div style="margin-bottom:6px">{{ q.stem }}</div>
        <div v-if="q.options" class="ans">
          <div v-for="(o, j) in parseOptions(q.options)" :key="j">{{ o.k }}. {{ o.v }}</div>
        </div>
        <div class="ans" style="margin-top:6px"><b>参考答案：</b>{{ q.answer }}<br /><b>解析：</b>{{ q.analysis }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { api, getCourses, streamLecture, downloadFile } from '../api'
import { parseOptions, mdToHtml } from '../utils'

defineOptions({ name: 'GenerateView' })

const courses = ref([])
const courseId = ref(null)
const kbs = ref([])
const kbChoice = ref('')
const kp = ref('')
const topic = ref('')
const qCount = ref(5)
const busy = ref(false)
const lecture = ref('')
const lectureId = ref(null)
const kbSaved = ref(false)
const kbName = ref('')
const kbSaving = ref(false)
const questions = ref([])
const error = ref('')

/* 课程 -> 知识库列表联动；换课程重置选择 */
const loadKbs = async () => {
  kbChoice.value = ''
  kbs.value = []
  if (!courseId.value) return
  try {
    kbs.value = await api(`/api/courses/${courseId.value}/kb`) || []
  } catch { /* 知识库列表拉取失败不阻塞生成 */ }
}
watch(courseId, loadKbs)

/* kbChoice -> 提交用的 kbIds：''=不使用，'all'=全部，否则单库 */
const kbIds = computed(() => {
  if (!kbChoice.value || !kbs.value.length) return []
  if (kbChoice.value === 'all') return kbs.value.map((k) => k.id)
  const id = Number(kbChoice.value)
  return kbs.value.some((k) => k.id === id) ? [id] : []
})
const kbLabel = computed(() => (kbChoice.value === 'all' ? '全部知识库' : (kbs.value.find((k) => String(k.id) === kbChoice.value)?.name || '')))
const activeKbHint = computed(() => (kp.value.trim() ? `「${kp.value.trim()}」` : '关键词'))

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) {
    courseId.value = courses.value[0].id
    await loadKbs()
  }
})

const genLecture = async () => {
  if (!courseId.value || !topic.value.trim()) {
    error.value = '请填写课程与讲义主题'
    return
  }
  busy.value = true
  error.value = ''
  questions.value = []
  lecture.value = ''
  lectureId.value = null
  kbSaved.value = false
  kbName.value = ''
  try {
    await streamLecture(
      { courseId: courseId.value, topic: topic.value.trim(), kp: kp.value.trim(), kbIds: kbIds.value },
      (delta) => { lecture.value += delta },
      (savedId) => { lectureId.value = savedId || null }
    )
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

const downloadLecture = async () => {
  if (!lectureId.value) return
  try {
    const name = (topic.value.trim() || '未命名').replace(/[\\/:*?"<>|]/g, '-')
    await downloadFile(`/api/generate/content/${lectureId.value}/download`, `讲义-${name}.md`)
  } catch (e) {
    error.value = e.message
  }
}

const saveToKb = async () => {
  if (!lectureId.value || kbSaved.value) return
  kbSaving.value = true
  error.value = ''
  try {
    const r = await api(`/api/generate/content/${lectureId.value}/to-kb`, { method: 'POST' })
    kbSaved.value = true
    kbName.value = r.kbName || '课程知识库'
  } catch (e) {
    error.value = e.message
  } finally {
    kbSaving.value = false
  }
}

const genQuestions = async () => {
  if (!courseId.value) {
    error.value = '请选择课程'
    return
  }
  busy.value = true
  error.value = ''
  lecture.value = ''
  lectureId.value = null
  try {
    questions.value = await api('/api/generate/questions', {
      method: 'POST',
      body: { courseId: courseId.value, kp: kp.value.trim(), count: qCount.value, kbIds: kbIds.value }
    })
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.md { line-height: 1.8; color: var(--text); }
.md :deep(h1), .md :deep(h2), .md :deep(h3), .md :deep(h4) { margin: 14px 0 6px; font-weight: 800; color: var(--text); }
.md :deep(strong) { color: var(--text); }
.md :deep(code) { background: #f4f1ea; border-radius: 4px; padding: 1px 5px; font-size: 13px; }
.md :deep(pre) { background: #f4f1ea; border-radius: 8px; padding: 12px 14px; overflow-x: auto; white-space: pre-wrap; margin: 8px 0; }
.md :deep(pre code) { background: none; padding: 0; }
.md :deep(blockquote) { margin: 8px 0; padding: 4px 12px; border-left: 3px solid var(--border); color: var(--muted); }

html.dark .md :deep(code), html.dark .md :deep(pre) { background: var(--soft); }
</style>