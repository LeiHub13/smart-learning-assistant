<template>
  <div class="billing-page">
    <div class="page-title">AI 次数与套餐</div>
    <div class="page-sub">查看剩余次数、购买套餐和订单进度</div>
    <div v-if="error" class="err">{{ error }}</div>
    <div v-if="notice" class="card notice">{{ notice }}</div>
    <div v-if="loading" class="card">正在加载…</div>
    <template v-else-if="overview">
      <section class="card balance-card">
        <div>
          <span class="balance-label">我的剩余 AI 次数</span>
          <div class="balance-number">{{ overview.balance }} <small>次</small></div>
          <p v-if="overview.enabled">智能答疑、生成讲义或练习题每次使用 1 次；生成失败会退回次数。</p>
          <p v-else>当前未启用次数限制，可以正常使用 AI 功能。</p>
        </div>
        <span class="balance-pill">{{ overview.enabled ? '次数包' : '自由体验中' }}</span>
      </section>

      <div class="section-heading">
        <div><h3>选择次数包</h3><p>次数到账后可在答疑和内容生成中使用</p></div>
      </div>
      <div class="package-grid">
        <article v-for="p in overview.packages" :key="p.code" class="card package">
          <h3>{{ p.name }}</h3>
          <div class="package-count">{{ p.credits }} <span>次 AI 使用</span></div>
          <div class="package-price"><span>¥</span>{{ price(p.priceFen) }}</div>
          <button class="btn" :disabled="!overview.orderingAvailable || busy" @click="createOrder(p)">
            {{ overview.orderingAvailable ? '选择套餐' : '暂未开放购买' }}
          </button>
        </article>
      </div>

      <section v-if="overview.paymentInstructions" class="card payment-card">
        <h3>付款说明</h3>
        <p class="instructions">{{ overview.paymentInstructions }}</p>
        <a v-if="overview.paymentUrl" class="payment-link" :href="overview.paymentUrl" target="_blank" rel="noopener noreferrer">前往付款平台 ↗</a>
        <p class="muted">付款后在下方订单中提交平台交易单号，核对后次数将到账。</p>
      </section>

      <section class="orders-section">
        <div class="section-heading"><div><h3>我的订单</h3><p>提交付款信息后，可在这里查看处理进度</p></div>
          <button class="btn ghost small" :disabled="busy" @click="load">刷新</button>
        </div>
        <div v-if="!orders.length" class="card empty-orders">暂无订单</div>
        <article v-for="order in orders" :key="order.orderNo" class="card order-card">
          <div class="order-head">
            <div><b>{{ order.packageName }}</b><span class="order-meta">{{ order.credits }} 次 · ¥{{ price(order.priceFen) }}</span></div>
            <span class="order-status" :class="order.status.toLowerCase()">{{ statusLabel(order.status) }}</span>
          </div>
          <div class="order-id">订单号：{{ order.orderNo }}</div>
          <div class="order-date">创建时间：{{ formatTime(order.createdAt) }}</div>
          <div v-if="order.status === 'WAITING' || order.status === 'REVIEW'" class="submit-area">
            <label>付款平台
              <select v-model="forms[order.orderNo].channel">
                <option value="">请选择</option><option value="AFDIAN">爱发电</option>
                <option value="WECHAT">微信支付</option><option value="ALIPAY">支付宝</option><option value="OTHER">其他平台</option>
              </select>
            </label>
            <label class="reference-label">平台交易单号
              <input v-model.trim="forms[order.orderNo].reference" maxlength="120" placeholder="填写付款平台的交易单号" />
            </label>
            <div class="order-actions">
              <button class="btn small" :disabled="busy" @click="submit(order)">{{ order.status === 'REVIEW' ? '更新付款信息' : '提交付款信息' }}</button>
              <button v-if="order.status === 'WAITING'" class="btn ghost small" :disabled="busy" @click="cancel(order)">取消订单</button>
            </div>
          </div>
          <p v-if="order.status === 'REVIEW'" class="muted">正在等待人工核对，确认后次数自动到账。</p>
          <p v-else-if="order.status === 'PAID'" class="muted">次数已到账 · {{ formatTime(order.paidAt) }}</p>
        </article>
      </section>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'

