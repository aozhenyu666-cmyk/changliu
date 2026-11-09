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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.changliu.app.infra.settings.UnifiedConfig
import com.changliu.app.infra.settings.UnifiedSettings
import com.changliu.app.infra.settings.UnifiedStatus
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatusPill
import com.changliu.app.ui.components.Tone
import com.changliu.app.ui.theme.ChangliuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * API 与集成：一次填好网关/key/模型，同步给驾驶员、一手、总控；语音与 Notion 也在这里。
 * key 的落盘分别是 SecretBox / EncryptedSharedPreferences / sealSecrets，三处都只存密文。
 */
class UnifiedSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChangliuTheme {
                UnifiedSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun UnifiedSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf<UnifiedConfig?>(null) }
    var status by remember { mutableStateOf<UnifiedStatus?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cfg = UnifiedSettings.read(ctx)
            status = UnifiedSettings.status(ctx)
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
                .padding(top = 8.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹ 返回",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onBack() },
                )
                Spacer(Modifier.width(16.dp))
                Text("API 与集成", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "填一次，驾驶员大脑、一手、总控参谋共用同一套网关和 key；语音与 Notion 也在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            status?.let { s ->
                Spacer(Modifier.height(12.dp))
                Row {
                    StatusPill(if (s.driverReady) "驾驶员已配" else "驾驶员未配", if (s.driverReady) Tone.Ok else Tone.Neutral)
                    Spacer(Modifier.width(8.dp))
                    StatusPill(if (s.yishouReady) "一手已配" else "一手未配", if (s.yishouReady) Tone.Ok else Tone.Neutral)
                    Spacer(Modifier.width(8.dp))
                    StatusPill(if (s.zongkongReady) "总控已配" else "总控未配", if (s.zongkongReady) Tone.Ok else Tone.Neutral)
                }
            }

            val c = cfg
            if (c == null) {
                Spacer(Modifier.height(24.dp))
                Text("正在读取本机设置…", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            SectionHeader("大模型", "OpenAI 兼容网关")
            ChangliuCard {
                Kicker("三端共用")
                Field("网关地址", c.baseUrl) { cfg = c.copy(baseUrl = it) }
                Field("API key", c.apiKey, secret = true) { cfg = c.copy(apiKey = it) }
                Field("主模型（大脑 / 参谋）", c.brainModel) { cfg = c.copy(brainModel = it) }
                Field("轻模型（限速 / 逃逸）", c.lightModel) { cfg = c.copy(lightModel = it) }
                Field("识图模型（可空）", c.visionModel) { cfg = c.copy(visionModel = it) }
                Row {
                    Button(
                        onClick = {
                            val current = cfg ?: return@Button
                            scope.launch {
                                withContext(Dispatchers.IO) { UnifiedSettings.apply(ctx, current) }
                                status = withContext(Dispatchers.IO) { UnifiedSettings.status(ctx) }
                                Toast.makeText(ctx, "已同步到驾驶员 / 一手 / 总控", Toast.LENGTH_LONG).show()
                            }
                        },
                        shape = RoundedCornerShape(50),
                    ) { Text("保存并同步三端") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val current = cfg ?: return@TextButton
                        Toast.makeText(ctx, "测试中…", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { UnifiedSettings.test(ctx, current) }
                            Toast.makeText(
                                ctx,
                                r.fold({ "网关正常：$it" }, { it.message ?: "失败" }),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }) { Text("测试连接") }
                }
            }

            SectionHeader("语音", "听写 / 朗读（可空）")
            ChangliuCard {
                Field("语音接口地址", c.speechBaseUrl) { cfg = c.copy(speechBaseUrl = it) }
                Field("语音 API key", c.speechApiKey, secret = true) { cfg = c.copy(speechApiKey = it) }
                Field("听写模型", c.sttModel) { cfg = c.copy(sttModel = it) }
                Field("朗读模型", c.ttsModel) { cfg = c.copy(ttsModel = it) }
                Field("音色", c.ttsVoice) { cfg = c.copy(ttsVoice = it) }
            }

            SectionHeader("Notion", "总控的对账与同步")
            ChangliuCard {
                Field("Integration token", c.notionToken, secret = true) { cfg = c.copy(notionToken = it) }
                Field("父页面 ID / 链接", c.notionParent) { cfg = c.copy(notionParent = it) }
                Text(
                    "各数据库 ID 仍可在总控「我 → Notion」里细调；这里只统一 token 与父页面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "key 只存本机密文：驾驶员走 SecretBox，一手走加密 SharedPreferences，总控写盘前 sealSecrets。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
