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
  background: #eef2ff; color: var(--accent-deep);
  font-size: 12px; font-weight: 700; font-family: "SF Mono", Consolas, monospace;
}
.mi-meta { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.mi-stem { font-size: 15px; font-weight: 600; line-height: 1.7; margin-bottom: 12px; }

/* 选项：红绿对比 */
.mi-opts { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.opt-row {
  display: flex; align-items: center; gap: 9px;
  padding: 8px 12px; border-radius: 10px;
  border: 1px solid #e8ebf3; background: #fbfcfe;
  font-size: 13.5px; color: #565b6e; line-height: 1.6;
}
.opt-row i {
  flex-shrink: 0; width: 20px; height: 20px; border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #fff; border: 1px solid #dfe3ee;
  font-style: normal; font-size: 11px; font-weight: 700; color: #4338ca;
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
  border: 1px solid #e8ebf3; background: #fcfdff; margin-bottom: 10px;
}
.cmp-row { display: flex; gap: 12px; padding: 9px 14px; font-size: 13.5px; line-height: 1.7; }
.cmp-row + .cmp-row { border-top: 1px solid #eef1f7; }
.cmp-lab { flex-shrink: 0; width: 62px; font-size: 12px; font-weight: 700; color: var(--muted); padding-top: 2px; }
.cmp-val { flex: 1; min-width: 0; word-break: break-word; }
.cmp-val.bad { color: #b3423a; font-weight: 600; }
.cmp-val.ok { color: #1f7a41; font-weight: 700; }

/* 解析折叠 */
.mi-analysis summary {
  display: inline-flex; align-items: center; gap: 5px;
  cursor: pointer; user-select: none; list-style: none;
  font-size: 12.5px; font-weight: 600; color: var(--accent-deep);
  padding: 4px 10px; border-radius: 8px; background: #f5f7fc;
  border: 1px solid #e5e9f2; transition: background .18s ease;
}
.mi-analysis summary::-webkit-details-marker { display: none; }
.mi-analysis summary:hover { background: #eceffb; }
.mi-chev { transition: transform .2s ease; }
.mi-analysis[open] summary { margin-bottom: 8px; }
.mi-analysis[open] .mi-chev { transform: rotate(180deg); }
.mi-ans-txt {
  font-size: 13.5px; line-height: 1.75; color: #565b6e;
  background: #fbfcfe; border: 1px solid #e5e9f2; border-radius: 10px; padding: 10px 14px;
}

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
