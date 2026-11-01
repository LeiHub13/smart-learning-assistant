package com.zhixue.assistant.ui.screens.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.zhixue.assistant.AppContainer
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.core.userMessage
import com.zhixue.assistant.data.model.BillingOrder
import com.zhixue.assistant.data.model.BillingOverview
import com.zhixue.assistant.data.model.BillingPackage
import com.zhixue.assistant.data.repo.PAYMENT_CHANNELS
import kotlinx.coroutines.launch

/** 订单行的付款信息草稿（人工核对模式，提交只代表「已付款待核对」）。 */
data class PaymentForm(val channel: String = "", val reference: String = "")

/**
 * 次数包页：额度总览 + 套餐 + 订单列表 + 提交/取消订单。
 * 价格、套餐、是否开放购买、付款说明全部来自 `GET /api/billing/overview`，客户端不写死任何数字。
 */
class BillingViewModel(private val container: AppContainer) : ViewModel() {

    var overview by mutableStateOf<UiState<BillingOverview>>(UiState.Loading)
        private set

    var orders by mutableStateOf<UiState<List<BillingOrder>>>(UiState.Loading)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var toast by mutableStateOf<String?>(null)
        private set

    /** 付款平台清单，与后端 submit 的白名单一致。 */
    val channels: List<Pair<String, String>> = PAYMENT_CHANNELS

    val forms: SnapshotStateMap<String, PaymentForm> = mutableStateMapOf()

    fun formOf(orderNo: String): PaymentForm = forms[orderNo] ?: PaymentForm()

    fun setChannel(orderNo: String, channel: String) {
        forms[orderNo] = formOf(orderNo).copy(channel = channel)
    }

    fun setReference(orderNo: String, reference: String) {
        forms[orderNo] = formOf(orderNo).copy(reference = reference.take(MAX_REFERENCE_LENGTH))
    }

    fun clearMessages() {
        error = null
        notice = null
    }

    fun consumeToast() {
        toast = null
    }

    fun load(overwriteForms: Boolean = true) {
        viewModelScope.launch { loadOnce(overwriteForms) }
    }

    private suspend fun loadOnce(overwriteForms: Boolean) {
        overview = UiState.Loading
        orders = UiState.Loading
        val overviewResult = runCatching { container.billing.overview() }
        val ordersResult = runCatching { container.billing.orders() }
        overview = overviewResult.fold({ UiState.Ready(it) }, { UiState.Error(it.userMessage()) })
        ordersResult.fold(
            onSuccess = { list ->
                orders = UiState.Ready(list)
                syncForms(list, overwriteForms)
            },
            onFailure = { err -> orders = UiState.Error(err.userMessage()) },
        )
    }

    /** 回填已提交的渠道与单号；手动刷新时以服务端为准，操作后的静默刷新保留用户正在输入的内容。 */
    private fun syncForms(orders: List<BillingOrder>, overwrite: Boolean) {
        orders.forEach { order ->
            val fromServer = PaymentForm(
                channel = order.submittedChannel.orEmpty(),
                reference = order.submittedReference.orEmpty(),
            )
            val existing = forms[order.orderNo]
            forms[order.orderNo] = when {
                existing == null || overwrite -> fromServer
                fromServer.channel.isEmpty() && fromServer.reference.isEmpty() -> existing
                else -> fromServer
            }
        }
    }

    fun createOrder(pkg: BillingPackage) {
        guard("订单已创建：请按付款说明完成付款，并在订单里提交交易单号。") {
            container.billing.createOrder(pkg.code)
        }
    }

    fun submitPayment(order: BillingOrder) {
        val form = formOf(order.orderNo)
        if (form.channel.isBlank()) {
            error = "请选择付款平台"
            return
        }
        val reference = form.reference.trim()
        if (reference.isEmpty()) {
            error = "请填写交易单号"
            return
        }
        if (reference.length > MAX_REFERENCE_LENGTH) {
            error = "请填写不超过 $MAX_REFERENCE_LENGTH 字的交易单号"
            return
        }
        guard("付款信息已提交：正在等待人工核对，确认后次数自动到账。") {
            container.billing.submit(order.orderNo, form.channel, reference)
        }
    }

    fun cancelOrder(order: BillingOrder) {
        guard("订单已取消。") {
            container.billing.cancel(order.orderNo)
        }
    }

    private fun guard(successHint: String, action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            notice = null
            val result = runCatching { action() }
            if (result.isSuccess) {
                notice = successHint
                toast = successHint
                // 次数可能因下单/核对变化，和 Web 的 billing-changed 事件等价
                container.refreshQuota()
                loadOnce(overwriteForms = false)
            } else {
                error = result.exceptionOrNull()?.userMessage().orEmpty()
            }
            busy = false
        }
    }

    companion object {
        const val MAX_REFERENCE_LENGTH = 120

        fun factory(container: AppContainer) = viewModelFactory {
            initializer { BillingViewModel(container) }
        }
    }
}
