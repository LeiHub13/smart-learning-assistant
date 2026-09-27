<template>
  <div>
    <div class="ov-card">
      <div class="ov-head">
        <b>AI 速览</b>
        <button class="btn ghost small" :disabled="ovLoading" @click="genOverview">
          {{ ovLoading ? '生成中…' : (doc.overview ? '重新生成' : '生成速览') }}
        </button>
      </div>
      <div v-if="ovLoading" class="loading"><i></i>AI 正在通读全文提炼要点…</div>
      <template v-else-if="doc.overview && ov">
        <div class="ov-summary">{{ ov.summary }}</div>
        <div class="ov-sec">核心要点</div>
        <ul class="ov-list"><li v-for="(p, pi) in ov.points" :key="'p' + pi">{{ p }}</li></ul>
        <template v-if="ov.examPoints && ov.examPoints.length">
          <div class="ov-sec">可能考点</div>
          <ul class="ov-list"><li v-for="(e, ei) in ov.examPoints" :key="'e' + ei">{{ e }}</li></ul>
        </template>
      </template>
      <div v-else class="muted small">尚未生成速览——点右上按钮，AI 通读全文提炼要点与可能考点</div>
    </div>

    <div class="row" style="justify-content:space-between;margin-bottom:6px">
      <span class="muted small">
        {{ preview.fileName }} · {{ preview.fileType }} · {{ preview.chunkCount }} 个片段 · {{ (preview.text || '').length }} 字 · {{ preview.parseStatus }}
      </span>
      <button class="btn ghost small" @click="downloadPreviewText">{{ preview.fileUrl ? '下载文本副本' : '下载文本' }}</button>
    </div>
    <div class="row" style="margin-bottom:6px">
      <input v-model="pvSearch" type="text" placeholder="在本文档内搜索…" style="max-width:280px" />
      <span class="muted small" style="flex:none;white-space:nowrap">{{ pvMatchCount ? (pvMatchIndex + 1) + ' / ' + pvMatchCount : (pvSearch ? '无匹配' : '') }}</span>
      <div class="btns">
        <button class="btn ghost small" :disabled="!pvMatchCount" @click="pvJump(-1)">上一个</button>
        <button class="btn ghost small" :disabled="!pvMatchCount" @click="pvJump(1)">下一个</button>
      </div>
    </div>
    <div class="pv-text" v-html="pvHtml"></div>
    <div v-if="preview.fileUrl" style="margin-top:8px">
      <a :href="preview.fileUrl" target="_blank" class="link">查看原文件（对象存储）</a>
      <span class="muted small" style="margin-left:8px">PDF 会在浏览器内打开，其他类型为下载</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { api } from '../api'

const props = defineProps({
  kbId: { type: Number, required: true },
  doc: { type: Object, required: true },
  preview: { type: Object, required: true },
  loading: { type: Boolean, default: false }
})

/* AI 速览：overview 存的是 JSON 串，解析失败按未生成处理 */
const ovLoading = ref(false)
const ov = computed(() => {
  if (!props.doc.overview) return null
  try { return JSON.parse(props.doc.overview) } catch (e) { return null }
})

const genOverview = async () => {
  if (ovLoading.value) return
  ovLoading.value = true
  try {
    const r = await api('/api/kb/' + props.kbId + '/documents/' + props.doc.id + '/overview', { method: 'POST' })
    props.doc.overview = JSON.stringify(r)
  } catch (e) {
    props.doc.overview = null
    alert(e.message)
  } finally {
    ovLoading.value = false
  }
}

/* 预览内搜索：命中高亮 + 上/下跳转 */
const pvSearch = ref('')
const pvMatchIndex = ref(0)

const pvMatchCount = computed(() => {
  const t = props.preview?.text || ''
  const q = pvSearch.value.trim().toLowerCase()
  if (!q) return 0
  let n = 0
  let i = 0
  const lower = t.toLowerCase()
  while ((i = lower.indexOf(q, i)) >= 0) { n++; i += q.length }
  return n
})

const pvHtml = computed(() => {
  const t = props.preview?.text
  if (!t) return '（无文本内容：索引未完成或文档为空）'
  const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const q = pvSearch.value.trim()
  if (!q) return esc(t)
  const lower = t.toLowerCase()
  const ql = q.toLowerCase()
  let out = ''
  let i = 0
  let n = 0
  while (true) {
    const j = lower.indexOf(ql, i)
    if (j < 0) { out += esc(t.slice(i)); break }
    out += esc(t.slice(i, j))
    n++
    out += '<mark class="pv-hit' + (n === pvMatchIndex.value + 1 ? ' on' : '') + '">' + esc(t.slice(j, j + q.length)) + '</mark>'
    i = j + q.length
  }
  return out
})

const pvJump = (delta) => {
  const total = pvMatchCount.value
  if (!total) return
  pvMatchIndex.value = (pvMatchIndex.value + delta + total) % total
  nextTick(() => {
    const el = document.querySelector('.pv-hit.on')
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

watch(pvSearch, () => {
  pvMatchIndex.value = 0
  nextTick(() => {
    const el = document.querySelector('.pv-hit.on')
    if (el) el.scrollIntoView({ block: 'center' })
  })
})

/* 下载预览文本：粘贴文本入库的文档没有原始文件，用 Blob 生成下载 */
const downloadPreviewText = () => {
  const t = props.preview?.text || ''
  const blob = new Blob([t], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = (props.preview.fileName || '文档') + '.txt'
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<style scoped>
.ov-card {
  background: #fff; border: 1px solid var(--border); border-radius: 10px;
  padding: 12px 14px; margin-bottom: 10px;
}
.ov-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.ov-head b { font-size: 13px; color: var(--primary); }
.ov-summary { font-size: 13px; line-height: 1.7; font-weight: 600; }
.ov-sec { font-size: 12px; font-weight: 700; color: var(--muted); margin: 10px 0 4px; }
.ov-list { margin: 0; padding-left: 18px; }
.ov-list li { font-size: 13px; line-height: 1.75; color: #334155; }
.pv-text {
  white-space: pre-wrap; line-height: 1.8; color: #334155;
  max-height: 320px; overflow: auto;
  font-family: Consolas, "Microsoft YaHei", monospace; font-size: 13px;
  background: #faf9f7; border-radius: 8px; padding: 10px 12px;
}
.pv-hit { background: #fff3b8; border-radius: 3px; padding: 0 1px; }
.pv-hit.on { background: #ffd33d; outline: 2px solid var(--primary); }
</style>
