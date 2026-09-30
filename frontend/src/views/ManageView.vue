<template>
  <div>
    <div class="page-title">我的课程</div>
    <div class="page-sub">创建课程、上传资料构建知识库，供 AI 答疑检索</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <h3>创建课程</h3>
      <div class="row">
        <div>
          <span class="label">课程名称</span>
          <input v-model="newName" type="text" placeholder="如：数据结构与算法" />
        </div>
        <div>
          <span class="label">课程简介</span>
          <input v-model="newDesc" type="text" />
        </div>
        <label class="row" style="gap:6px;align-items:center;flex:none;padding-top:20px">
          <input v-model="newInHub" type="checkbox" style="width:auto" />
          <span class="label" style="margin:0">入驻课程 Hub</span>
        </label>
        <div class="btns">
          <button class="btn" @click="createCourse">创建</button>
        </div>
      </div>
    </div>

    <div class="grid2">
      <CourseCard v-for="c in courses" :key="c.id" :course="c" :active="expanded === c.id" :clickable="false">
        <template #actions>
          <button class="btn ghost small" @click="goPractice(c)">去练习</button>
          <button class="btn ghost small" @click="openDetail(c)">知识库</button>
          <button v-if="c.ownerId === meId" class="btn ghost small" @click="toggleHub(c)">
            {{ c.inHub ? '移出 Hub' : '入驻 Hub' }}
          </button>
          <button v-if="c.ownerId === meId" class="btn danger small" @click="askDeleteCourse(c)">删除课程</button>
          <button v-if="c.role !== 'creator'" class="btn ghost small" @click="askLeaveCourse(c)">退出课程</button>
        </template>
      </CourseCard>
    </div>

    <div v-if="sel" class="modal-mask" @click.self="expanded = null">
      <div class="modal-box wide">
        <div class="detail-hd">
          <div>
            <h3 class="detail-title">{{ sel.name }} · 知识库</h3>
            <div class="detail-sub">创建知识库、上传资料，供 AI 答疑检索</div>
          </div>
          <button class="btn ghost small" @click="expanded = null">关闭</button>
        </div>

        <div class="row" style="margin-bottom:10px">
          <input v-model="newKbName" type="text" placeholder="新知识库名称" />
          <div class="btns">
            <button class="btn ghost small" @click="createKb(sel.id)">创建知识库</button>
          </div>
        </div>
        <div v-for="kb in sel.kbs" :key="kb.id" class="qi">
          <div class="hd">
            <b>{{ kb.name }}</b>
            <span class="tag">知识库</span>
            <button class="btn ghost small" style="margin-left:auto" @click="toggleUpload(kb.id)">上传文档</button>
            <button class="btn ghost small" style="margin-left:8px" @click="openGraph(kb)">图谱</button>
            <button class="btn danger small" style="margin-left:8px" @click="askDeleteKb(sel.id, kb.id, kb.name)">删除知识库</button>
          </div>
          <div v-for="d in kb.docs" :key="d.id" class="ans" style="margin:4px 0">
            <div class="row" style="justify-content:space-between">
              <input v-if="renamingDocId === d.id" v-model="docRenameVal" class="shrink"
                     @click.stop @keyup.enter="doRenameDoc(kb.id, d)" @keyup.esc="cancelRenameDoc" @blur="doRenameDoc(kb.id, d)" />
              <span v-else class="shrink">📄 {{ d.fileName }} · {{ d.chunkCount }} 个片段 <span v-if="d.overview" class="tag">✨速览</span></span>
              <span class="row" style="gap:8px">
                <button class="btn ghost small" title="重命名" @click="startRenameDoc(d)">✎</button>
                <button class="btn ghost small" @click="togglePreview(kb.id, d.id)">
                  {{ previewDocId === d.id ? '收起预览' : '预览' }}
                </button>
                <button class="btn danger small" @click="deleteDoc(kb.id, d.id)">删除</button>
              </span>
            </div>
            <div v-if="previewDocId === d.id" class="doc-preview">
              <div v-if="previewLoading" class="loading"><i></i>加载中…</div>
              <DocViewer v-else-if="preview" :kb-id="kb.id" :doc="d" :preview="preview" />
            </div>
          </div>
          <div v-if="uploadingKb === kb.id" style="margin-top:8px">
            <div class="row">
              <input v-model="docName" type="text" placeholder="文档名（如：HashMap原理.md）" />
              <div class="btns">
              <label class="btn ghost small" style="cursor:pointer">
                📄 选择文件
                <input type="file" accept=".txt,.md,.markdown,.pdf,.docx,.java,.json,.xml,.yml,.sql" style="display:none" @change="pickFile" />
              </label>
              <button class="btn small" @click="doUpload(sel.id, kb.id)">解析入库</button>
            </div>
            </div>
            <div v-if="pickedFile" class="row" style="margin-top:8px;align-items:center;gap:8px">
              <span class="shrink" style="font-size:13px">已选择：{{ pickedFile.name }}（{{ (pickedFile.size / 1024).toFixed(0) }} KB），上传原始文件由后端解析</span>
              <button class="btn ghost small" @click="clearPicked">改为粘贴文本</button>
            </div>
            <textarea v-model="docContent" :disabled="!!pickedFile"
                      placeholder="支持 .txt/.md/.pdf/.docx（旧版 .doc 请先另存为 .docx）；选择文件自动解析，或直接粘贴文档内容；系统自动分块向量化…"
                      style="margin-top:8px;min-height:130px"></textarea>
          </div>
        </div>
      </div>
    </div>

    <!-- 知识图谱：LLM 从该知识库资料抽取概念与关系，ECharts 力导向图展示 -->
    <div v-if="graphModal" class="modal-mask" @click.self="closeGraph">
      <div class="modal-box wide kg-box">
        <div class="detail-hd">
          <div>
            <h3 class="detail-title">{{ graphModal.kb.name }} · 知识图谱</h3>
            <div class="detail-sub" v-if="graphModal.data">
              {{ graphModal.data.nodeCount }} 个概念 · {{ graphModal.data.edgeCount }} 条关系 ·
              生成于 {{ fmtKgTime(graphModal.data.updatedAt) }}；拖拽平移、滚轮缩放，点击节点可发起答疑
            </div>
            <div class="detail-sub" v-else>尚未生成图谱，点下方按钮从知识库资料中抽取概念与关系</div>
          </div>
          <button class="btn ghost small" @click="closeGraph">关闭</button>
        </div>
        <div class="kg-ops">
          <button class="btn small" :disabled="graphModal.rebuilding" @click="rebuildGraph">
            {{ graphModal.rebuilding ? '生成中…' : (graphModal.data ? '重新生成' : '生成图谱') }}
          </button>
          <span class="kg-legend">
            <span v-for="(c, t) in KG_TYPE_COLORS" :key="t"><i :style="{ background: c }"></i>{{ t }}</span>
          </span>
        </div>
        <div class="kg-err" v-if="graphModal.err">{{ graphModal.err }}</div>
        <div class="kg-wrap">
          <div v-if="!graphModal.data" class="kg-empty">
            {{ graphModal.rebuilding ? '正在通读资料、抽取概念与关系…（约十几秒）' : '暂无图谱' }}
          </div>
          <div ref="kgRef" class="kg-canvas"></div>
        </div>
      </div>
    </div>

    <div v-if="confirmKb" class="modal-mask" @click.self="confirmKb = null">
      <div class="modal-box">
        <h3>删除知识库</h3>
        <p>确定删除知识库「{{ confirmKb.name }}」吗？其中的文档和向量索引也会一并清除，且无法恢复。</p>
        <div class="modal-ops">
          <button class="btn ghost small" @click="confirmKb = null">取消</button>
          <button class="btn danger small" @click="doDeleteKb">删除</button>
        </div>
      </div>
    </div>

    <div v-if="confirmLeave" class="modal-mask" @click.self="confirmLeave = null">
      <div class="modal-box">
        <h3>退出课程</h3>
        <p>确定退出课程「{{ confirmLeave.name }}」吗？退出后该课程不再出现在「我的课程」里，你已产生的练习/笔记等数据仍保留；课程若仍在 Hub 中可随时重新加入。</p>
        <div class="modal-ops">
          <button class="btn ghost small" @click="confirmLeave = null">取消</button>
          <button class="btn danger small" @click="doLeaveCourse">确认退出</button>
        </div>
      </div>
    </div>

    <div v-if="confirmCourse" class="modal-mask" @click.self="confirmCourse = null">
      <div class="modal-box">
        <h3>删除课程</h3>
        <p>确定删除课程「{{ confirmCourse.name }}」吗？该课程的知识库/文档/题目/练习/考试/掌握度/笔记/报告/计划与课程绑定会话将<b>一并删除</b>，且无法恢复。</p>
        <div class="modal-ops">
          <button class="btn ghost small" @click="confirmCourse = null">取消</button>
          <button class="btn danger small" @click="doDeleteCourse">确认删除</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import DocViewer from '../components/DocViewer.vue'
