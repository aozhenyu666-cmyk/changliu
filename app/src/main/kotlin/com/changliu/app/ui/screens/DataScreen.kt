package com.changliu.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.hub.stats.HubStats
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.ScreenTitle
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatTile
import com.changliu.app.ui.components.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 数据页：账本上的今天 / 本周。人和中枢 AI 看的是同一份数。
 */
@Composable
fun DataScreen() {
    val ctx = LocalContext.current
    var range by rememberSaveable { mutableStateOf(0) }

    val today by produceState<HubStats.Today?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { HubStats.today(ctx) }.getOrNull() }
            delay(5_000)
        }
    }
    val week by produceState<List<HubStats.Day>>(emptyList()) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { HubStats.week(ctx) }.getOrDefault(emptyList()) }
            delay(15_000)
        }
    }
    // 防线当前档位 + 信用分：和拦截次数摆在一起，才看得出"拦得对不对"
    val guard by produceState("—" to 70) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val credit = runCatching { com.changliu.app.hub.policy.GateDirector.zkContext(ctx).credit }.getOrDefault(70)
                val zone = runCatching { com.changliu.app.hub.policy.GateDirector.zone(ctx) }.getOrNull()
                val label = zone?.let { "L${it.level.n} ${it.level.label} · ${it.minutes} 分钟" } ?: "名单为空"
                label to credit
            }
            delay(5_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle("数据", "统一账本 · 今天 / 本周")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RangeTab("今天", range == 0) { range = 0 }
            RangeTab("本周", range == 1) { range = 1 }
        }
        Spacer(Modifier.height(14.dp))

        if (range == 0) {
            val t = today
            if (t == null) {
                ChangliuCard { Text("正在读账本…", style = MaterialTheme.typography.bodyMedium) }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("屏幕", "${t.screenMin} 分钟", "前台应用合计", Tone.Neutral, Modifier.weight(1f))
                StatTile("娱乐", "${t.entertainmentMin} 分钟", "被管 App 合计", if (t.entertainmentMin > 0) Tone.Accent else Tone.Neutral, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("拦截", "${t.gatesShown} 次", "通过 ${t.gatesPassed} · ${t.passRate}%", if (t.gatesShown > 0) Tone.Accent else Tone.Neutral, Modifier.weight(1f))
                StatTile("陪练", "${t.liveMin} 分钟", "Gemini Live", if (t.liveMin > 0) Tone.Ok else Tone.Neutral, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("训练", "${t.trainSets} 组", "一手完成组数", if (t.trainSets > 0) Tone.Ok else Tone.Neutral, Modifier.weight(1f))
                StatTile("行动", "${t.tasksDone} 件", "总控做完一步", if (t.tasksDone > 0) Tone.Ok else Tone.Neutral, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    "防线",
                    guard.first,
                    "信用 ${guard.second} / 起点 70",
                    if (guard.second < 70) Tone.Warn else Tone.Ok,
                    Modifier.weight(1f),
                )
            }

            SectionHeader("用得最多的 App")
            ChangliuCard {
                if (t.topApps.isEmpty()) {
                    Text("今天还没有前台记录。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val max = t.topApps.maxOf { it.second }.coerceAtLeast(1)
                    t.topApps.forEach { (pkg, minutes) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label(ctx, pkg),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(120.dp),
                            )
                            Box(Modifier.weight(1f).height(8.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {}
                                Surface(
                                    modifier = Modifier.fillMaxWidth(minutes.toFloat() / max).height(8.dp),
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {}
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("$minutes 分", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            if (t.gliding > 0) {
                Spacer(Modifier.height(12.dp))
                ChangliuCard {
                    Text(
                        "五态今天滑向「滑行」${t.gliding} 次；可以在「我的 → 长流引擎」看当前状态和复盘。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            if (week.isEmpty()) {
                ChangliuCard { Text("正在读账本…", style = MaterialTheme.typography.bodyMedium) }
                return@Column
            }
            val entTotal = week.sumOf { it.entertainmentMin }
            val gateTotal = week.sumOf { it.gates }
            val liveTotal = week.sumOf { it.liveMin }
            val trainTotal = week.sumOf { it.trainSets }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("娱乐", "$entTotal 分钟", "7 天合计", Tone.Accent, Modifier.weight(1f))
                StatTile("拦截", "$gateTotal 次", "7 天合计", Tone.Accent, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("陪练", "$liveTotal 分钟", "7 天合计", Tone.Ok, Modifier.weight(1f))
                StatTile("训练", "$trainTotal 组", "7 天合计", Tone.Ok, Modifier.weight(1f))
            }

            SectionHeader("每天娱乐分钟")
            ChangliuCard {
                val max = week.maxOf { it.entertainmentMin }.coerceAtLeast(1)
                Row(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    week.forEach { day ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Text("${day.entertainmentMin}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((8 + 72f * day.entertainmentMin / max).dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                day.date.format(DateTimeFormatter.ofPattern("E", Locale.SIMPLIFIED_CHINESE)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "数据来自统一账本（changliu.db），只记包名、时长与判定，不记屏幕内容；保留 180 天。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RangeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
    }
}

private fun label(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
