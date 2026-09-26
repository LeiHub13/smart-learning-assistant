<template>
  <div>
    <div class="page-title">错题本</div>
    <div class="page-sub">自动归集练习与考试中答错的题，重练答对后自动出本</div>

    <div v-if="error" class="err">{{ error }}</div>

    <!-- 工具栏 -->
    <div class="card mb-toolbar">
      <div class="mb-filter">
        <span class="mb-filter-lab">课程</span>
        <select v-model="courseId">
          <option :value="null">全部课程</option>
          <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </div>
      <div v-if="total" class="mb-stat">
        <b>{{ total }}</b>
        <span>道错题待攻克</span>
      </div>
      <button class="btn accent" :disabled="!total" @click="goRetrain">
        <AppIcon name="practice" :size="14" /> 开始重练
      </button>
    </div>

    <!-- 错题卡片 -->
    <div v-for="(m, i) in records" :key="m.question.id" class="card mi-card">
      <div class="mi-head">
        <span class="mi-no">{{ pageOffset + i + 1 }}</span>
        <span class="tag">{{ m.question.type }}</span>
        <span v-if="m.question.kpName" class="tag">{{ m.question.kpName }}</span>
        <span v-if="m.question.difficulty" class="tag">{{ m.question.difficulty }}</span>
        <span class="mi-meta">
          <span class="tag bad">错 {{ m.wrongCount }} 次</span>
          <span class="muted small">{{ fmtTime(m.lastWrongAt) }}</span>
        </span>
      </div>

      <div class="mi-stem">{{ m.question.stem }}</div>

      <!-- 选项：正确项标绿、选错项标红 -->
      <div v-if="m.question.options" class="mi-opts">
        <div v-for="o in parseOptions(m.question.options)" :key="o.k" class="opt-row" :class="markOption(m, o.k)">
          <i>{{ o.k }}</i>
          <span class="opt-v">{{ o.v }}</span>
          <span v-if="markOption(m, o.k) === 'correct'" class="opt-flag ok">正确答案</span>
          <span v-else-if="markOption(m, o.k) === 'wrong'" class="opt-flag bad">你的答案</span>
        </div>
      </div>

      <!-- 答案对比（问答/填空，或选项之外的补充说明） -->
      <div class="mi-cmp">
        <div v-if="m.lastWrongAnswer" class="cmp-row">
          <span class="cmp-lab">你的答案</span>
          <span class="cmp-val bad">{{ m.lastWrongAnswer }}</span>
        </div>
        <div class="cmp-row">
          <span class="cmp-lab">正确答案</span>
          <span class="cmp-val ok">{{ m.question.answer || '—' }}</span>
        </div>
      </div>

      <details v-if="m.question.analysis" class="mi-analysis">
        <summary>
          <AppIcon name="chev" :size="12" class="mi-chev" />
          查看解析
        </summary>
        <div class="mi-ans-txt">{{ m.question.analysis }}</div>
      </details>

      <div class="mi-foot">
        <button class="btn ghost small" :disabled="generatingId === m.question.id" @click="openVariants(m)">
          <AppIcon name="sparkles" :size="13" /> 举一反三
        </button>
        <span class="muted small">AI 围绕该题考点出几道变式，检验是否真正掌握</span>
      </div>
    </div>

    <!-- 空态 -->
    <div v-if="!records.length && !error" class="card">
      <div class="mb-empty">
        <span class="mb-empty-ico"><AppIcon name="target" :size="26" /></span>
        <div class="mb-empty-t">{{ courseId ? '该课程暂无错题' : '暂无错题' }}</div>
        <div class="mb-empty-d">{{ courseId ? '这门课掌握得不错，保持下去！' : '完成一次练习后，答错的题会自动归集到这里' }}</div>
      </div>
    </div>

    <!-- 分页 -->
    <div v-if="total" class="pager">
      <button class="btn ghost small" :disabled="page <= 1" @click="load(page - 1)">上一页</button>
      <span class="muted small">第 {{ page }} / {{ pages }} 页 · 共 {{ total }} 题</span>
      <button class="btn ghost small" :disabled="page >= pages" @click="load(page + 1)">下一页</button>
    </div>

    <!-- 举一反三弹窗：AI 出题中 → 作答 → 批改结果 -->
    <div v-if="va" class="modal-mask" @click.self="closeVariants">
      <div class="modal-box variants-modal">
        <!-- 出题中 -->
        <template v-if="va.phase === 'loading'">
          <h3>举一反三 · AI 出题中</h3>
          <div class="va-loading"><i></i>正在围绕「{{ va.kp || '该题考点' }}」生成变式题…</div>
          <div class="va-orig">
            <div class="va-orig-lab">原题</div>
            <div class="va-orig-stem">{{ va.stem }}</div>
          </div>
        </template>

        <!-- 作答 -->
        <template v-else-if="va.phase === 'answer'">
          <div class="va-head">
            <h3>举一反三 · 变式 {{ va.paper.length }} 题</h3>
            <button class="btn ghost small" @click="closeVariants">关闭</button>
          </div>
          <details class="va-orig">
            <summary>原题</summary>
            <div class="va-orig-stem">{{ va.stem }}</div>
          </details>
          <div v-for="(q, i) in va.paper" :key="q.id" class="q">
            <div class="head">
              <span class="type">{{ i + 1 }}. 【{{ q.type }}】{{ q.stem }}</span>
              <span class="kp">{{ q.kpName }}</span>
            </div>
            <label v-for="o in parseOptions(q.options)" :key="o.k" class="opt" :class="{ on: vaIsSel(q, o.k) }">
              <input :type="q.type === '多选' ? 'checkbox' : 'radio'"
                     :name="'va' + q.id" :value="o.k"
                     :checked="vaIsSel(q, o.k)"
                     @change="vaToggle(q, o.k, q.type)" />
              {{ o.k }}. {{ o.v }}
            </label>
            <textarea v-if="q.type === '问答'" v-model="va.answers[q.id]" placeholder="请输入你的答案…" style="margin-top:8px"></textarea>
          </div>
          <div v-if="va.error" class="err">{{ va.error }}</div>
          <div class="va-ops">
            <button class="btn ghost small" @click="closeVariants">取消</button>
            <button class="btn accent" :disabled="va.submitting" @click="submitVariants">
              {{ va.submitting ? '批改中…' : '提交并 AI 批改' }}
            </button>
          </div>
        </template>

        <!-- 结果 -->
        <template v-else>
          <div class="va-head">
            <h3>变式练习结果</h3>
            <button class="btn ghost small" @click="closeVariants">关闭</button>
          </div>
          <div class="row va-stats">
            <div class="stat" style="flex:1"><div class="num">{{ va.report.score }} / {{ va.report.totalScore }}</div><div class="lab">总分</div></div>
            <div class="stat" style="flex:1"><div class="num">{{ vaOk }} / {{ va.report.items.length }}</div><div class="lab">答对</div></div>
          </div>
          <div v-for="(it, i) in va.report.items" :key="it.pq.id" class="q" :class="it.pq.correct ? 'pass' : 'fail'">
            <div class="head">
              <span class="type">{{ i + 1 }}. 【{{ it.q.type }}】{{ it.q.stem }}</span>
              <span class="tag" :class="it.pq.correct ? 'ok' : 'bad'">{{ it.pq.correct ? '正确 +' + it.pq.score : '错误' }}</span>
            </div>
            <div class="ans"><b>你的答案：</b>{{ it.pq.userAnswer || '（未作答）' }}<br />
              <b>参考答案：</b>{{ it.q.answer }}<br /><b>解析：</b>{{ it.q.analysis }}</div>
            <div v-if="it.pq.review" class="ans ai" style="margin-top:6px"><b>AI 点评：</b>{{ it.pq.review }}</div>
          </div>
          <div class="va-ops">
            <button class="btn accent" @click="closeVariants">完成</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, getCourses } from '../api'
