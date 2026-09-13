<template>
  <div>
    <div class="page-title">题库管理</div>
    <div class="page-sub">查看、录入、编辑课程题目；AI 生成的题目自动入库，可在此维护</div>

    <div v-if="error" class="err">{{ error }}</div>

    <div class="card">
      <div class="row" style="justify-content:space-between">
        <div style="display:flex;gap:16px;align-items:flex-end;flex-wrap:wrap">
          <div>
            <span class="label">课程</span>
            <select v-model="courseId">
              <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
            </select>
          </div>
          <div>
            <span class="label">类型</span>
            <select v-model="fType">
              <option value="">全部</option>
              <option v-for="t in TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <div>
            <span class="label">难度</span>
            <select v-model="fDiff">
              <option value="">全部</option>
              <option v-for="d in DIFFS" :key="d" :value="d">{{ d }}</option>
            </select>
          </div>
          <div>
            <span class="label">关键词</span>
            <input v-model="keyword" type="text" placeholder="题干 / 知识点" @keyup.enter="search" />
          </div>
          <div class="btns">
            <button class="btn ghost" @click="search">搜索</button>
          </div>
        </div>
        <div class="btns">
          <button class="btn" :disabled="!courseId" @click="openEdit(null)">+ 录入题目</button>
        </div>
      </div>
    </div>

    <div class="card">
      <table v-if="records.length">
        <thead><tr><th>时间</th><th>类型</th><th>难度</th><th>知识点</th><th>来源</th><th>题干</th><th></th></tr></thead>
        <tbody>
          <tr v-for="q in records" :key="q.id">
            <td class="nowrap">{{ fmtTime(q.createdAt) }}</td>
            <td><span class="tag">{{ q.type }}</span></td>
            <td>{{ q.difficulty || '-' }}</td>
            <td>{{ q.kpName || '-' }}</td>
            <td><span class="tag" :class="q.source === 'AI' ? 'ok' : ''">{{ sourceName(q.source) }}</span></td>
            <td class="stem" :title="q.stem">{{ q.stem }}</td>
            <td class="nowrap">
              <a class="link" @click="openEdit(q)">编辑</a>
              <a class="link danger" @click="confirmDel = q">删除</a>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty">本课程暂无符合条件的题目</div>
      <div v-if="total" class="pager">
        <button class="btn ghost small" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
        <span class="muted small">第 {{ page }} / {{ pages }} 页 · 共 {{ total }} 题</span>
        <button class="btn ghost small" :disabled="page >= pages" @click="load(page + 1)">下一页</button>
      </div>
    </div>

    <!-- 录入 / 编辑弹窗 -->
    <div v-if="editing" class="modal-mask" @click.self="editing = null">
      <div class="modal-box" style="max-width:560px">
        <h3>{{ editing.id ? '编辑题目' : '录入题目' }}</h3>
        <div class="row" style="margin-bottom:10px">
          <div>
            <span class="label">类型</span>
            <select v-model="form.type">
              <option v-for="t in TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <div>
            <span class="label">难度</span>
            <select v-model="form.difficulty">
              <option v-for="d in DIFFS" :key="d" :value="d">{{ d }}</option>
            </select>
          </div>
          <div style="flex:1">
            <span class="label">知识点</span>
            <input v-model="form.kpName" type="text" placeholder="如：HashMap / 多线程" />
          </div>
        </div>
        <span class="label">题干</span>
        <textarea v-model="form.stem" rows="3" placeholder="题目内容"></textarea>

        <template v-if="form.type === '单选' || form.type === '多选'">
          <div class="label" style="margin-top:10px">选项</div>
          <div v-for="(o, i) in formOptions" :key="i" class="row" style="margin-bottom:6px;gap:8px">
            <input v-model="o.k" type="text" style="width:56px;text-align:center" />
            <input v-model="o.v" type="text" style="flex:1" :placeholder="'选项 ' + o.k" />
            <button class="btn ghost small" :disabled="formOptions.length <= 2" @click="formOptions.splice(i, 1)">删</button>
          </div>
          <button class="btn ghost small" @click="addOption">+ 加选项</button>
        </template>

        <span class="label" style="margin-top:10px">{{ form.type === '判断' ? '答案' : form.type === '问答' ? '参考答案' : '正确答案' }}</span>
        <select v-if="form.type === '判断'" v-model="form.answer">
          <option value="正确">正确</option>
          <option value="错误">错误</option>
        </select>
        <textarea v-else v-model="form.answer" :rows="form.type === '问答' ? 3 : 1"
                  :placeholder="form.type === '单选' ? '如：A' : form.type === '多选' ? '如：ABD' : '参考答案内容'"></textarea>

        <span class="label" style="margin-top:10px">解析</span>
        <textarea v-model="form.analysis" rows="3" placeholder="解题思路（可选）"></textarea>

        <div class="modal-ops">
          <button class="btn ghost small" @click="editing = null">取消</button>
          <button class="btn small" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="confirmDel" class="modal-mask" @click.self="confirmDel = null">
      <div class="modal-box">
        <h3>删除题目</h3>
        <p>确定删除这道「{{ confirmDel.type }}」题吗？若已被练习/考试记录或收藏引用将无法删除。</p>
        <div class="modal-ops">
          <button class="btn ghost small" @click="confirmDel = null">取消</button>
          <button class="btn danger small" :disabled="deleting" @click="doDelete">{{ deleting ? '删除中…' : '确认删除' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { api, getCourses } from '../api'
import { fmtTime, parseOptions } from '../utils'

defineOptions({ name: 'QuestionBankView' })

const TYPES = ['单选', '多选', '判断', '问答']
const DIFFS = ['基础', '进阶', '综合']
const PAGE_SIZE = 10

const courses = ref([])
const courseId = ref(null)
const fType = ref('')
const fDiff = ref('')
const keyword = ref('')
const records = ref([])
const page = ref(1)
const total = ref(0)
const error = ref('')

const editing = ref(null) // null | {id?} 触发表单弹窗
const form = ref({})
const formOptions = ref([])
const saving = ref(false)
const confirmDel = ref(null)
const deleting = ref(false)

const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

onMounted(async () => {
  courses.value = await getCourses()
  if (courses.value.length) courseId.value = courses.value[0].id
  load(1)
})

const load = async (p = 1) => {
  if (!courseId.value) return
  error.value = ''
  try {
    const params = new URLSearchParams({
      courseId: courseId.value, page: p, size: PAGE_SIZE,
      type: fType.value, difficulty: fDiff.value, keyword: keyword.value.trim()
    })
    const d = await api('/api/questions?' + params)
    records.value = d.records || []
    total.value = d.total || 0
    page.value = p
  } catch (e) {
    error.value = e.message
  }
}

const search = () => load(1)

watch([fType, fDiff], () => load(1))
watch(courseId, () => { fType.value = ''; fDiff.value = ''; keyword.value = ''; load(1) })

const sourceName = (s) => ({ AI: 'AI 生成', SEED: '内置', 手动: '手动' }[s] || s || '-')

const openEdit = (q) => {
  error.value = ''
  editing.value = q ? { ...q } : {}
  form.value = {
    type: q?.type || '单选',
    difficulty: q?.difficulty || '进阶',
    kpName: q?.kpName || '',
    stem: q?.stem || '',
    answer: q?.answer || '',
    analysis: q?.analysis || ''
  }
  formOptions.value = q && parseOptions(q.options).length
    ? parseOptions(q.options).map((o) => ({ ...o }))
    : [{ k: 'A', v: '' }, { k: 'B', v: '' }, { k: 'C', v: '' }, { k: 'D', v: '' }]
}

const addOption = () => {
  const used = formOptions.value.map((o) => o.k)
  const k = 'ABCDEFGH'.split('').find((c) => !used.includes(c)) || String(formOptions.value.length + 1)
  formOptions.value.push({ k, v: '' })
}

const save = async () => {
  const f = form.value
  if (!f.stem.trim()) { error.value = '题干不能为空'; return }
  let options = null
  if (f.type === '单选' || f.type === '多选') {
    const rows = formOptions.value.filter((o) => o.k && o.v.trim())
    if (rows.length < 2) { error.value = '请至少填写两个选项'; return }
    options = JSON.stringify(rows)
    const keys = rows.map((o) => o.k)
    if (f.type === '单选' && !keys.includes(f.answer)) { error.value = '答案必须是已有选项之一'; return }
    if (f.type === '多选' && ![...(f.answer || '')].every((c) => keys.includes(c))) { error.value = '答案必须由已有选项字母组成'; return }
  }
  saving.value = true
  error.value = ''
  const wasEdit = !!editing.value.id
  try {
    const body = { ...f, courseId: courseId.value, options }
    if (wasEdit) {
      await api('/api/questions/' + editing.value.id, { method: 'PUT', body })
    } else {
      await api('/api/questions', { method: 'POST', body })
    }
    editing.value = null
    load(wasEdit ? page.value : 1)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

const doDelete = async () => {
  deleting.value = true
  error.value = ''
  try {
    await api('/api/questions/' + confirmDel.value.id, { method: 'DELETE' })
    confirmDel.value = null
    load(records.value.length === 1 && page.value > 1 ? page.value - 1 : page.value)
  } catch (e) {
    error.value = e.message
    confirmDel.value = null
  } finally {
    deleting.value = false
  }
}
</script>

<style scoped>
.stem { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nowrap { white-space: nowrap; }
.pager { margin-top: 8px; display: flex; align-items: center; gap: 10px; }
.link.danger { color: #b91c1c; margin-left: 8px; }
</style>
