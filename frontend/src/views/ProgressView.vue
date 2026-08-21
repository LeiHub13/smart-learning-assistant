<template>
  <div>
    <div class="page-title">学情分析</div>
    <div class="page-sub">基于练习记录分析知识点掌握度，AI 生成复习建议</div>

    <div class="card">
      <div class="row">
        <div>
          <span class="label">课程</span>
          <select v-model="courseId" @change="load">
            <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </div>
        <div class="btns" style="justify-content:flex-end"><button class="btn ghost small" @click="load">刷新</button></div>
      </div>
    </div>

    <div v-if="!summary" class="loading"><i></i>正在分析学情…</div>

    <template v-else>
      <div class="card">
        <h3>总体指标</h3>
        <div class="row">
          <div class="stat" style="flex:1"><div class="num">{{ Math.round(summary.averageMastery) }}%</div><div class="lab">平均掌握度</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ summary.totalAttempts }}</div><div class="lab">累计答题</div></div>
          <div class="stat" style="flex:1"><div class="num">{{ summary.wrongBook.length }}</div><div class="lab">错题数</div></div>
        </div>
      </div>

      <div class="card">
        <h3>AI 复习建议</h3>
        <div style="line-height:1.9;color:#334155">
          <p v-for="(line, i) in adviceLines" :key="i">{{ line }}</p>
        </div>
      </div>

      <div class="card">
        <h3>知识点掌握度</h3>
        <div v-if="!summary.masteries.length" class="empty">暂无练习数据，去「题库练习」做一组题后生成</div>
        <div v-for="m in summary.masteries" :key="m.id" class="mb">
          <div class="nm">{{ m.kpName }}</div>
          <div class="tr"><div class="fl" :style="{ width: m.mastery + '%' }"></div></div>
          <div class="vl">{{ Math.round(m.mastery) }}%</div>
          <div class="ct">{{ m.correctCount }}/{{ m.attempts }} 对</div>
        </div>
      </div>

      <div class="card">
        <h3>错题本</h3>
        <div v-if="!summary.wrongBook.length" class="empty">太棒了，暂无错题</div>
        <table v-else>
          <tr><th>题目</th><th>知识点</th><th>你的答案</th><th>参考答案</th><th>时间</th></tr>
          <tr v-for="(w, i) in summary.wrongBook" :key="i">
            <td>{{ w.stem }}</td>
            <td><span class="tag bad">{{ w.kpName }}</span></td>
            <td>{{ w.userAnswer || '未作答' }}</td>
            <td>{{ w.answer }}</td>
            <td>{{ fmtTime(w.wrongAt) }}</td>
          </tr>
        </table>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api, getCourses } from '../api'
import { fmtTime } from '../utils'

defineOptions({ name: 'ProgressView' })

const courses = ref([])
const courseId = ref(null)
const summary = ref(null)

const adviceLines = computed(() => {
  if (!summary.value || !summary.value.advice) return []
  return summary.value.advice.split('\n').filter(Boolean)
})

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) {
    courseId.value = courses.value[0].id
    await load()
  }
})

const load = async () => {
  if (!courseId.value) return
  summary.value = null
  summary.value = await api('/api/progress/summary?courseId=' + courseId.value)
}
</script>