import { fmtTime, parseOptions } from '../utils'
import AppIcon from '../components/AppIcon.vue'

defineOptions({ name: 'MistakeBookView' })

const PAGE_SIZE = 10
const router = useRouter()

const courses = ref([])
const courseId = ref(null)
const records = ref([])
const page = ref(1)
const total = ref(0)
const error = ref('')

const pages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const pageOffset = computed(() => (page.value - 1) * PAGE_SIZE)

onMounted(async () => {
  courses.value = await getCourses()
  load(1)
})

const load = async (p = 1) => {
  error.value = ''
  try {
    const q = new URLSearchParams({ page: p, size: PAGE_SIZE })
    if (courseId.value) q.set('courseId', courseId.value)
    const d = await api('/api/mistakes?' + q)
    records.value = d.records || []
    total.value = d.total || 0
    page.value = p
  } catch (e) {
    error.value = e.message
  }
}

watch(courseId, () => load(1))

/** 选项标记：正确项=correct，用户选错的项=wrong，其余中性 */
const markOption = (m, k) => {
  const c = (m.question.answer || '').toUpperCase()
  const u = (m.lastWrongAnswer || '').toUpperCase()
  if (c.includes(k.toUpperCase()) && u.includes(k.toUpperCase())) return 'correct'
  if (c.includes(k.toUpperCase())) return 'correct'
  if (u.includes(k.toUpperCase())) return 'wrong'
  return ''
}