defineOptions({ name: 'BillingView' })
const overview = ref(null)
const orders = ref([])
const forms = ref({})
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const price = (fen) => (Number(fen) / 100).toFixed(2)
const formatTime = (value) => value ? String(value).replace('T', ' ').slice(0, 19) : '—'
const statusLabel = (status) => ({ WAITING: '待付款', REVIEW: '待核对', PAID: '已到账', CANCELLED: '已取消' }[status] || status)

const load = async () => {
  error.value = ''
  try {
    const [summary, list] = await Promise.all([api('/api/billing/overview'), api('/api/billing/orders')])
    overview.value = summary
    orders.value = list || []
    for (const order of orders.value) {
      forms.value[order.orderNo] = {
        channel: order.submittedChannel || '', reference: order.submittedReference || ''
      }
    }
    window.dispatchEvent(new Event('billing-changed'))
  } catch (e) { error.value = e.message } finally { loading.value = false }
}

const run = async (action, message) => {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try { await action(); await load(); notice.value = message } catch (e) { error.value = e.message } finally { busy.value = false }
}
const createOrder = (p) => run(() => api('/api/billing/orders', { method: 'POST', body: { packageCode: p.code } }), '订单已创建，请按照付款说明完成付款并提交交易单号。')
const submit = (order) => {
  const form = forms.value[order.orderNo]
  if (!form.channel || !form.reference) { error.value = '请选择付款平台并填写交易单号'; return }
  run(() => api(`/api/billing/orders/${order.orderNo}/submit`, { method: 'POST', body: form }), '付款信息已提交，等待人工核对。')
}
const cancel = (order) => run(() => api(`/api/billing/orders/${order.orderNo}/cancel`, { method: 'POST' }), '订单已取消。')
onMounted(load)
</script>

<style scoped>
.billing-page { max-width: 1080px; margin: 0 auto; padding-bottom: 36px; }
.notice { border-color: var(--primary); color: var(--primary); }
.balance-card { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; padding: 24px; }
.balance-label, .order-meta, .order-id, .order-date, .section-heading p, .balance-card p, .muted { color: var(--muted); font-size: 13px; }
.balance-number { font-size: 40px; font-weight: 750; margin: 6px 0; }
.balance-number small { font-size: 15px; font-weight: 500; }
.balance-card p, .section-heading p, .muted { margin: 6px 0 0; line-height: 1.6; }
.balance-pill, .order-status { border-radius: 20px; background: rgba(9,105,218,.09); color: var(--primary); padding: 5px 11px; font-size: 12px; white-space: nowrap; }
.section-heading { display: flex; justify-content: space-between; align-items: end; gap: 12px; margin: 26px 0 12px; }
.section-heading h3, .package h3 { margin: 0; }
.package-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; }
.package { display: flex; flex-direction: column; gap: 14px; margin: 0; padding: 22px; }
.package-count { font-size: 26px; font-weight: 700; }
.package-count span { font-size: 13px; font-weight: 400; color: var(--muted); }
.package-price { font-size: 27px; font-weight: 750; }
.package-price span { font-size: 16px; margin-right: 2px; }
.package .btn { align-self: start; }
.payment-card { margin-top: 18px; }
.instructions { white-space: pre-wrap; line-height: 1.7; }
.payment-link { color: var(--primary); font-weight: 600; }
.order-card { padding: 18px 20px; }
.order-head { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
.order-meta { margin-left: 10px; }
.order-id { margin-top: 10px; overflow-wrap: anywhere; }
.order-date { margin-top: 4px; }
.order-status.paid { color: #16804a; background: rgba(22,128,74,.1); }
.order-status.cancelled { color: var(--muted); background: rgba(128,128,128,.1); }
.submit-area { display: flex; flex-wrap: wrap; align-items: end; gap: 10px; margin-top: 16px; }
.submit-area label { display: flex; flex-direction: column; gap: 6px; color: var(--muted); font-size: 12px; }
.submit-area select, .submit-area input { min-height: 34px; }
.reference-label { flex: 1; min-width: 200px; }
.order-actions { display: flex; gap: 8px; }
.empty-orders { color: var(--muted); text-align: center; padding: 24px; }
@media (max-width: 600px) { .balance-card { padding: 18px; } .order-head { align-items: flex-start; } .order-meta { display: block; margin: 5px 0 0; } }
</style>