import { useRouter } from 'vue-router'
import { api, getCourses } from '../api'
import CourseCard from '../components/CourseCard.vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GraphChart, TooltipComponent, CanvasRenderer])

defineOptions({ name: 'ManageView' })

const router = useRouter()
const goPractice = (c) => router.push('/practice?courseId=' + c.id)

const courses = ref([])
const expanded = ref(null)
const sel = computed(() => courses.value.find((c) => c.id === expanded.value) || null)
const meId = ref(null)
const confirmCourse = ref(null)
const confirmLeave = ref(null)
const uploadingKb = ref(null)
const previewDocId = ref(null)
const preview = ref(null)
const previewLoading = ref(false)
const genOverview = async (kbId2, d) => {
  if (ovLoading.value) return
  ovLoading.value = true
  try {
    const r = await api('/api/kb/' + kbId2 + '/documents/' + d.id + '/overview', { method: 'POST' })
    d.overview = JSON.stringify(r)
  } catch (e) {
    showHint(e.message)
  } finally {
    ovLoading.value = false
  }
}

/* ===== 文档重命名：行内编辑，enter/blur 确认、esc 取消 ===== */
const renamingDocId = ref(null)
const docRenameVal = ref('')

const startRenameDoc = (d) => {
  renamingDocId.value = d.id
  docRenameVal.value = d.fileName
}