const goRetrain = () => {
  const q = courseId.value ? '?mistake=1&courseId=' + courseId.value : '?mistake=1'
  router.push('/practice' + q)
}

/* ===== 举一反三：变式出题 → 弹窗作答 → 复用练习判分 ===== */
const generatingId = ref(null)
const va = ref(null)

const vaOk = computed(() =>
  va.value && va.value.report ? va.value.report.items.filter((i) => i.pq.correct).length : 0)

const openVariants = async (m) => {
  if (!m.question.courseId) {
    error.value = '该题未关联课程，无法生成变式'
    return
  }
  generatingId.value = m.question.id
  va.value = {
    phase: 'loading', kp: m.question.kpName, stem: m.question.stem,
    courseId: m.question.courseId, paper: [], answers: {}, report: null,
    submitting: false, error: ''
  }
  try {
    va.value.paper = await api('/api/mistakes/' + m.question.id + '/variants', {
      method: 'POST', body: { count: 2 }
    })
    va.value.phase = 'answer'
  } catch (e) {
    va.value = null
    error.value = e.message
  } finally {
    generatingId.value = null
  }
}

const vaIsSel = (q, k) => {
  const v = va.value.answers[q.id] || ''
  if (q.type === '多选') return v.split('').includes(k)
  return v === k
}

const vaToggle = (q, k, type) => {
  if (type === '多选') {
    let v = (va.value.answers[q.id] || '').split('').filter((c) => c !== k).join('')
    if (!v.includes(k)) v = (v + k).split('').sort().join('')
    va.value.answers[q.id] = v
  } else {
    va.value.answers[q.id] = k
  }
}

const submitVariants = async () => {
  const v = va.value
  v.submitting = true
  v.error = ''
  try {
    const items = v.paper.map((q) => ({ questionId: q.id, answer: v.answers[q.id] || '' }))
    const p = await api('/api/practice/submit', { method: 'POST', body: { courseId: v.courseId, items } })
    v.report = await api('/api/practice/' + p.id)
    v.phase = 'result'
  } catch (e) {
    v.error = e.message
  } finally {
    v.submitting = false
  }
}

const closeVariants = () => { va.value = null }
</script>

<style scoped>
/* ===== 工具栏 ===== */
.mb-toolbar {
  display: flex; align-items: flex-end; gap: 18px; flex-wrap: wrap;
  padding: 16px 22px; margin-bottom: 16px;
}
.mb-filter { display: flex; flex-direction: column; gap: 5px; min-width: 220px; }
.mb-filter-lab { font-size: 13px; color: var(--muted); }
.mb-filter select { max-width: 280px; }
.mb-stat { display: flex; align-items: baseline; gap: 6px; margin-left: auto; padding-bottom: 3px; }
.mb-stat b { font-size: 24px; font-weight: 800; letter-spacing: -.02em; color: var(--accent-deep); }
.mb-stat span { font-size: 12px; color: var(--muted); }

