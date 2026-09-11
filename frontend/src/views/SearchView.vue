<template>
  <div>
    <div class="page-title">全局搜索</div>
    <div class="page-sub">一次查询覆盖：题目 · 笔记 · 考试 · 知识库文档片段</div>

    <div class="card">
      <div class="row" style="gap:10px">
        <input v-model="q" placeholder="输入关键词，回车搜索" @keyup.enter="doSearch" style="flex:1" />
        <button class="btn small" :disabled="loading" @click="doSearch">{{ loading ? '搜索中…' : '搜索' }}</button>
      </div>
    </div>

    <div v-if="error" class="err">{{ error }}</div>

    <template v-if="result">
      <div class="card">
        <h3>题目（{{ result.questions.length }}）</h3>
        <div v-if="!result.questions.length" class="empty">无匹配</div>
        <div v-for="it in result.questions" :key="'q' + it.id" class="hit">
          <span class="tag">【{{ it.type }}】</span>{{ it.stem }}
          <span class="muted small" v-if="it.kpName"> · {{ it.kpName }}</span>
          <a class="link" @click="$router.push('/practice')">去练习 →</a>
        </div>
      </div>

      <div class="card">
        <h3>学习笔记（{{ result.notes.length }}）</h3>
        <div v-if="!result.notes.length" class="empty">无匹配</div>
        <div v-for="it in result.notes" :key="'n' + it.id" class="hit">
          <b>{{ it.title }}</b>
          <span v-if="it.kpName" class="tag">{{ it.kpName }}</span>
          <div class="muted small">{{ it.content }}</div>
          <a class="link" @click="$router.push('/notes')">去笔记 →</a>
        </div>
      </div>

      <div class="card">
        <h3>知识库文档片段（{{ result.docs.length }}）</h3>
        <div v-if="!result.docs.length" class="empty">无匹配</div>
        <div v-for="(it, i) in result.docs" :key="'d' + i" class="hit">
          <b>📄 {{ it.docName }}</b>
          <span class="muted small">（{{ it.kbName }}）</span>
          <div class="doc-text">{{ it.content }}</div>
        </div>
      </div>

      <div class="card">
        <h3>在线考试（{{ result.exams.length }}）</h3>
        <div v-if="!result.exams.length" class="empty">无匹配</div>
        <div v-for="it in result.exams" :key="'e' + it.id" class="hit">
          <b>{{ it.title }}</b>
          <span class="muted small"> · 总分 {{ it.totalScore }}</span>
          <a class="link" @click="$router.push('/exam')">去考试 →</a>
        </div>
      </div>
    </template>

    <div v-if="!result && !loading" class="card"><div class="empty">输入关键词开始搜索</div></div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'

defineOptions({ name: 'SearchView' })

const route = useRoute()
const router = useRouter()
const q = ref(route.query.q || '')
const result = ref(null)
const loading = ref(false)
const error = ref('')

const doSearch = async () => {
  const kw = q.value.trim()
  if (!kw) return
  loading.value = true
  error.value = ''
  try {
    result.value = await api('/api/search?q=' + encodeURIComponent(kw))
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

if (q.value) doSearch()
</script>

<style scoped>
.hit { padding: 9px 0; border-bottom: 1px solid var(--border); line-height: 1.7; }
.hit:last-child { border-bottom: none; }
.hit .link { margin-left: 8px; font-size: 12px; cursor: pointer; }
.doc-text {
  white-space: pre-wrap; font-size: 13px; color: #334155; background: var(--soft);
  border-radius: 8px; padding: 8px 10px; margin-top: 4px;
}
</style>
