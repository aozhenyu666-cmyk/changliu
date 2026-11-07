package com.changliu.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.ui.Openers
import com.changliu.app.ui.ShellSnap
import com.changliu.app.ui.components.BigFigure
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.EntryRow
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.ScreenTitle
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.ThinProgress
import com.changliu.app.ui.components.Tone

@Composable
fun DeptScreen(snap: ShellSnap, onOpenDepts: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle("四部", "总控 · 严管、总控时间、今日部署")

        ChangliuCard {
            Kicker(if (snap.zkStrict) "现在管着" else "现在的口径", if (snap.zkStrict) Tone.Seal else Tone.Ok)
            Text(snap.zkHeadline, style = MaterialTheme.typography.headlineMedium)
            if (snap.zkReason.isNotBlank()) {
                Text(
                    snap.zkReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("今日娱乐", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BigFigure(if (snap.zkQuotaMin > 0) "${snap.zkPlayMin}/${snap.zkQuotaMin}" else "${snap.zkPlayMin} 分钟")
                }
                Column(Modifier.weight(1f)) {
                    Text("今日部署", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BigFigure(if (snap.zkGatesTotal > 0) "${snap.zkGatesDone}/${snap.zkGatesTotal}" else "—")
                }
            }
            if (snap.zkQuotaMin > 0) {
                ThinProgress(
                    snap.zkPlayMin.toFloat() / snap.zkQuotaMin,
                    if (snap.zkPlayMin >= snap.zkQuotaMin) Tone.Bad else Tone.Seal,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onOpenDepts, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
                Text("打开四部总览")
            }
        }

        SectionHeader("总控时间")
        ChangliuCard(tone = if (snap.hqActive) Tone.Seal else Tone.Neutral) {
            Kicker(if (snap.hqActive) "进行中" else "未开始", if (snap.hqActive) Tone.Seal else Tone.Neutral)
            if (snap.hqActive) {
                Text("还剩 ${snap.hqLeftMin} 分钟", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "这段时间白名单以外的应用会被弹回总控。提前结束要走紧急按钮。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("到点自动进入", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "也可以手动开始：清库、静收、谋、筹、行、封，一段一段来。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { Openers.zk(ctx, "hq") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) { Text(if (snap.hqActive) "进入总控时间" else "手动开始") }
        }

        SectionHeader("入口")
        ChangliuCard {
            EntryRow("四部总览", "行为 / 学习 / 身体 / 财务", onClick = onOpenDepts, showDivider = true)
            EntryRow("今日部署", "今天排了什么、交没交", onClick = { Openers.zk(ctx, "today") }, showDivider = true)
            EntryRow("参谋", "把今天交给总控排一遍", onClick = { Openers.zk(ctx, "chat") }, showDivider = true)
            EntryRow("计划", "在推的事与下一步", onClick = { Openers.zk(ctx, "plan") })
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "以上进入总控的完整界面；返回键回到长流。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
