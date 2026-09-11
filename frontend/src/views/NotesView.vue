<template>
  <div>
    <div class="page-title">学习笔记</div>
    <div class="page-sub">按课程记录学习与练习心得，可关联知识点便于回顾</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <div class="row" style="gap:12px;align-items:center">
        <select v-model="courseId" @change="load">
          <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <button class="btn ghost small" @click="toggleEditor(null)">{{ editing ? '收起编辑' : '新建笔记' }}</button>
      </div>

      <div v-if="editing" class="editor">
        <div class="row" style="gap:12px">
          <div><span class="label">标题</span><input v-model="form.title" maxlength="100" placeholder="一句话概括" /></div>
          <div><span class="label">知识点（可选）</span><input v-model="form.kpName" placeholder="如：HashMap原理" /></div>
        </div>
        <textarea v-model="form.content" rows="6" placeholder="记录学习/练习中的收获、易错点、思路…" style="margin-top:10px"></textarea>
        <div style="margin-top:10px">
          <button class="btn small" :disabled="saving" @click="save">{{ saving ? '保存中…' : (form.id ? '保存修改' : '保存笔记') }}</button>
        </div>
      </div>
    </div>

    <div v-if="!notes.length && !editing" class="card"><div class="empty">暂无笔记</div></div>

    <div v-for="n in notes" :key="n.id" class="card note-card">
      <div class="row" style="justify-content:space-between">
        <h3 style="margin:0">{{ n.title }}
          <span v-if="n.kpName" class="tag">{{ n.kpName }}</span>
        </h3>
        <div class="btns">
          <button class="btn ghost small" @click="toggleEditor(n)">编辑</button>
          <button class="btn ghost small" @click="del(n)">删除</button>
        </div>
      </div>
      <div class="note-content">{{ n.content }}</div>
      <div class="muted small">更新于 {{ fmtTime(n.updatedAt) }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api, getCourses } from '../api'
import { fmtTime } from '../utils'

defineOptions({ name: 'NotesView' })

const courses = ref([])
const courseId = ref(null)
const notes = ref([])
const editing = ref(false)
const form = ref({ title: '', kpName: '', content: '' })
const editId = ref(null)
const saving = ref(false)
const error = ref('')

const load = async () => {
  notes.value = await api('/api/notes' + (courseId.value ? '?courseId=' + courseId.value : ''))
}

const toggleEditor = (n) => {
  if (n) {
    editId.value = n.id
    form.value = { title: n.title, kpName: n.kpName || '', content: n.content }
  } else {
    editId.value = null
    form.value = { title: '', kpName: '', content: '' }
  }
  editing.value = !editing.value
  if (!editing.value) editId.value = null
}

const save = async () => {
  saving.value = true
  error.value = ''
  try {
    const body = { ...form.value, courseId: courseId.value }
    if (editId.value) await api('/api/notes/' + editId.value, { method: 'PUT', body })
    else await api('/api/notes', { method: 'POST', body })
    editing.value = false
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

const del = async (n) => {
  if (!confirm('删除笔记「' + n.title + '」？')) return
  try {
    await api('/api/notes/' + n.id, { method: 'DELETE' })
    await load()
  } catch (e) {
    error.value = e.message
  }
}

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) courseId.value = courses.value[0].id
  await load()
})
</script>

<style scoped>
.editor { margin-top: 12px; padding: 14px; border: 1px solid var(--border); border-radius: 10px; }
.note-content { white-space: pre-wrap; line-height: 1.8; color: #334155; margin: 10px 0; }
.note-card { transition: box-shadow .2s ease; }
.note-card:hover { box-shadow: 0 4px 16px rgba(0,0,0,.06); }
</style>
