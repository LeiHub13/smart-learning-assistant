package com.zhixue.assistant.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhixue.assistant.data.model.BillingOrder
import com.zhixue.assistant.data.model.BillingOverview
import com.zhixue.assistant.data.model.BillingPackage
import com.zhixue.assistant.core.UiState
import com.zhixue.assistant.ui.components.BannerTone
import com.zhixue.assistant.ui.components.ConfirmDialog
import com.zhixue.assistant.ui.components.EmptyView
import com.zhixue.assistant.ui.components.ErrorState
import com.zhixue.assistant.ui.components.HintBanner
import com.zhixue.assistant.ui.components.InlineLoading
import com.zhixue.assistant.ui.components.LabeledField
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalToast
import com.zhixue.assistant.ui.components.PageHeader
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SecondaryButton
import com.zhixue.assistant.ui.components.SelectField
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.StatTile
import com.zhixue.assistant.ui.components.TagBadge
import com.zhixue.assistant.ui.components.TagTone
import com.zhixue.assistant.ui.components.ZhixueCard
import com.zhixue.assistant.ui.theme.LocalZhixuePalette
import com.zhixue.assistant.util.fmtFen
import com.zhixue.assistant.util.parseDateTime
import com.zhixue.assistant.util.FileActions

/**
 * AI 次数与套餐（人工核对模式）：客户端只下单与提交付款信息，
 * 不存在任何「假支付 / 自动到账」，价格与说明全部来自后端。
 */
@Composable
fun BillingScreen() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val toast = LocalToast.current
    val vm: BillingViewModel = viewModel(factory = BillingViewModel.factory(container))

    var pendingCancel by remember { mutableStateOf<BillingOrder?>(null) }

    LaunchedEffect(Unit) { vm.load(overwriteForms = true) }
    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            toast(it)
            vm.consumeToast()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(
            title = "AI 次数与套餐",
            subtitle = "查看剩余次数、套餐与订单进度；付款由收款平台完成，本站只做人工核对。",
            actions = {
                SecondaryButton(
                    text = "刷新",
                    onClick = { vm.load(overwriteForms = true) },
                    enabled = vm.overview !is UiState.Loading && vm.orders !is UiState.Loading && !vm.busy,
                )
            },
        )

        vm.error?.let { HintBanner(it, BannerTone.Error, onDismiss = vm::clearMessages) }
        vm.notice?.let { HintBanner(it, BannerTone.Success, onDismiss = vm::clearMessages) }

        when (val state = vm.overview) {
            is UiState.Loading -> InlineLoading("正在读取额度与套餐…")

            is UiState.Error -> ErrorState(
                message = "额度读取失败：${state.message}",
                onRetry = { vm.load() },
            )

            is UiState.Ready -> OverviewSection(
                overview = state.data,
                vm = vm,
                onOpenPaymentUrl = { url ->
                    if (!FileActions.openInBrowser(context, url)) {
                        toast("系统里没有可用的浏览器打开该付款链接")
                    }
                },
            )
        }

        SectionTitle("我的订单")
        when (val state = vm.orders) {
            is UiState.Loading -> InlineLoading("正在读取订单…")

            is UiState.Error -> ErrorState(
                message = "订单读取失败：${state.message}",
                onRetry = { vm.load() },
            )

            is UiState.Ready -> {
                if (state.data.isEmpty()) {
                    EmptyView(
                        title = "暂无订单",
                        hint = "在上方选择套餐并下单后，订单会出现在这里，提交交易单号即可进入人工核对。",
                    )
                } else {
                    state.data.forEach { order ->
                        OrderCard(
                            order = order,
                            vm = vm,
                            onRequestCancel = { pendingCancel = it },
                        )
                    }
                }
            }
        }
    }

    pendingCancel?.let { order ->
        ConfirmDialog(
            title = "取消订单",
            body = "确认取消订单 ${order.orderNo}？取消后需要重新下单。",
            confirmText = "取消订单",
            dismissText = "再想想",
            danger = true,
            onConfirm = {
                pendingCancel = null
                vm.cancelOrder(order)
            },
            onDismiss = { pendingCancel = null },
        )
    }
}

