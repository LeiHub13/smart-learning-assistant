<template>
  <div>
    <div class="page-title">文档管理</div>
    <div class="page-sub">跨课程汇总所有知识库文档：AI 速览、预览内搜索、重命名，以及跳转查看对象存储中的原文件</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <div class="row" style="justify-content:space-between">
        <select v-model="courseFilter" style="max-width:240px">
          <option :value="null">全部课程</option>
          <option v-for="c in courseOptions" :key="c" :value="c">{{ c }}</option>
        </select>
        <input v-model="keyword" type="text" placeholder="按文件名搜索…" style="max-width:280px" />
        <span class="muted small" style="flex:none">{{ filtered.length }} 个文档</span>
      </div>
    </div>

    <div v-for="d in filtered" :key="d.docId" class="card">
      <div class="row" style="justify-content:space-between">
        <input v-if="renamingId === d.docId" v-model="renameVal" class="shrink"
               @click.stop @keyup.enter="doRename(d)" @keyup.esc="cancelRename" @blur="doRename(d)" />
        <span v-else class="shrink">
          📄 <b>{{ d.fileName }}</b>
          <span v-if="d.hasOverview" class="tag" style="margin-left:6px">✨速览</span>
        </span>
        <span class="row" style="gap:8px">
          <a v-if="d.fileUrl" :href="d.fileUrl" target="_blank" class="link" style="font-size:13px">原文件</a>
          <button class="btn ghost small" title="重命名" @click="startRename(d)">✎</button>
          <button class="btn ghost small" @click="togglePreview(d)">{{ expandedId === d.docId ? '收起' : '预览' }}</button>
          <button class="btn danger small" @click="askDelete(d)">删除</button>
        </span>
      </div>
      <div class="muted small" style="margin-top:4px">
        {{ d.courseName }} · {{ d.kbName }} · {{ d.fileType || '未知类型' }} · {{ d.chunkCount }} 个片段 · {{ fmtTime(d.createdAt) }}
        <span v-if="!d.fileUrl" class="muted" style="margin-left:6px">（纯文本入库，无原文件）</span>
      </div>

      <div v-if="confirmDel === d.docId" class="row" style="margin-top:8px;background:#ffebe9;border-radius:8px;padding:8px 12px">
        <span class="shrink" style="font-size:13px">确认删除「{{ d.fileName }}」？文本、向量索引与原文件将一并清理。</span>
        <span class="row" style="gap:8px">
          <button class="btn danger small" @click="doDelete(d)">确认删除</button>
          <button class="btn ghost small" @click="confirmDel = null">取消</button>
        </span>
      </div>

      <div v-if="expandedId === d.docId" class="doc-preview">
        <div v-if="previewLoadingId === d.docId" class="loading"><i></i>加载中…</div>
        <DocViewer v-else-if="previewMap[d.docId]" :kb-id="d.kbId" :doc="d" :preview="previewMap[d.docId]" />
      </div>
    </div>

    <div v-if="!filtered.length && !error" class="card">
      <div class="empty">{{ error ? '' : '暂无文档——先到「我的课程」上传资料' }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import { fmtTime } from '../utils'
import DocViewer from '../components/DocViewer.vue'

defineOptions({ name: 'DocsView' })

const docs = ref([])
const error = ref('')
const courseFilter = ref(null)
const keyword = ref('')
const expandedId = ref(null)
const previewMap = ref({})
const previewLoadingId = ref(null)
const renamingId = ref(null)
const renameVal = ref('')
const confirmDel = ref(null)

const courseOptions = computed(() => [...new Set(docs.value.map((d) => d.courseName).filter((c) => c && c !== '—'))])
const filtered = computed(() => docs.value.filter((d) => {
  if (courseFilter.value && d.courseName !== courseFilter.value) return false
  const kw = keyword.value.trim().toLowerCase()
  return !kw || (d.fileName || '').toLowerCase().includes(kw)
}))

const load = async () => {
  error.value = ''
  try {
    docs.value = await api('/api/kb/docs')
  } catch (e) {
    error.value = e.message
  }
}

onMounted(load)

const togglePreview = async (d) => {
  if (expandedId.value === d.docId) {
    expandedId.value = null
    return
  }
  expandedId.value = d.docId
  if (!previewMap.value[d.docId]) {
    previewLoadingId.value = d.docId
    try {
      previewMap.value[d.docId] = await api('/api/kb/' + d.kbId + '/documents/' + d.docId + '/preview')
    } catch (e) {
      error.value = e.message
      expandedId.value = null
    } finally {
      previewLoadingId.value = null
    }
  }
}

const startRename = (d) => {
  renamingId.value = d.docId
  renameVal.value = d.fileName
}
const cancelRename = () => { renamingId.value = null }

const doRename = async (d) => {
  if (renamingId.value !== d.docId) return
  const name = renameVal.value.trim()
  if (!name || name === d.fileName) {
    cancelRename()
    return
  }
  try {
    const up = await api('/api/kb/' + d.kbId + '/documents/' + d.docId + '/rename', { method: 'PUT', body: { fileName: name } })
    d.fileName = up.fileName
    if (previewMap.value[d.docId]) previewMap.value[d.docId].fileName = up.fileName
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    renamingId.value = null
  }
}

const askDelete = (d) => { confirmDel.value = d.docId }

const doDelete = async (d) => {
  try {
    await api('/api/kb/' + d.kbId + '/documents/' + d.docId, { method: 'DELETE' })
    confirmDel.value = null
    expandedId.value = null
    delete previewMap.value[d.docId]
    docs.value = docs.value.filter((x) => x.docId !== d.docId)
  } catch (e) {
    error.value = e.message
  }
}
</script>

<style scoped>
.doc-preview {
  margin-top: 10px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px;
  background: var(--soft);
}
</style>