const cancelRenameDoc = () => { renamingDocId.value = null }

const doRenameDoc = async (kbId2, d) => {
  if (renamingDocId.value !== d.id) return
  const name = docRenameVal.value.trim()
  if (!name || name === d.fileName) {
    cancelRenameDoc()
    return
  }
  try {
    const up = await api('/api/kb/' + kbId2 + '/documents/' + d.id + '/rename', { method: 'PUT', body: { fileName: name } })
    d.fileName = up.fileName
    if (preview.value && preview.value.docId === d.id) preview.value.fileName = up.fileName
    showHint('已重命名为「' + up.fileName + '」')
  } catch (e) {
    showHint(e.message)
  } finally {
    renamingDocId.value = null
  }
}

const togglePreview = async (kbId, docId) => {
  if (previewDocId.value === docId) {
    previewDocId.value = null
    preview.value = null
    return
  }
  previewDocId.value = docId
  preview.value = null
  previewLoading.value = true
  try {
    preview.value = await api('/api/kb/' + kbId + '/documents/' + docId + '/preview')
  } catch (e) {
    previewDocId.value = null
  } finally {
    previewLoading.value = false
  }
}
const newName = ref('')
const newDesc = ref('')
const newInHub = ref(false)
const newKbName = ref('')
const docName = ref('')
const docContent = ref('')
const pickedFile = ref(null)
const error = ref('')
const confirmKb = ref(null)

let hintTimer = null
const showHint = (msg) => {
  error.value = msg
  clearTimeout(hintTimer)
  hintTimer = setTimeout(() => (error.value = ''), 2800)
}

onMounted(async () => {
  courses.value = await getCourses()
  try {
    meId.value = Number((await api('/api/auth/me')).id)
  } catch (e) { /* 忽略 */ }
})

