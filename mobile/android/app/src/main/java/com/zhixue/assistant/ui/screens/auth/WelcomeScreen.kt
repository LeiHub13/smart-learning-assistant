package com.zhixue.assistant.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhixue.assistant.ui.components.LocalAppContainer
import com.zhixue.assistant.ui.components.LocalNavController
import com.zhixue.assistant.ui.components.PrimaryButton
import com.zhixue.assistant.ui.components.SectionTitle
import com.zhixue.assistant.ui.components.cardShape
import com.zhixue.assistant.ui.nav.Routes
import com.zhixue.assistant.ui.theme.LocalZhixuePalette

/** 功能介绍：与 Web 登录页左侧面板同样的四件事，全部是客户端已具备的能力，不写空承诺。 */
private val welcomeFeatures = listOf(
    WelcomeFeature("AI 答疑", "课程知识库 RAG 检索，回答标注引用来源"),
    WelcomeFeature("讲义与题目生成", "一键流式生成讲义，题目自动入库"),
    WelcomeFeature("练习与批改", "客观题自动判分，主观题 AI 点评"),
    WelcomeFeature("学情分析", "掌握度追踪、错题归集与个性化建议"),
)

private data class WelcomeFeature(val name: String, val desc: String)

/**
 * 未登录介绍页：不调任何接口（未登录时也没有可用的鉴权接口），
 * 只说明产品能做什么，并提供进入登录/注册的入口。
 */
@Composable
fun WelcomeScreen() {
    val container = LocalAppContainer.current
    val navController = LocalNavController.current
    val palette = LocalZhixuePalette.current

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .safeDrawingPadding()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(palette.text),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "智",
                        color = palette.canvas,
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text("智学助手", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }

            Column {
                Text(
                    "用大模型重新定义智能学习",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "基于 RAG 检索增强生成，覆盖答疑、生成、批改与学情的完整学习闭环。" +
                        "所有学习数据都存在你自己配置的后端里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.muted,
                )
            }

            SectionTitle("你将得到")
            welcomeFeatures.forEachIndexed { index, feature ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(palette.card)
                        .border(1.dp, palette.border, cardShape)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.accentSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            color = palette.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(feature.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            feature.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            SectionTitle("开始使用")
            Text(
                "登录或注册后即可查看课程、答疑与练习记录。" +
                    "如果后端部署在你自己的服务器或本机，可在登录页展开「服务器设置」修改地址。",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = "登录 / 注册",
                onClick = { navController.navigate(Routes.LOGIN) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "当前后端：${container.server.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
