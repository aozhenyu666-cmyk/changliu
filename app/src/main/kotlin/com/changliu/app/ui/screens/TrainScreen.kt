package com.changliu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.ui.Openers
import com.changliu.app.ui.ShellSnap
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.ScreenTitle
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatTile
import com.changliu.app.ui.components.Tone

@Composable
fun TrainScreen(snap: ShellSnap) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle("训练", "一手 · 题库、六骰子、题卡、语音陪练")

        ChangliuCard {
            Kicker("来一手", Tone.Accent)
            Text("池子里有东西，就先做一手。", style = MaterialTheme.typography.headlineSmall)
            Text(
                "分享一篇文章、拍一页书、导入一份题库；后面的练法都从这里拿。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { Openers.yishou(ctx, "board") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) { Text("进入一手") }
        }

        SectionHeader("现在练一手", "过闸用的就是这几种")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrainTile(
                kicker = "静心",
                title = "舒尔特方格",
                hint = "30 秒聚焦",
                modifier = Modifier.weight(1f),
            ) { Openers.yishou(ctx, "schulte") }
            TrainTile(
                kicker = "找破绽",
                title = "残局",
                hint = "从原文截漏洞",
                modifier = Modifier.weight(1f),
            ) { Openers.yishou(ctx, "endgame") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrainTile(
                kicker = "先押后验",
                title = "预判本",
                hint = "押错了要认",
                modifier = Modifier.weight(1f),
            ) { Openers.yishou(ctx, "bets") }
            TrainTile(
                kicker = "对着刷",
                title = "刷屏做题",
                hint = "边看边答",
                modifier = Modifier.weight(1f),
            ) { runCatching { ctx.startActivity(com.yishou.app.scroll.ScrollActivity.startIntent(ctx)) } }
        }

        SectionHeader("入口")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrainTile(
                kicker = "题库与素材",
                title = "什么都往这放",
                hint = "导入 / 分类 / 练",
                modifier = Modifier.weight(1f),
            ) { Openers.yishou(ctx, "bank") }
            TrainTile(
                kicker = "想一件事",
                title = "把一件事接着想",
                hint = "六骰子 · 主线",
                modifier = Modifier.weight(1f),
            ) { Openers.yishou(ctx, "think") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrainTile(
                kicker = "语音伙伴",
                title = "一起看一段",
                hint = "它问你答",
                modifier = Modifier.weight(1f),
            ) { Openers.yishou(ctx, "partner") }
            TrainTile(
                kicker = "AI 助手",
                title = "直接聊",
                hint = "接你自己的模型",
                modifier = Modifier.weight(1f),
            ) { Openers.yishou(ctx, "assistant") }
        }

        SectionHeader("娱乐收费站", "一手")
        ChangliuCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    "关注",
                    "${snap.ysWatched} 个",
                    "打开先答题",
                    if (snap.ysWatched > 0) Tone.Accent else Tone.Neutral,
                    Modifier.weight(1f),
                )
                StatTile(
                    "今天",
                    "${snap.ysGateShown} 次",
                    "放行 ${snap.ysGatePass} 次",
                    Tone.Neutral,
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "打开已选应用先做题，正常通过换一段放行，到点重新锁。拦截和判卷都在长流里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = { Openers.yishou(ctx, "watched") }) { Text("选择娱乐应用") }
                TextButton(onClick = { Openers.yishou(ctx, "gatedoctor") }) { Text("拦不住？体检") }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "以上进入一手的完整界面；返回键回到长流。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrainTile(
    kicker: String,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ChangliuCard(modifier = modifier, onClick = onClick) {
        Kicker(kicker, Tone.Accent)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
