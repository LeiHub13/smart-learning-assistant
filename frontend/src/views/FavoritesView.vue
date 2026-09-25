<template>
  <div>
    <div class="page-title">收藏夹</div>
    <div class="page-sub">收藏的好题集中回顾，支持一键重练</div>

    <div v-if="error" class="err">{{ error }}</div>

    <!-- 工具栏 -->
    <div class="card fi-toolbar">
      <div class="fi-filter">
        <span class="fi-filter-lab">课程</span>
        <select v-model="courseId">
          <option :value="null">全部课程</option>
          <option v-for="c in courses" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
      </div>
      <div v-if="favList.length" class="fi-stat">
        <b>{{ favList.length }}</b>
        <span>道收藏题目</span>
      </div>
      <button class="btn accent" :disabled="!favList.length" @click="goRetrain">
        <AppIcon name="practice" :size="14" /> 一键重练
      </button>
    </div>

    <!-- 收藏卡片 -->
    <div v-for="(q, i) in favList" :key="q.questionId" class="card fi-card">
      <div class="fi-head">
        <span class="fi-no">{{ i + 1 }}</span>
        <span class="tag">{{ q.type }}</span>
        <span v-if="q.kpName" class="tag">{{ q.kpName }}</span>
        <span v-if="q.difficulty" class="tag">{{ q.difficulty }}</span>
        <span v-if="!courseId" class="tag">{{ q.courseName }}</span>
        <button class="btn ghost small fi-unfav" :disabled="removing === q.questionId" @click="remove(q)">
          <AppIcon name="star" :size="12" />
          {{ removing === q.questionId ? '移除中…' : '取消收藏' }}
        </button>
      </div>

      <div class="fi-stem">{{ q.stem }}</div>

      <!-- 选项：正确答案标绿、选错项标红（有作答记录时） -->
      <div v-if="q.options" class="fi-opts">
        <div v-for="o in parseOptions(q.options)" :key="o.k" class="opt-row" :class="markOption(q, o.k)">
          <i>{{ o.k }}</i>
          <span class="opt-v">{{ o.v }}</span>
          <span v-if="markOption(q, o.k) === 'correct'" class="opt-flag ok">正确答案</span>
          <span v-else-if="markOption(q, o.k) === 'wrong'" class="opt-flag bad">你的答案</span>
        </div>
      </div>

      <!-- 答案对比：做过则有「你的答案」，否则只显示正确答案 -->
      <div class="fi-cmp">
        <div v-if="hasRecord(q)" class="cmp-row">
          <span class="cmp-lab">你的答案</span>
          <span class="cmp-val" :class="q.lastCorrect ? 'ok' : 'bad'">{{ q.lastAnswer || '（未作答）' }}</span>
        </div>
        <div class="cmp-row">
          <span class="cmp-lab">正确答案</span>
          <span class="cmp-val ok">{{ q.answer || '—' }}</span>
        </div>
      </div>

      <details v-if="q.analysis" class="fi-analysis">
        <summary>
          <AppIcon name="chev" :size="12" class="fi-chev" />
          查看解析
        </summary>
        <div class="fi-ans-txt">{{ q.analysis }}</div>
      </details>
    </div>

    <!-- 空态 -->
    <div v-if="!favList.length && !error" class="card">
      <div class="fi-empty">
        <span class="fi-empty-ico"><AppIcon name="star" :size="24" /></span>
        <div class="fi-empty-t">{{ courseId ? '该课程暂无收藏' : '暂无收藏' }}</div>
        <div class="fi-empty-d">{{ courseId ? '切换课程或去练习页收藏好题' : '在练习/考试报告页点击收藏，好题会集中到这里' }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, getCourses } from '../api'
import { parseOptions } from '../utils'
import AppIcon from '../components/AppIcon.vue'

defineOptions({ name: 'FavoritesView' })

const router = useRouter()
const courses = ref([])
const courseId = ref(null)
const favList = ref([])
const removing = ref(null)
const error = ref('')

onMounted(async () => {
  courses.value = await getCourses()
  await load()
})

const load = async () => {
  error.value = ''
  try {
    favList.value = await api('/api/favorites' + (courseId.value ? '?courseId=' + courseId.value : ''))
  } catch (e) {
    error.value = e.message
  }
}

watch(courseId, load)

const remove = async (q) => {
  removing.value = q.questionId
  error.value = ''
  try {
    await api('/api/favorites/toggle', { method: 'POST', body: { questionId: q.questionId } })
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    removing.value = null
  }
}

const goRetrain = () => {
  const q = courseId.value ? '?favorite=1&courseId=' + courseId.value : '?favorite=1'
  router.push('/practice' + q)
}

/** 该题是否有作答记录（收藏的题可能从未做过） */
const hasRecord = (q) => q.lastAnswer !== null && q.lastAnswer !== undefined

