<template>
  <div>
    <div class="page-title">学习报告</div>
    <div class="page-sub">AI 聚合周期学习数据，生成 Markdown 周报并支持 PDF 导出</div>

    <div class="card">
      <div class="row" style="gap:12px">
        <select v-model="courseId">
          <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <button class="btn" :disabled="loading" @click="genReport">生成周报</button>
      </div>
    </div>

    <div v-if="reports.length" class="card">
      <h3>报告列表</h3>
      <div v-for="r in reports" :key="r.id" class="report-item">
        <div class="report-head" @click="show(r)">
          <b>{{ r.title }}</b>
          <span class="muted small">{{ formatTime(r.createdAt) }}</span>
        </div>
        <div v-if="current?.id === r.id" class="report-body">
          <div class="md" v-html="renderMd(current.content)"></div>
          <button class="btn ghost small" :disabled="downloading" @click="downloadPdf(r)">下载 PDF</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getCourses, listReports, generateWeeklyReport, downloadFile } from '../api'

const courses = ref([])
const reports = ref([])
const current = ref(null)
const courseId = ref(1)
const loading = ref(false)
const downloading = ref(false)

const load = async () => {
  courses.value = await getCourses()
  reports.value = await listReports()
}

const genReport = async () => {
  loading.value = true
  try {
    const r = await generateWeeklyReport(courseId.value)
    reports.value.unshift(r)
    current.value = r
  } finally {
    loading.value = false
  }
}

const show = (r) => {
  current.value = current.value?.id === r.id ? null : r
}

const downloadPdf = async (r) => {
  downloading.value = true
  try {
    await downloadFile(`/api/reports/${r.id}/pdf`, `学习报告-${r.title}.pdf`)
  } finally {
    downloading.value = false
  }
}

const formatTime = (s) => s ? new Date(s).toLocaleString() : ''

const renderMd = (md) => {
  if (!md) return ''
  return md
    .replace(/^### (.*$)/gim, '<h4>$1</h4>')
    .replace(/^## (.*$)/gim, '<h3>$1</h3>')
    .replace(/^# (.*$)/gim, '<h2>$1</h2>')
    .replace(/^\- (.*$)/gim, '<li>$1</li>')
    .replace(/\*\*(.*?)\*\*/g, '<b>$1</b>')
    .replace(/\n/g, '<br>')
}

onMounted(load)
</script>

<style scoped>
.report-item { border: 1px solid var(--border); border-radius: 10px; margin-bottom: 10px; overflow: hidden; }
.report-head { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; background: var(--soft); cursor: pointer; }
.report-body { padding: 14px; }
.md { line-height: 1.7; margin-bottom: 14px; }
.small { font-size: 12px; }
.muted { color: #888; }
</style>