@Composable
private fun OverviewSection(
    overview: BillingOverview,
    vm: BillingViewModel,
    onOpenPaymentUrl: (String) -> Unit,
) {
    val palette = LocalZhixuePalette.current

    ZhixueCard(title = "我的剩余 AI 次数") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${overview.balance}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (overview.enabled) {
                        "智能答疑、生成讲义或练习题每次使用 1 次；生成失败会退回次数。"
                    } else {
                        "当前未开启次数包：AI 功能不受次数限制，可以正常使用。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
            TagBadge(
                text = if (overview.enabled) "次数包" else "自由体验中",
                tone = if (overview.enabled) TagTone.Info else TagTone.Neutral,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(value = "${overview.balance}", label = "剩余次数", modifier = Modifier.weight(1f))
            StatTile(value = "${overview.initialCredits}", label = "初始次数", modifier = Modifier.weight(1f))
        }
    }

    if (!overview.enabled) {
        HintBanner(
            "当前未开启次数包：后端没有启用额度校验，套餐与订单功能暂时不可用。",
            BannerTone.Warn,
        )
        return
    }

    SectionTitle("选择次数包")
    if (overview.packages.isEmpty()) {
        EmptyView(title = "后端暂未配置套餐", hint = "暂时无法下单；开通后会显示在这里。")
    } else {
        overview.packages.forEach { pkg ->
            PackageCard(
                pkg = pkg,
                orderingAvailable = overview.orderingAvailable,
                busy = vm.busy,
                onOrder = { vm.createOrder(pkg) },
            )
        }
    }

    if (!overview.orderingAvailable) {
        HintBanner("暂未开放购买：收款通道或运营配置尚未就绪，下单按钮已禁用。", BannerTone.Info)
    }

    if (overview.paymentInstructions.isNotBlank()) {
        ZhixueCard(title = "付款说明") {
            Text(
                overview.paymentInstructions,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            if (overview.paymentUrl.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("前往付款平台", onClick = { onOpenPaymentUrl(overview.paymentUrl) })
                Text(
                    "将在系统浏览器中打开：${overview.paymentUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "付款后在下方订单中提交平台交易单号，核对通过后次数自动到账。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun PackageCard(
    pkg: BillingPackage,
    orderingAvailable: Boolean,
    busy: Boolean,
    onOrder: () -> Unit,
) {
    val palette = LocalZhixuePalette.current
    ZhixueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pkg.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${pkg.credits} 次 AI 使用 · ${fmtFen(pkg.priceFen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
            PrimaryButton(
                text = if (orderingAvailable) "选择套餐" else "暂未开放购买",
                onClick = onOrder,
                enabled = orderingAvailable && !busy,
            )
        }
    }
}

@Composable
private fun OrderCard(
    order: BillingOrder,
    vm: BillingViewModel,
    onRequestCancel: (BillingOrder) -> Unit,
) {
    val palette = LocalZhixuePalette.current
    val submittable = order.status == "WAITING" || order.status == "REVIEW"
    val form = vm.formOf(order.orderNo)

    ZhixueCard(title = order.packageName, subtitle = "${order.credits} 次 · ${fmtFen(order.priceFen)}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("订单号：${order.orderNo}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "创建时间：${orderTime(order.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
                if (order.status == "PAID") {
                    Text(
                        "次数已到账：${orderTime(order.paidAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.success,
                    )
                }
            }
            TagBadge(text = order.statusLabel, tone = statusTone(order.status))
        }

        if (!order.submittedChannel.isNullOrBlank() || !order.submittedReference.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "已提交：${channelLabel(order.submittedChannel, vm.channels)} · 交易单号 ${order.submittedReference ?: "未填写"}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }

        if (submittable) {
            Spacer(Modifier.height(12.dp))
            SelectField(
                label = "付款平台",
                options = vm.channels,
                selected = form.channel.ifBlank { null },
                onSelect = { vm.setChannel(order.orderNo, it) },
                emptyLabel = "请选择",
            )
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "平台交易单号",
                value = form.reference,
                onValueChange = { vm.setReference(order.orderNo, it) },
                placeholder = "填写付款平台的交易单号",
                maxLength = BillingViewModel.MAX_REFERENCE_LENGTH,
                helper = "最多 ${BillingViewModel.MAX_REFERENCE_LENGTH} 字",
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    text = if (order.status == "REVIEW") "更新付款信息" else "提交付款信息",
                    onClick = { vm.submitPayment(order) },
                    loading = vm.busy,
                )
                if (order.status == "WAITING") {
                    SecondaryButton(
                        text = "取消订单",
                        onClick = { onRequestCancel(order) },
                        enabled = !vm.busy,
                        danger = true,
                    )
                }
            }
        }

        if (order.status == "REVIEW") {
            Spacer(Modifier.height(8.dp))
            HintBanner("正在等待人工核对，确认后次数自动到账。", BannerTone.Info)
        }
    }
}

private fun statusTone(status: String): TagTone = when (status) {
    "WAITING" -> TagTone.Warn
    "REVIEW" -> TagTone.Info
    "PAID" -> TagTone.Success
    else -> TagTone.Neutral
}

private fun channelLabel(channel: String?, channels: List<Pair<String, String>>): String =
    channel?.takeIf { it.isNotBlank() }?.let { key ->
        channels.firstOrNull { it.first == key }?.second ?: key
    } ?: "未选择"

/** 订单时间要显示到日，Web 用 `2026-11-01 14:32:30`，这里给到分钟。 */
private fun orderTime(raw: String?): String = parseDateTime(raw)?.let {
    "%04d-%02d-%02d %02d:%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute)
} ?: "—"
