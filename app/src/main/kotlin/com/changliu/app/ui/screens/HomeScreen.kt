package com.changliu.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.changliu.app.ui.ShellSnap
import com.changliu.app.ui.components.BigFigure
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.EntryRow
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatTile
import com.changliu.app.ui.components.StatusPill
import com.changliu.app.ui.components.ThinProgress
import com.changliu.app.ui.components.Tone

@Composable
fun HomeScreen(
    snap: ShellSnap,
    onOpenDriver: () -> Unit,
    onGoTrain: () -> Unit,
    onGoDept: () -> Unit,
    onOpenZkChat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp),
    ) {
        Header(snap)

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(if (snap.a11yOn) "无障碍已开" else "无障碍未开", if (snap.a11yOn) Tone.Ok else Tone.Bad)
            StatusPill(if (snap.guardRunning) "守护中" else "未守护", if (snap.guardRunning) Tone.Ok else Tone.Neutral)
            StatusPill(if (snap.zkStrict) "总控严管" else "总控放行", if (snap.zkStrict) Tone.Seal else Tone.Neutral)
        }

        Spacer(Modifier.height(16.dp))
        if (snap.locked) LockedHero(snap, onOpenDriver) else IdleHero(snap, onOpenDriver)

        SectionHeader("今天")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "拦截",
                if (snap.driverEnabled) "${snap.driverBlocked} 个" else "已关",
                if (snap.driverEnabled) "放行 ${snap.driverUsedMin}/${snap.driverQuotaMin} 分钟" else "在「我的」里打开",
                if (snap.driverEnabled) Tone.Accent else Tone.Neutral,
                Modifier.weight(1f),
            )
            StatTile(
                "总控",
                snap.zkHeadline,
                if (snap.zkQuotaMin > 0) "娱乐 ${snap.zkPlayMin}/${snap.zkQuotaMin} 分钟" else "未设娱乐额度",
                if (snap.zkStrict) Tone.Seal else if (snap.zkHeadline == "休假中") Tone.Neutral else Tone.Ok,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "训练",
                "${snap.ysWatched} 个关注",
                if (snap.ysWatched > 0) "今天拦 ${snap.ysGateShown} 次 · 放行 ${snap.ysGatePass}" else "还没选娱乐应用",
                if (snap.ysWatched > 0) Tone.Accent else Tone.Neutral,
                Modifier.weight(1f),
            )
            StatTile(
                "挂着的事",
                "${snap.agendaOpen} 条",
                if (snap.agendaOpen > 0) "下次开局会先问起" else "议程是空的",
                if (snap.agendaOpen > 0) Tone.Warn else Tone.Neutral,
                Modifier.weight(1f),
            )
        }

        SectionHeader("下一步")
        ChangliuCard {
            EntryRow("开始训练", "题库 / 六骰子 / 题卡 / 语音伙伴", onClick = onGoTrain, showDivider = true)
            EntryRow("去四部", "总控时间 / 严管 / 今日部署", onClick = onGoDept, showDivider = true)
            EntryRow("找参谋", "把今天交给总控排一遍", onClick = onOpenZkChat)
        }

        if (snap.lastLine.isNotBlank()) {
            SectionHeader("上一次")
            ChangliuCard(tone = Tone.Neutral) {
                Kicker("结论 / 题目")
                Text(
                    snap.lastLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Header(snap: ShellSnap) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                snap.greeting,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                snap.dateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "流",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun LockedHero(snap: ShellSnap, onOpenDriver: () -> Unit) {
    ChangliuCard {
        Kicker("陪练中 · ${snap.moodLabel}", Tone.Accent)
        Text(
            snap.task.ifBlank { snap.topic }.ifBlank { "正在陪练" },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            BigFigure("%d:%02d".format(snap.minutesLeft / 60, snap.minutesLeft % 60))
            Spacer(Modifier.width(10.dp))
            Text("剩余", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${(snap.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ThinProgress(snap.progress)
        Spacer(Modifier.height(4.dp))
        Button(onClick = onOpenDriver, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
            Text("回到陪练")
        }
    }
}

@Composable
private fun IdleHero(snap: ShellSnap, onOpenDriver: () -> Unit) {
    ChangliuCard {
        Kicker("现在 · ${snap.flowLabel} · ${snap.moodLabel}", Tone.Accent)
        Text(
            "先钉住一件事。",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "开着 Live 学的时候，我在顶上问一句；打开娱乐 App 时，先答一道有依据的题。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onOpenDriver, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50)) {
            Text("开始陪练")
        }
    }
}
