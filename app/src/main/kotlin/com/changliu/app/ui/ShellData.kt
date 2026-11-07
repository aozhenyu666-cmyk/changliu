package com.changliu.app.ui

import android.content.Context
import com.changliu.app.A11yGuard
import com.changliu.app.ChangliuApp
import com.changliu.app.Engine
import com.changliu.app.infra.keepalive.KeepAliveService
import com.yishou.app.settings.AppZone
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 统一壳的状态聚合：只读，不写任何业务状态。
 *
 * 三套业务各自有自己的 Store / StateFlow；这里在 IO 线程上取一份快照，
 * 让首页能一屏看到「陪练 / 拦截 / 总控 / 一手」四个面。
 */
data class ShellSnap(
    val greeting: String = "你好。",
    val dateLabel: String = "",
    // 驾驶员：陪练
    val locked: Boolean = false,
    val task: String = "",
    val topic: String = "",
    val minutesLeft: Int = 0,
    val progress: Float = 0f,
    // 基础设施
    val a11yOn: Boolean = false,
    val guardRunning: Boolean = false,
    // 驾驶员：拦截
    val driverEnabled: Boolean = false,
    val driverBlocked: Int = 0,
    val driverUsedMin: Int = 0,
    val driverQuotaMin: Int = 0,
    val agendaOpen: Int = 0,
    val lastLine: String = "",
    // 总控
    val zkStrict: Boolean = false,
    val zkHeadline: String = "放行",
    val zkReason: String = "",
    val zkPlayMin: Int = 0,
    val zkQuotaMin: Int = 0,
    val zkGatesDone: Int = 0,
    val zkGatesTotal: Int = 0,
    val hqActive: Boolean = false,
    val hqLeftMin: Int = 0,
    // 一手
    val ysWatched: Int = 0,
    val ysGateShown: Int = 0,
    val ysGatePass: Int = 0,
    // 长流引擎
    val flowLabel: String = "思考在线",
    val moodLabel: String = "温和",
) {
    val driverLeft: Int get() = (driverQuotaMin - driverUsedMin).coerceAtLeast(0)
    val zkLeft: Int get() = (zkQuotaMin - zkPlayMin).coerceAtLeast(0)
}

object ShellData {

    fun read(ctx: Context): ShellSnap {
        val app = ctx.applicationContext as? ChangliuApp
        val now = System.currentTimeMillis()
        val today = runCatching { LocalDate.now(AppZone.zone) }.getOrDefault(LocalDate.now())
        val greeting = runCatching {
            when (LocalDateTime.now(AppZone.zone).hour) {
                in 0..4 -> "夜深了。"
                in 5..10 -> "早上好。"
                in 11..13 -> "中午好。"
                in 14..17 -> "下午好。"
                else -> "晚上好。"
            }
        }.getOrDefault("你好。")
        val dateLabel = runCatching {
            today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))
        }.getOrDefault(today.toString())

        // 驾驶员
        val snap = Engine.snapshot
        val locked = Engine.locked
        val leftSec = snap?.let { ((it.endsAt - now) / 1000).coerceAtLeast(0) } ?: 0
        val progress = snap?.let {
            val total = (it.endsAt - it.startedAt).coerceAtLeast(1L)
            ((now - it.startedAt).toFloat() / total).coerceIn(0f, 1f)
        } ?: 0f

        val blocking = runCatching { Engine.store.blocking() }.getOrNull()
        val todayKey = today.toString()
        val used = if (blocking?.day == todayKey) blocking.grantedToday.values.sum() else 0
        val agendaOpen = runCatching { Engine.store.agenda().open().size }.getOrDefault(0)
        val last = runCatching { Engine.store.last() }.getOrNull()
        val lastLine = last?.conclusion?.takeIf { it.isNotBlank() }
            ?: last?.task?.takeIf { it.isNotBlank() }
            ?: last?.topic.orEmpty()

        // 总控
        val zkStatus = app?.let { runCatching { it.store.status(now) }.getOrNull() }
        val hq = app?.let { runCatching { it.store.hq.value.active }.getOrNull() }
        val hqLeft = hq?.let { ((it.endsAt - now) / 60_000L).coerceAtLeast(0).toInt() } ?: 0

        // 一手
        val prefs = app?.let { runCatching { it.settings.app.value }.getOrNull() }
        val dayStats = app?.let { runCatching { it.stats.day(today) }.getOrNull() }

        return ShellSnap(
            greeting = greeting,
            dateLabel = dateLabel,
            locked = locked,
            task = snap?.task.orEmpty(),
            topic = snap?.topic.orEmpty(),
            minutesLeft = (leftSec / 60).toInt(),
            progress = progress,
            a11yOn = runCatching { A11yGuard.isOn(ctx) }.getOrDefault(false),
            guardRunning = KeepAliveService.running,
            driverEnabled = blocking?.enabled == true,
            driverBlocked = blocking?.blocked?.size ?: 0,
            driverUsedMin = used,
            driverQuotaMin = blocking?.dailyMinutes ?: 0,
            agendaOpen = agendaOpen,
            lastLine = lastLine,
            zkStrict = zkStatus?.strict == true,
            zkHeadline = zkStatus?.headline ?: "放行",
            zkReason = zkStatus?.reasons?.firstOrNull()?.text.orEmpty(),
            zkPlayMin = zkStatus?.playMin ?: 0,
            zkQuotaMin = zkStatus?.quotaMin ?: 0,
            zkGatesDone = zkStatus?.doneCount ?: 0,
            zkGatesTotal = zkStatus?.gates?.size ?: 0,
            hqActive = hq != null,
            hqLeftMin = hqLeft,
            ysWatched = prefs?.watched?.size ?: 0,
            ysGateShown = dayStats?.events?.get("gate_shown") ?: 0,
            ysGatePass = dayStats?.events?.get("gate_pass") ?: 0,
            flowLabel = com.changliu.app.brain.BrainRuntime.stateLabel(),
            moodLabel = com.changliu.app.brain.BrainRuntime.moodLabel(),
        )
    }
}