/* ===== 错题卡片 ===== */
.mi-card { padding: 18px 20px; }
.mi-head { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.mi-no {
  width: 22px; height: 22px; border-radius: 7px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: #ddf4ff; color: var(--accent-deep);
  font-size: 12px; font-weight: 700; font-family: "SF Mono", Consolas, monospace;
}
.mi-meta { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.mi-stem { font-size: 15px; font-weight: 600; line-height: 1.7; margin-bottom: 12px; }

/* 选项：红绿对比 */
.mi-opts { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.opt-row {
  display: flex; align-items: center; gap: 9px;
  padding: 8px 12px; border-radius: 10px;
  border: 1px solid var(--border); background: #fff;
  font-size: 13.5px; color: #57606a; line-height: 1.6;
}
.opt-row i {
  flex-shrink: 0; width: 20px; height: 20px; border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #fff; border: 1px solid #d1d9e0;
  font-style: normal; font-size: 11px; font-weight: 700; color: var(--primary);
}
.opt-v { flex: 1; min-width: 0; }
.opt-flag {
  flex-shrink: 0; font-size: 11px; font-weight: 700;
  padding: 2px 8px; border-radius: 20px;
}
.opt-row.correct { background: #f2faf4; border-color: #c3e6cd; color: #1f5c34; }
.opt-row.correct i { background: #e2f4e7; border-color: #b7dfc2; color: #1f7a41; }
.opt-flag.ok { background: #dff3e5; color: #1f7a41; }
.opt-row.wrong { background: #fdf3f2; border-color: #f2d2ce; color: #8c3a33; }
.opt-row.wrong i { background: #fbe6e4; border-color: #f0c8c3; color: #b3423a; }
.opt-flag.bad { background: #f9e2e0; color: #b3423a; }

/* 答案对比行 */
.mi-cmp {
  border-radius: 10px; overflow: hidden;
  border: 1px solid var(--border); background: #fff; margin-bottom: 10px;
}
.cmp-row { display: flex; gap: 12px; padding: 9px 14px; font-size: 13.5px; line-height: 1.7; }
.cmp-row + .cmp-row { border-top: 1px solid #eaeef2; }
.cmp-lab { flex-shrink: 0; width: 62px; font-size: 12px; font-weight: 700; color: var(--muted); padding-top: 2px; }
.cmp-val { flex: 1; min-width: 0; word-break: break-word; }
.cmp-val.bad { color: #b3423a; font-weight: 600; }
.cmp-val.ok { color: #1f7a41; font-weight: 700; }

/* 解析折叠 */
.mi-analysis summary {
  display: inline-flex; align-items: center; gap: 5px;
  cursor: pointer; user-select: none; list-style: none;
  font-size: 12.5px; font-weight: 600; color: var(--accent-deep);
  padding: 4px 10px; border-radius: 8px; background: var(--soft);
  border: 1px solid var(--border); transition: background .18s ease;
}
.mi-analysis summary::-webkit-details-marker { display: none; }
.mi-analysis summary:hover { background: #f3f4f6; }
.mi-chev { transition: transform .2s ease; }
.mi-analysis[open] summary { margin-bottom: 8px; }
.mi-analysis[open] .mi-chev { transform: rotate(180deg); }
.mi-ans-txt {
  font-size: 13.5px; line-height: 1.75; color: #565b6e;
  background: var(--soft); border: 1px solid var(--border); border-radius: 10px; padding: 10px 14px;
}

/* ===== 举一反三 ===== */
.mi-foot {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-top: 6px; padding-top: 12px; border-top: 1px dashed var(--border);
}
.variants-modal { width: min(720px, 94vw); max-height: 84vh; overflow-y: auto; }
.va-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }
.va-head h3 { margin: 0; }
.va-orig {
  border: 1px solid var(--border); background: var(--soft);
  border-radius: 8px; padding: 10px 14px; margin-bottom: 14px;
}
.va-orig summary {
  cursor: pointer; user-select: none; list-style: none;
  font-size: 12px; font-weight: 700; color: var(--muted);
}
.va-orig summary::-webkit-details-marker { display: none; }
.va-orig[open] summary { margin-bottom: 6px; }
.va-orig-lab { font-size: 12px; font-weight: 700; color: var(--muted); margin-bottom: 4px; }
.va-orig-stem { font-size: 13px; line-height: 1.7; color: #57606a; }
.va-loading { display: flex; align-items: center; gap: 8px; color: var(--muted); padding: 8px 0 14px; }
.va-loading i {
  width: 15px; height: 15px; flex-shrink: 0;
  border: 2px solid #d1d9e0; border-top-color: var(--primary);
  border-radius: 50%; animation: bootsp .8s linear infinite;
}
.va-stats { margin-bottom: 12px; }
.va-ops { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }

/* ===== 分页 / 空态 ===== */
.pager { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 6px 0 12px; }
.mb-empty { text-align: center; padding: 46px 0 40px; }
.mb-empty-ico {
  display: inline-flex; align-items: center; justify-content: center;
  width: 54px; height: 54px; border-radius: 18px; margin-bottom: 14px;
  background: #f7f4ee; color: #b9a893;
}
.mb-empty-t { font-size: 15px; font-weight: 700; margin-bottom: 5px; }
.mb-empty-d { font-size: 13px; color: var(--muted); }

@media (max-width: 768px) {
  .mb-toolbar { align-items: stretch; }
  .mb-filter { min-width: 0; }
  .mb-filter select { max-width: none; }
  .mb-stat { margin-left: 0; }
  .mi-meta { margin-left: 0; width: 100%; }
}
</style>