/** 选项标记：正确项=correct，作答中选错的项=wrong */
const markOption = (q, k) => {
  const kk = String(k).toUpperCase()
  if ((q.answer || '').toUpperCase().includes(kk)) return 'correct'
  if (hasRecord(q) && (q.lastAnswer || '').toUpperCase().includes(kk)) return 'wrong'
  return ''
}
</script>

<style scoped>
/* ===== 工具栏 ===== */
.fi-toolbar {
  display: flex; align-items: flex-end; gap: 18px; flex-wrap: wrap;
  padding: 16px 22px; margin-bottom: 16px;
}
.fi-filter { display: flex; flex-direction: column; gap: 5px; min-width: 220px; }
.fi-filter-lab { font-size: 13px; color: var(--muted); }
.fi-filter select { max-width: 280px; }
.fi-stat { display: flex; align-items: baseline; gap: 6px; margin-left: auto; padding-bottom: 3px; }
.fi-stat b { font-size: 24px; font-weight: 800; letter-spacing: -.02em; color: var(--accent-deep); }
.fi-stat span { font-size: 12px; color: var(--muted); }

/* ===== 收藏卡片 ===== */
.fi-card { padding: 18px 20px; }
.fi-head { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap; }
.fi-no {
  width: 22px; height: 22px; border-radius: 7px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: #eef2ff; color: var(--accent-deep);
  font-size: 12px; font-weight: 700; font-family: "SF Mono", Consolas, monospace;
}
.fi-unfav { margin-left: auto; }
.fi-stem { font-size: 15px; font-weight: 600; line-height: 1.7; margin-bottom: 12px; }

/* 选项：红绿对比 */
.fi-opts { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
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
.opt-flag { flex-shrink: 0; font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 20px; }
.opt-row.correct { background: #f2faf4; border-color: #c3e6cd; color: #1f5c34; }
.opt-row.correct i { background: #e2f4e7; border-color: #b7dfc2; color: #1f7a41; }
.opt-flag.ok { background: #dff3e5; color: #1f7a41; }
.opt-row.wrong { background: #fdf3f2; border-color: #f2d2ce; color: #8c3a33; }
.opt-row.wrong i { background: #fbe6e4; border-color: #f0c8c3; color: #b3423a; }
.opt-flag.bad { background: #f9e2e0; color: #b3423a; }

/* 答案对比 */
.fi-cmp { border-radius: 10px; overflow: hidden; border: 1px solid #e8ebf3; background: #fcfdff; margin-bottom: 10px; }
.cmp-row { display: flex; gap: 12px; padding: 9px 14px; font-size: 13.5px; line-height: 1.7; }
.cmp-row + .cmp-row { border-top: 1px solid #eef1f7; }
.cmp-lab { flex-shrink: 0; width: 62px; font-size: 12px; font-weight: 700; color: var(--muted); padding-top: 2px; }
.cmp-val { flex: 1; min-width: 0; word-break: break-word; }
.cmp-val.bad { color: #b3423a; font-weight: 600; }
.cmp-val.ok { color: #1f7a41; font-weight: 700; }

/* 解析折叠 */
.fi-analysis summary {
  display: inline-flex; align-items: center; gap: 5px;
  cursor: pointer; user-select: none; list-style: none;
  font-size: 12.5px; font-weight: 600; color: var(--accent-deep);
  padding: 4px 10px; border-radius: 8px; background: #f5f7fc;
  border: 1px solid #e5e9f2; transition: background .18s ease;
}
.fi-analysis summary::-webkit-details-marker { display: none; }
.fi-analysis summary:hover { background: #eceffb; }
.fi-chev { transition: transform .2s ease; }
.fi-analysis[open] summary { margin-bottom: 8px; }
.fi-analysis[open] .fi-chev { transform: rotate(180deg); }
.fi-ans-txt {
  font-size: 13.5px; line-height: 1.75; color: #5b5b57;
  background: #fbfcfe; border: 1px solid #e5e9f2; border-radius: 10px; padding: 10px 14px;
}

/* 空态 */
.fi-empty { text-align: center; padding: 46px 0 40px; }
.fi-empty-ico {
  display: inline-flex; align-items: center; justify-content: center;
  width: 54px; height: 54px; border-radius: 18px; margin-bottom: 14px;
  background: #f7f4ee; color: #b9a893;
}
.fi-empty-t { font-size: 15px; font-weight: 700; margin-bottom: 5px; }
.fi-empty-d { font-size: 13px; color: var(--muted); }

@media (max-width: 768px) {
  .fi-toolbar { align-items: stretch; }
  .fi-filter { min-width: 0; }
  .fi-filter select { max-width: none; }
  .fi-stat { margin-left: 0; }
}
</style>
