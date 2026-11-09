package com.changliu.app.ui

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.hub.policy.ConstraintStore
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatusPill
import com.changliu.app.ui.components.Tone
import com.changliu.app.ui.theme.ChangliuTheme
import com.changliu.core.hub.AppMode
import com.changliu.core.hub.ConstraintApp
import com.changliu.core.hub.ConstraintProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 统一约束：一份名单、四种模式；导入三端 / 同步回三端。 */
class ConstraintsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChangliuTheme {
                ConstraintsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun ConstraintsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var profile by remember { mutableStateOf(ConstraintProfile()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            profile = ConstraintStore.load(ctx)
            loaded = true
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
                Text("约束", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "同一个 App 只设一次；四种模式：直接拦 / 答题放行 / 只观察 / 放行。首次从三份旧名单取并集。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!loaded) {
                Spacer(Modifier.height(20.dp))
                Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            SectionHeader("统一名单", "${profile.apps.size} 个")
            ChangliuCard {
                Row {
                    Button(
                        onClick = {
                            profile = ConstraintStore.importFromLegacy(ctx)
                            ConstraintStore.syncToLegacy(ctx, profile)
                            Toast.makeText(ctx, "已从三端导入并同步", Toast.LENGTH_LONG).show()
                        },
                        shape = RoundedCornerShape(50),
                    ) { Text("从三端导入") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        ConstraintStore.syncToLegacy(ctx, profile)
                        Toast.makeText(ctx, "已同步回三端", Toast.LENGTH_LONG).show()
                    }) { Text("同步回三端") }
                }
                Text(
                    "导入会把驾驶员拦截名单、总控 blocked、一手关注名单取并集；总控 blocked 记为「直接拦」，其余记为「答题放行」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("逐个调整", "点一下切换模式")
            if (profile.apps.isEmpty()) {
                ChangliuCard {
                    Text(
                        "还没有名单。先点「从三端导入」。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            profile.apps.values.sortedBy { it.pkg }.forEach { app ->
                ChangliuCard(
                    onClick = {
                        profile = ConstraintStore.setMode(ctx, app.pkg, nextMode(app.mode))
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Kicker(app.category)
                            Text(label(ctx, app.pkg), style = MaterialTheme.typography.titleMedium)
                            Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(modeLabel(app.mode), modeTone(app.mode))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "改完记得点「同步回三端」；旧页面仍能看到同一份名单。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun nextMode(mode: AppMode): AppMode = when (mode) {
    AppMode.BLOCK -> AppMode.GATE
    AppMode.GATE -> AppMode.OBSERVE
    AppMode.OBSERVE -> AppMode.ALLOW
    AppMode.ALLOW -> AppMode.BLOCK
}

private fun modeLabel(mode: AppMode): String = when (mode) {
    AppMode.BLOCK -> "直接拦"
    AppMode.GATE -> "答题放行"
    AppMode.OBSERVE -> "只观察"
    AppMode.ALLOW -> "放行"
}

private fun modeTone(mode: AppMode): Tone = when (mode) {
    AppMode.BLOCK -> Tone.Seal
    AppMode.GATE -> Tone.Accent
    AppMode.OBSERVE -> Tone.Warn
    AppMode.ALLOW -> Tone.Neutral
}

private fun label(ctx: android.content.Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
