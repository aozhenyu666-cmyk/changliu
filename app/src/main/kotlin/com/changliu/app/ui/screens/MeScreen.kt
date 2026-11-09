package com.changliu.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.changliu.app.BuildConfig
import com.changliu.app.infra.a11y.A11yDoctorRuntime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import com.changliu.app.brain.VoiceNudge
import com.changliu.app.ui.ConstraintsActivity
import com.changliu.app.ui.MemoryActivity
import com.changliu.app.ui.Openers
import com.changliu.app.ui.UnifiedSettingsActivity
import com.changliu.app.ui.ShellSnap
import com.changliu.app.ui.components.ChangliuCard
import com.changliu.app.ui.components.EntryRow
import com.changliu.app.ui.components.Kicker
import com.changliu.app.ui.components.ScreenTitle
import com.changliu.app.ui.components.SectionHeader
import com.changliu.app.ui.components.StatusPill
import com.changliu.app.ui.components.Tone
import com.changliu.app.ui.components.fg

@Composable
fun MeScreen(snap: ShellSnap) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        ScreenTitle("我的", "状态、权限、三套入口")

        SectionHeader("现在")
        ChangliuCard {
            RowLine("无障碍", if (snap.a11yOn) "已开" else "未开", if (snap.a11yOn) Tone.Ok else Tone.Bad)
            RowLine("常驻守护", if (snap.guardRunning) "运行中" else "未运行", if (snap.guardRunning) Tone.Ok else Tone.Neutral)
            RowLine("总控", snap.zkHeadline, if (snap.zkStrict) Tone.Seal else Tone.Ok)
            RowLine("一手关注", "${snap.ysWatched} 个应用", if (snap.ysWatched > 0) Tone.Accent else Tone.Neutral)
            RowLine("驾驶员拦截", if (snap.driverEnabled) "${snap.driverBlocked} 个应用" else "已关", if (snap.driverEnabled) Tone.Accent else Tone.Neutral)
        }

        SectionHeader("长流引擎")
        ChangliuCard {
            RowLine("五态", snap.flowLabel, Tone.Accent)
            RowLine("语气", snap.moodLabel, Tone.Accent)
            EntryRow(
                "它记得什么",
                "查看 / 删除 / 暂停消化 · 复盘建议",
                onClick = { ctx.startActivity(Intent(ctx, MemoryActivity::class.java)) },
            )
            VoiceRow(ctx)
        }

        SectionHeader("无障碍医生")
        DoctorCard(snap)

        SectionHeader("集成")
        ChangliuCard {
            EntryRow(
                "约束",
                "统一名单 / 四种模式 / 同步回三端",
                onClick = { ctx.startActivity(Intent(ctx, ConstraintsActivity::class.java)) },
                showDivider = true,
            )
            EntryRow(
                "API 与集成",
                "网关 / API key / 模型 / 语音 / Notion 一次配好",
                onClick = { ctx.startActivity(Intent(ctx, UnifiedSettingsActivity::class.java)) },
            )
        }

        SectionHeader("权限")
        ChangliuCard {
            EntryRow("无障碍服务", if (snap.a11yOn) "已开；关着就完全不拦" else "必须手动打开", onClick = { Openers.a11ySettings(ctx) }, showDivider = true)
            EntryRow("悬浮窗", "陪练小卡 / 遮罩兜底", onClick = { Openers.overlaySettings(ctx) }, showDivider = true)
            EntryRow("电池优化", "别让系统把长流省掉", onClick = { Openers.batterySettings(ctx) }, showDivider = true)
            EntryRow("使用情况访问", "无障碍掉线时的第二只眼", onClick = { Openers.usageSettings(ctx) }, showDivider = true)
            EntryRow("通知", "拦截、提醒、常驻状态", onClick = { Openers.appNotificationSettings(ctx) })
        }

        SectionHeader("三套入口")
        ChangliuCard {
            EntryRow("陪练（驾驶员）", "开始陪练 / 拦截设置 / 自我察觉", onClick = { Openers.driver(ctx) }, showDivider = true)
            EntryRow("训练（一手）", "题库 / 六骰子 / 语音伙伴 / 设置", onClick = { Openers.yishou(ctx, "settings") }, showDivider = true)
            EntryRow("四部（总控）", "严管 / 总控时间 / Notion / 设置", onClick = { Openers.zk(ctx, "settings") })
        }

        SectionHeader("关于")
        ChangliuCard {
            Kicker("长流")
            Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodyMedium)
            Text(
                "单进程融合：驾驶员 + 一手 + 总控。GPL-3.0；一手/总控源码以 library 模块编译。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "只在本机保存数据；API key 用系统密钥库加密，明文不入库、不进日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VoiceRow(ctx: Context) {
    var enabled by remember { mutableStateOf(VoiceNudge.enabled(ctx)) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("语音搭话", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "沉默时先用语音开口（不录音，只朗读）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                VoiceNudge.setEnabled(ctx, it)
            },
        )
    }
}

@Composable
private fun RowLine(label: String, value: String, tone: Tone) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        StatusPill(value, tone)
    }
}

/**
 * 无障碍医生：被摘掉自动写回、开着却僵死自动“关一下再开”、可选定时重启（熄屏时做）。
 * 没有写权限时，告诉你缺哪一步（Shizuku 授权一次 / adb 授权一次）。
 */
@Composable
private fun DoctorCard(snap: ShellSnap) {
    val ctx = LocalContext.current
    var every by remember { mutableStateOf(snap.doctorEvery) }
    var msg by remember { mutableStateOf<String?>(null) }
    ChangliuCard {
        RowLine("自救能力", if (snap.canHeal) "能自己救" else "只能提醒", if (snap.canHeal) Tone.Ok else Tone.Warn)
        Text(snap.doctorLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!snap.canHeal) {
            Text(
                "想让它自己救：装 Shizuku 后在「陪练（驾驶员）」首页授权一次；或电脑上执行一次 " +
                    "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS",
                style = MaterialTheme.typography.bodySmall,
                color = Tone.Warn.fg(),
            )
        }
        Text("定时重启（熄屏、没在答题 / 总控时间时）", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            A11yDoctorRuntime.EVERY_CHOICES.forEach { h ->
                FilterChip(
                    selected = every == h,
                    onClick = { every = h; A11yDoctorRuntime.setEveryHours(ctx, h) },
                    label = { Text(if (h == 0) "关" else "每 $h 小时") },
                )
            }
        }
        OutlinedButton(onClick = {
            msg = if (A11yDoctorRuntime.restartNow(ctx)) "正在关一下再开，几秒后回来看记录。" else "没有写回权限，只能去系统设置里手动关开。"
        }, modifier = Modifier.fillMaxWidth()) { Text("现在重启一次无障碍") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (snap.doctorLog.isNotEmpty()) {
            Text("最近的修复", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            snap.doctorLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
