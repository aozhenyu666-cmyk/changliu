package com.changliu.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.brain.ActiveTuning
import com.changliu.app.brain.BrainMemory
import com.changliu.app.brain.BrainTuning
import com.changliu.app.brain.MemoryEntry
import com.changliu.core.brain.TuningProposal
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatusPill
import com.changliu.app.ui.components.Tone
import com.changliu.app.ui.theme.ChangliuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 它记得什么：可看、可删、可暂停消化（设计方案 §8.3 的可回滚边界）。 */
class MemoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChangliuTheme {
                MemoryScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun MemoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var proposals by remember { mutableStateOf<List<TuningProposal>>(emptyList()) }
    var activeTuning by remember { mutableStateOf<ActiveTuning?>(null) }
    var paused by remember { mutableStateOf(false) }
    var ledger by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            entries = BrainMemory.entries(ctx)
            proposals = BrainTuning.proposals(ctx)
            activeTuning = BrainTuning.active(ctx)
            paused = BrainMemory.paused(ctx)
            ledger = BrainMemory.ledgerSize(ctx)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹ 返回",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onBack() },
                )
                Spacer(Modifier.width(16.dp))
                Text("它记得什么", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "只写有 ≥3 次证据的结论，每条都能回指到具体时间；删除后不再自动写回。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("消化")
            ChangliuCard {
                StatRow("账本", "$ledger 条")
                StatRow("消化", if (paused) "已暂停" else "运行中", if (paused) Tone.Warn else Tone.Ok)
                Row {
                    TextButton(onClick = {
                        BrainMemory.setPaused(ctx, !paused)
                        revision++
                    }) { Text(if (paused) "恢复消化" else "暂停消化") }
                    TextButton(onClick = {
                        BrainMemory.digest(ctx)
                        revision++
                    }) { Text("重新消化") }
                }
            }

            SectionHeader("复盘建议")
            activeTuning?.let { active ->
                ChangliuCard {
                    Kicker("已采用 · 自动过期", Tone.Accent)
                    Text(active.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        active.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "到期 ${format(active.until)} 自动恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            BrainTuning.reset(ctx)
                            revision++
                        }) { Text("恢复默认") }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (proposals.isEmpty() && activeTuning == null) {
                ChangliuCard {
                    Text(
                        "本周没有新的复盘建议。同类偏离出现 5 次才会提；建议只调语气权重，7 天自动过期。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            proposals.forEach { proposal ->
                ChangliuCard {
                    Kicker("建议 · 证据 ${proposal.evidence} 次", Tone.Warn)
                    Text(proposal.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        proposal.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = {
                            BrainTuning.apply(ctx, proposal)
                            revision++
                        }) { Text("采用一周") }
                        TextButton(onClick = {
                            // 忽略：只在本轮隐藏，不落盘
                            proposals = proposals.filterNot { it.key == proposal.key }
                        }) { Text("这次不用") }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            SectionHeader("长期记忆", "${entries.size} 条")
            if (entries.isEmpty()) {
                ChangliuCard {
                    Text(
                        "还没有足够的证据。多用几天，同类偏离出现 3 次才会写。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            entries.forEach { entry ->
                ChangliuCard {
                    Kicker("记忆")
                    Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "证据 ${entry.evidence} 次 · ${format(entry.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "删除",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable {
                                BrainMemory.clear(ctx, entry.key)
                                revision++
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, tone: Tone = Tone.Neutral) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        StatusPill(value, tone)
    }
}

private fun format(at: Long): String = runCatching {
    SimpleDateFormat("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(Date(at))
}.getOrDefault("")
