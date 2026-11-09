package com.changliu.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.changliu.app.hub.conductor.ActionRow
import com.changliu.app.hub.conductor.AiActionLog
import com.changliu.app.hub.conductor.ChatLine
import com.changliu.app.hub.conductor.Conductor
import com.changliu.app.hub.conductor.PendingAction
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.theme.ChangliuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 中枢对话：能调工具、放宽约束需确认、每条操作可查可撤销。 */
class ConductorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = intent.getStringExtra("prompt")
        setContent {
            ChangliuTheme {
                ConductorScreen(initialPrompt = initial, onBack = { finish() })
            }
        }
    }
}

@Composable
private fun ConductorScreen(initialPrompt: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(listOf<ChatLine>()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var logs by remember { mutableStateOf(listOf<ActionRow>()) }

    fun refreshLogs() {
        scope.launch { logs = withContext(Dispatchers.IO) { AiActionLog.list(ctx, 20) } }
    }
    LaunchedEffect(Unit) { refreshLogs() }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        input = ""
        val history = lines
        lines = lines + ChatLine("user", text)
        busy = true
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { Conductor.send(ctx, text, history) }
                    .getOrElse { com.changliu.app.hub.conductor.ConductorReply("出错：${it.message}", error = true) }
            }
            lines = lines + ChatLine("assistant", reply.text)
            pending = reply.pending
            busy = false
            refreshLogs()
        }
    }

    var initialSent by remember { mutableStateOf(false) }
    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank() && !initialSent) {
            initialSent = true
            input = initialPrompt
            send()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 返回") }
                Spacer(Modifier.width(4.dp))
                Text("中枢", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                "能读三端数据、调工具；收紧直接执行，放宽会停下来问你。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("对话")
            ChangliuCard {
                if (lines.isEmpty()) {
                    Text(
                        "试试：看看今天的完成率；把抖音设为直接拦；给我排 3 组申论。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                lines.forEach { line ->
                    Kicker(if (line.role == "user") "你" else "中枢")
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (line.role == "user") FontWeight.Medium else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (busy) Text("中枢在想…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("说一句") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { send() }, enabled = !busy, shape = RoundedCornerShape(50)) {
                    Text(if (busy) "思考中" else "发送")
                }
            }

            SectionHeader("AI 操作日志", "可撤销")
            ChangliuCard {
                if (logs.isEmpty()) {
                    Text("还没有操作。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                logs.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${row.level} · ${row.tool}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(row.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (row.undoType.isNotBlank() && !row.undone) {
                            TextButton(onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { AiActionLog.undo(ctx, row.id) }
                                    Toast.makeText(ctx, r.text, Toast.LENGTH_SHORT).show()
                                    refreshLogs()
                                }
                            }) { Text("撤销") }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("需要确认") },
            text = { Text(p.prompt) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { Conductor.confirm(ctx, p) }
                        lines = lines + ChatLine("assistant", outcome.text)
                        refreshLogs()
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("算了") } },
        )
    }
}