const askDeleteCourse = (c) => {
  confirmCourse.value = c
}

const askLeaveCourse = (c) => {
  confirmLeave.value = c
}

const doLeaveCourse = async () => {
  const c = confirmLeave.value
  if (!c) return
  try {
    await api('/api/courses/' + c.id + '/enroll', { method: 'DELETE' })
    confirmLeave.value = null
    showHint('已退出课程「' + c.name + '」')
    courses.value = await getCourses(true)
  } catch (e) {
    confirmLeave.value = null
    showHint(e.message)
  }
}

const doDeleteCourse = async () => {
  const c = confirmCourse.value
  if (!c) return
  try {
    await api('/api/courses/' + c.id, { method: 'DELETE' })
    confirmCourse.value = null
    showHint('课程「' + c.name + '」已删除')
    courses.value = await getCourses(true)
  } catch (e) {
    confirmCourse.value = null
    showHint(e.message)
  }
}

const createCourse = async () => {
  if (!newName.value.trim()) {
    error.value = '请输入课程名称'
    return
  }
  await api('/api/courses', { method: 'POST', body: { name: newName.value.trim(), description: newDesc.value, inHub: newInHub.value } })
  newName.value = ''
  newDesc.value = ''
  newInHub.value = false
  courses.value = await getCourses(true)
}

const toggleHub = async (c) => {
  error.value = ''
  try {
    await api('/api/courses/' + c.id + '/hub', { method: 'POST', body: { inHub: !c.inHub } })
    c.inHub = !c.inHub
  } catch (e) {
    error.value = e.message
  }
}

const openDetail = async (c) => {
  expanded.value = c.id
  uploadingKb.value = null
  previewDocId.value = null
  preview.value = null
  await refreshKbs(c.id)
}

/** 原位刷新某课程的知识库列表：不整体替换 courses、不折叠，避免闪烁 */
const refreshKbs = async (courseId) => {
  const c = courses.value.find((x) => x.id === courseId)
  if (!c) return
  const kbs = await api('/api/courses/' + courseId + '/kb')
  for (const kb of kbs) {
    kb.docs = await api('/api/kb/' + kb.id + '/documents')
  }
  c.kbs = kbs
  c.kbCount = kbs.length
  c.docCount = kbs.reduce((n, kb) => n + kb.docs.length, 0)
  c.chunkCount = kbs.reduce((n, kb) => n + kb.docs.reduce((s, d) => s + (d.chunkCount || 0), 0), 0)
}

const createKb = async (courseId) => {
  await api('/api/courses/' + courseId + '/kb', { method: 'POST', body: { name: newKbName.value || '新知识库' } })
  newKbName.value = ''
  await refreshKbs(courseId)
}

const toggleUpload = (kbId) => {
  uploadingKb.value = uploadingKb.value === kbId ? null : kbId
  docName.value = ''
  docContent.value = ''
  pickedFile.value = null
}

const BINARY_EXT = ['.pdf', '.docx']

const pickFile = (e) => {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  docName.value = f.name
  pickedFile.value = f
  const lower = f.name.toLowerCase()
  if (BINARY_EXT.some((x) => lower.endsWith(x))) {
    // 二进制文件不能按文本读（会破坏字节导致后端解析乱码），交由后端解析，清空预览框
    docContent.value = ''
  } else {
    const reader = new FileReader()
    reader.onload = () => {
      docContent.value = String(reader.result || '')
    }
    reader.readAsText(f)
  }
  e.target.value = ''
}

const clearPicked = () => {
  pickedFile.value = null
  docContent.value = ''
}

const doUpload = async (courseId, kbId) => {
  const name = docName.value.trim() || '未命名文档'
  const fd = new FormData()
  if (pickedFile.value) {
    // 直接上传原始文件字节，由 ai-service 解析并切块
    fd.append('file', pickedFile.value, pickedFile.value.name)
  } else {
    if (!docContent.value.trim()) {
      error.value = '请选择文件或粘贴文档内容'
      return
    }
    fd.append('file', new File([docContent.value], name, { type: 'text/plain' }))
  }
  await api('/api/kb/' + kbId + '/documents/upload', { method: 'POST', body: fd })
  uploadingKb.value = null
  await refreshKbs(courseId)
  showHint('文档已入库，AI 速览正在后台生成，稍后打开预览即可查看')
}

