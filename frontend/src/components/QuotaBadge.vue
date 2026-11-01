<template>
  <button v-if="overview?.enabled" class="quota-badge" title="答疑、生成讲义或练习题每次使用 1 次" @click="router.push('/billing')">
    AI 次数 {{ overview.balance }} · 查看套餐
  </button>
</template>

<script setup>
import { ref, onMounted, onUnmounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'

const router = useRouter()
const overview = ref(null)
const refresh = async () => {
  try { overview.value = await api('/api/billing/overview') } catch { /* 不影响学习功能 */ }
}
onMounted(() => { refresh(); window.addEventListener('billing-changed', refresh) })
onActivated(refresh)
onUnmounted(() => window.removeEventListener('billing-changed', refresh))
</script>

<style scoped>
.quota-badge { border: 1px solid var(--border); border-radius: 20px; background: var(--card); color: var(--primary); padding: 5px 10px; font-size: 12px; cursor: pointer; white-space: nowrap; }
</style>
