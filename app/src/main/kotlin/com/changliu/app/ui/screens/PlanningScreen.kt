package com.changliu.app.ui.screens

import android.content.Intent
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.hub.stats.HubStats
import com.changliu.app.ui.ConductorActivity
import com.changliu.app.ui.Openers
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.EntryRow
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.ScreenTitle
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatTile
import com.changliu.app.ui.components.Tone
import com.zongkong.app.ZkHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class PlanInfo(
    val mainKey: String = "",
    val mainTitle: String = "",
    val done: Int = 0,
    val total: Int = 0,
)

/** 规划栏（P4 MVP）：今日主线 + 本周复盘 + 四部/计划入口；目标树仍由总控计划页维护。 */
@Composable
fun PlanningScreen() {
    val ctx = LocalContext.current
    val zk = ctx.applicationContext as ZkHost

    val plan by produceState(PlanInfo()) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val store = zk.store
                    val now = System.currentTimeMillis()
                    val key = store.mainKey(now)
                    val rec = key.takeIf { it.isNotBlank() }?.let { store.work.value.rec(it) }
                    val status = store.status(now)
                    PlanInfo(key, rec?.title.orEmpty(), status.doneCount, status.gates.size)
                }.getOrDefault(PlanInfo())
            }
            delay(5_000)
        }
    }
    val week by produceState(emptyList<HubStats.Day>()) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { HubStats.week(ctx) }.getOrDefault(emptyList()) }
            delay(15_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle("规划", "目标 → 今日主线 → 行动 → 训练")

        ChangliuCard {
            Kicker("今日主线", Tone.Accent)
            Text(
                plan.mainTitle.ifBlank { "还没排主线" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                if (plan.mainKey.isBlank()) "去「计划」或让中枢排今天。"
                else "主线键 ${plan.mainKey} · 今日部署 ${plan.done}/${plan.total}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { Openers.zk(ctx, "plan") },
                    shape = RoundedCornerShape(50),
                ) { Text("打开计划") }
                Button(
                    onClick = {
                        ctx.startActivity(
                            Intent(ctx, ConductorActivity::class.java)
                                .putExtra("prompt", "对照今天的计划和账本，指出主线卡在哪，给我一个下一步。"),
                        )
                    },
                    shape = RoundedCornerShape(50),
                ) { Text("问中枢") }
            }
        }

        SectionHeader("本周")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "训练",
                "${week.sumOf { it.trainSets }} 组",
                "7 天",
                Tone.Ok,
                Modifier.weight(1f),
            )
            StatTile(
                "娱乐",
                "${week.sumOf { it.entertainmentMin }} 分",
                "7 天",
                Tone.Accent,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("拦截", "${week.sumOf { it.gates }} 次", "7 天", Tone.Accent, Modifier.weight(1f))
            StatTile("陪练", "${week.sumOf { it.liveMin }} 分", "7 天", Tone.Ok, Modifier.weight(1f))
        }

        SectionHeader("复盘")
        ChangliuCard {
            Text(
                "周复盘由中枢发起：对照本周账本与计划，指出落后的里程碑并给调整建议。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    ctx.startActivity(
                        Intent(ctx, ConductorActivity::class.java)
                            .putExtra("prompt", "做一次本周复盘：对照账本和计划，指出一个落后的里程碑，并给出具体调整建议。"),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
            ) { Text("开始周复盘") }
        }

        SectionHeader("入口")
        ChangliuCard {
            EntryRow("中枢对话", "能读三端数据、调工具、改约束", onClick = {
                ctx.startActivity(Intent(ctx, ConductorActivity::class.java))
            }, showDivider = true)
            EntryRow("四部", "行为 / 学习 / 身体 / 财务", onClick = { Openers.zk(ctx, "depts") }, showDivider = true)
            EntryRow("今日部署", "今天排了什么、交没交", onClick = { Openers.zk(ctx, "today") }, showDivider = true)
            EntryRow("计划", "目标树、里程碑、周计划", onClick = { Openers.zk(ctx, "plan") })
        }
    }
}