const deleteDoc = async (kbId, docId) => {
  await api('/api/kb/' + kbId + '/documents/' + docId, { method: 'DELETE' })
  const c = courses.value.find((x) => x.kbs && x.kbs.some((k) => k.id === kbId))
  if (c) await refreshKbs(c.id)
}

const askDeleteKb = (courseId, kbId, name) => {
  confirmKb.value = { courseId, kbId, name }
}

const doDeleteKb = async () => {
  const { courseId, kbId } = confirmKb.value
  confirmKb.value = null
  try {
    await api('/api/kb/' + kbId, { method: 'DELETE' })
    // 原位更新，不整体替换 courses，避免重渲染闪烁
    await refreshKbs(courseId)
    showHint('知识库已删除')
  } catch (e) {
    showHint(e.message)
  }
}

/* ===== 知识图谱：从知识库资料抽取概念与关系，ECharts 力导向图展示 ===== */
const KG_TYPE_COLORS = { 概念: '#4e7cf6', 术语: '#9a6bf0', 方法: '#18a058', 原理: '#e08c1a', 工具: '#d9534f' }
const graphModal = ref(null) // { kb, data, rebuilding, err }
const kgRef = ref(null)
let kgChart = null

const fmtKgTime = (s) => (s ? String(s).slice(0, 16).replace('T', ' ') : '')

const openGraph = async (kb) => {
  graphModal.value = { kb, data: null, rebuilding: false, err: '' }
  window.addEventListener('resize', kgResize)
  try {
    graphModal.value.data = await api('/api/kb/' + kb.id + '/graph')
  } catch (e) {
    graphModal.value.err = e.message
  }
  nextTick(() => renderGraph())
}

const renderGraph = () => {
  const g = graphModal.value && graphModal.value.data
  if (!kgRef.value || !g || !g.nodes || !g.nodes.length) return
  if (kgChart) kgChart.dispose()
  kgChart = echarts.init(kgRef.value)
  const dark = document.documentElement.classList.contains('dark')
  // 度数决定节点大小：连接越多（越核心）越大
  const degree = {}
  g.edges.forEach((e) => {
    degree[e.source] = (degree[e.source] || 0) + 1
    degree[e.target] = (degree[e.target] || 0) + 1
  })
  const types = [...new Set(g.nodes.map((n) => n.type || '概念'))]
  kgChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      confine: true,
      formatter: (p) => p.dataType === 'edge'
        ? p.data.source + ' —' + p.data.value + '→ ' + p.data.target
        : p.data.name + '（' + (g.nodes.find((n) => n.name === p.data.name) || {}).type + '）'
    },
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      data: g.nodes.map((n) => ({
        name: n.name,
        category: Math.max(types.indexOf(n.type || '概念'), 0),
        symbolSize: Math.min(18 + (degree[n.name] || 0) * 5, 52),
        itemStyle: { color: KG_TYPE_COLORS[n.type || '概念'] || '#4e7cf6' }
      })),
      links: g.edges.map((e) => ({ source: e.source, target: e.target, value: e.relation })),
      categories: types.map((t) => ({ name: t })),
      force: { repulsion: 260, edgeLength: [60, 140], gravity: 0.08 },
      lineStyle: { color: dark ? '#3a4150' : '#d4d9e3', curveness: 0.15 },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 7,
      label: { show: true, color: dark ? '#dfe3ea' : '#333', fontSize: 12 },
      edgeLabel: {
        show: false, formatter: '{c}', fontSize: 11,
        color: dark ? '#9aa3b2' : '#666', backgroundColor: dark ? '#1b1f27' : '#fff'
      },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } }
    }]
  })
  // 点击节点 → 带上概念名与它在图中的直接邻居跳转答疑，自动绑定该知识库并发起提问
  kgChart.on('click', (p) => {
    if (p.dataType !== 'node' || !graphModal.value) return
    const name = p.data.name
    const kb = graphModal.value.kb
    const rel = [...new Set(g.edges
      .filter((e) => e.source === name || e.target === name)
      .map((e) => (e.source === name ? e.target : e.source)))].slice(0, 4)
    closeGraph()
    router.push({ path: '/chat', query: { courseId: kb.courseId, kbId: kb.id, ask: name, rel: rel.join(',') } })
  })
}

