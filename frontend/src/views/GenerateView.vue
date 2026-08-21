<template>
  <div>
    <div class="page-title">AI 内容生成</div>
    <div class="page-sub">AI 一键生成讲义与练习题，题目自动进入题库供学生练习</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <h3>生成配置</h3>
      <div class="row" style="margin-bottom:12px">
        <div>
          <span class="label">课程</span>
          <select v-model="courseId">
            <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
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
        <div class="btns">
          <button class="btn" :disabled="busy" @click="genLecture">生成讲义</button>
          <button class="btn ghost" :disabled="busy" @click="genQuestions">生成练习题</button>
        </div>
      </div>
    </div>

    <div v-if="lecture" class="card">
      <h3>讲义预览 <span class="tag">已保存</span></h3>
      <div class="gen">{{ lecture }}</div>
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
import { ref, onMounted } from 'vue'
import { api, getCourses } from '../api'
import { parseOptions } from '../utils'

defineOptions({ name: 'GenerateView' })

const courses = ref([])
const courseId = ref(null)
const kp = ref('')
const topic = ref('')
const busy = ref(false)
const lecture = ref('')
const questions = ref([])
const error = ref('')

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) courseId.value = courses.value[0].id
})

const genLecture = async () => {
  if (!courseId.value || !topic.value.trim()) {
    error.value = '请填写课程与讲义主题'
    return
  }
  busy.value = true
  error.value = ''
  questions.value = []
  try {
    const g = await api('/api/generate/lecture', {
      method: 'POST',
      body: { courseId: courseId.value, topic: topic.value.trim(), kp: kp.value.trim() }
    })
    lecture.value = g.content
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
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
  try {
    questions.value = await api('/api/generate/questions', {
      method: 'POST',
      body: { courseId: courseId.value, kp: kp.value.trim(), count: 5 }
    })
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}
</script>