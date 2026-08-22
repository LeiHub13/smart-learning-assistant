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
        <div class="btns">
          <button class="btn" @click="createCourse">创建</button>
        </div>
      </div>
    </div>

    <div v-for="c in courses" :key="c.id" class="card">
      <div class="row" style="justify-content:space-between">
        <div style="flex:1">
          <h3 style="margin-bottom:4px">{{ c.name }}
            <span v-if="c.enrolled" class="tag ok">已加入</span>
            <span class="tag">创建者：{{ c.ownerName }}</span>
          </h3>
          <div style="color:var(--muted)">{{ c.description }}</div>
          <div style="margin-top:6px;font-size:12px;color:var(--muted)">知识库 {{ c.kbCount }} 个 · 题库 {{ c.questionCount }} 题</div>
        </div>
        <button v-if="!c.enrolled" class="btn small" @click="enroll(c)">加入</button>
      </div>

      <template v-if="expanded === c.id">
        <div style="margin-top:14px">
          <div class="row" style="margin-bottom:10px">
            <input v-model="newKbName" type="text" placeholder="新知识库名称" />
            <div class="btns">
            <button class="btn ghost small" @click="createKb(c.id)">创建知识库</button>
          </div>
          </div>
          <div v-for="kb in c.kbs" :key="kb.id" class="qi">
            <div class="hd">
              <b>{{ kb.name }}</b>
              <span class="tag">知识库</span>
              <button class="btn ghost small" style="margin-left:auto" @click="toggleUpload(kb.id)">上传文档</button>
            </div>
            <div v-for="d in kb.docs" :key="d.id" class="ans" style="margin:4px 0">
              <div class="row" style="justify-content:space-between">
                <span>📄 {{ d.fileName }} · {{ d.chunkCount }} 个片段</span>
                <button class="btn danger small" @click="deleteDoc(kb.id, d.id)">删除</button>
              </div>
            </div>
            <div v-if="uploadingKb === kb.id" style="margin-top:8px">
              <div class="row">
                <input v-model="docName" type="text" placeholder="文档名（如：HashMap原理.md）" />
                <div class="btns">
                <label class="btn ghost small" style="cursor:pointer">
                  📄 选择文件
                  <input type="file" accept=".txt,.md,.markdown,.pdf,.doc,.docx,.java,.json,.xml,.yml,.sql" style="display:none" @change="pickFile" />
                </label>
                <button class="btn small" @click="doUpload(c.id, kb.id)">解析入库</button>
              </div>
              </div>
              <textarea v-model="docContent" placeholder="支持 .txt/.md/.pdf/.doc/.docx；选择文件自动解析，或直接粘贴文档内容；系统自动分块向量化…"
                        style="margin-top:8px;min-height:130px"></textarea>
            </div>
          </div>
        </div>
      </template>
      <button class="btn ghost small" style="margin-top:12px" @click="toggleExpand(c)">
        {{ expanded === c.id ? '收起' : '查看知识库' }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, getCourses } from '../api'

defineOptions({ name: 'ManageView' })

const courses = ref([])
const expanded = ref(null)
const uploadingKb = ref(null)
const newName = ref('')
const newDesc = ref('')
const newKbName = ref('')
const docName = ref('')
const docContent = ref('')
const error = ref('')

onMounted(async () => {
  courses.value = await getCourses()
})

const enroll = async (c) => {
  await api('/api/courses/' + c.id + '/enroll', { method: 'POST', body: {} })
  courses.value = await getCourses(true)
}

const createCourse = async () => {
  if (!newName.value.trim()) {
    error.value = '请输入课程名称'
    return
  }
  await api('/api/courses', { method: 'POST', body: { name: newName.value.trim(), description: newDesc.value } })
  newName.value = ''
  newDesc.value = ''
  courses.value = await getCourses(true)
}

const toggleExpand = async (c) => {
  if (expanded.value === c.id) {
    expanded.value = null
    return
  }
  expanded.value = c.id
  const kbs = await api('/api/courses/' + c.id + '/kb')
  for (const kb of kbs) {
    kb.docs = await api('/api/kb/' + kb.id + '/documents')
  }
  c.kbs = kbs
}

const createKb = async (courseId) => {
  await api('/api/courses/' + courseId + '/kb', { method: 'POST', body: { name: newKbName.value || '新知识库' } })
  newKbName.value = ''
  const c = courses.value.find((x) => x.id === courseId)
  await toggleExpand(c)
  await toggleExpand(c)
}

const toggleUpload = (kbId) => {
  uploadingKb.value = uploadingKb.value === kbId ? null : kbId
  docName.value = ''
  docContent.value = ''
}

const pickFile = (e) => {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  docName.value = f.name
  const reader = new FileReader()
  reader.onload = () => {
    docContent.value = String(reader.result || '')
  }
  reader.readAsText(f)
  e.target.value = ''
}

const doUpload = async (courseId, kbId) => {
  const name = docName.value.trim() || '未命名文档'
  if (!docContent.value.trim()) {
    error.value = '请选择文件或粘贴文档内容'
    return
  }
  const fd = new FormData()
  fd.append('file', new File([docContent.value], name, { type: 'text/plain' }))
  await api('/api/kb/' + kbId + '/documents/upload', { method: 'POST', body: fd })
  uploadingKb.value = null
  const c = courses.value.find((x) => x.id === courseId)
  await toggleExpand(c)
}

const deleteDoc = async (kbId, docId) => {
  await api('/api/kb/' + kbId + '/documents/' + docId, { method: 'DELETE' })
  const c = courses.value.find((x) => x.kbs && x.kbs.some((k) => k.id === kbId))
  if (c) await toggleExpand(c)
}
</script>