const rebuildGraph = async () => {
  const m = graphModal.value
  if (!m || m.rebuilding) return
  m.rebuilding = true
  m.err = ''
  try {
    m.data = await api('/api/kb/' + m.kb.id + '/graph/rebuild', { method: 'POST' })
    nextTick(() => renderGraph())
  } catch (e) {
    m.err = e.message
  } finally {
    m.rebuilding = false
  }
}

const kgResize = () => { if (kgChart) kgChart.resize() }

const closeGraph = () => {
  graphModal.value = null
  window.removeEventListener('resize', kgResize)
  if (kgChart) { kgChart.dispose(); kgChart = null }
}
</script>

<style scoped>
/* 知识库详情用宽版弹窗：全局 .modal-box 只有 400px，装不下上传框和预览 */
.modal-box.wide {
  width: min(760px, 94vw);
  max-height: 86vh;
  overflow-y: auto;
  padding: 20px 22px;
}
.detail-hd { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.detail-hd > div { flex: 1; }
.detail-hd .btn { flex: 0 0 auto; }
.detail-title { font-size: 16px; margin: 0 0 4px; }
.detail-sub { color: var(--muted); font-size: 12px; }
.muted { color: var(--muted); }
.small { font-size: 12px; }
.doc-preview {
  margin-top: 8px; padding: 12px; border: 1px solid var(--border); border-radius: 10px;
  background: var(--soft);
}
.pv-text {
  white-space: pre-wrap; line-height: 1.8; color: var(--text);
  max-height: 320px; overflow: auto;
  font-family: Consolas, "Microsoft YaHei", monospace; font-size: 13px;
  background: #faf9f7; border-radius: 8px; padding: 10px 12px;
}
.pv-hit { background: #fff3b8; border-radius: 3px; padding: 0 1px; }
.pv-hit.on { background: #ffd33d; outline: 2px solid var(--primary); }
/* AI 速览卡片 */
.ov-card {
  background: var(--card); border: 1px solid var(--border); border-radius: 10px;
  padding: 12px 14px; margin-bottom: 10px;
}
.ov-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.ov-head b { font-size: 13px; color: var(--primary); }
.ov-summary { font-size: 13px; line-height: 1.7; font-weight: 600; }
.ov-sec { font-size: 12px; font-weight: 700; color: var(--muted); margin: 10px 0 4px; }
.ov-list { margin: 0; padding-left: 18px; }
.ov-list li { font-size: 13px; line-height: 1.75; color: var(--text); }
@media (max-width: 900px) { .pk-grid { grid-template-columns: minmax(0, 1fr); } }

html.dark .pv-text { background: var(--soft); }
html.dark .pv-hit { background: rgba(210,153,34,.4); }

/* 知识图谱弹窗 */
.kg-box { display: flex; flex-direction: column; }
.kg-ops { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.kg-legend { margin-left: auto; display: flex; gap: 12px; font-size: 12px; color: var(--muted); }
.kg-legend i { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 4px; }
.kg-err { color: #d9534f; font-size: 13px; margin-bottom: 8px; }
.kg-wrap { position: relative; }
.kg-canvas { height: 60vh; min-height: 380px; border-radius: 10px; background: var(--soft); }
.kg-empty {
  position: absolute; inset: 0; z-index: 2; pointer-events: none;
  display: flex; align-items: center; justify-content: center;
  color: var(--muted); font-size: 14px;
}
html.dark .kg-canvas { background: #14161c; }
</style>