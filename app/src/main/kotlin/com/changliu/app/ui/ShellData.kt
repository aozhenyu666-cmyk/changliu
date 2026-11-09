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
    // 防线（GateDirector 单管道）
    val credit: Int = 70,
    val gateLevel: Int = 1,
    val gateLabel: String = "断点快答",
    val gateMinutes: Int = 10,
    val gateReason: String = "",
    /** 四部 → 一手的弹药：已经落进素材池的假设数 / 还没落的。 */
    val veinFed: Int = 0,
    val veinPending: Int = 0,
    // 公审（总控四部门流水线）
    val courtReady: Boolean = false,
    val frontline: String = "",
    val examLeft: Int? = null,
    val fuseText: String = "",
    val drillsToday: Int = 0,
    val drillsGoal: Int = 5,
    val openOrder: String = "",
    val hqAt: String = "",
    // 无障碍医生
    val canHeal: Boolean = false,
    val doctorLine: String = "",
    val doctorEvery: Int = 6,
    val doctorLog: List<String> = emptyList(),
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

        // 公审
        val court = app?.let { runCatching { it.store.court.value }.getOrNull() }
        val courtDay = app?.let { com.zongkong.core.DayClock.dateKey(now, it.store.zone) }.orEmpty()
        val fuse = app?.let { runCatching { it.store.fuse(now) }.getOrNull() }
        val hqCfg = app?.let { runCatching { it.store.config.value.hq }.getOrNull() }

        // 一手
        val prefs = app?.let { runCatching { it.settings.app.value }.getOrNull() }
        val dayStats = app?.let { runCatching { it.stats.day(today) }.getOrNull() }

        // 防线：总控定级后的关卡 + 四部喂给一手的弹药量
        val zk = runCatching { com.changliu.app.hub.policy.GateDirector.zkContext(ctx) }
            .getOrDefault(com.changliu.app.hub.policy.GateDirector.ZkContext())
        val zone = runCatching { com.changliu.app.hub.policy.GateDirector.zone(ctx) }.getOrNull()
        val hypo = runCatching {
            (ctx.applicationContext as com.zongkong.app.ZkHost).store.vein.value.nodes
                .filter { it.vStatus == com.zongkong.core.vein.VStatus.HYPO }
        }.getOrDefault(emptyList())
        val fed = ctx.getSharedPreferences("hub_sync", Context.MODE_PRIVATE)
            .getStringSet("vein_seen", emptySet()).orEmpty()

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
            credit = zk.credit,
            gateLevel = zone?.level?.n ?: 4,
            gateLabel = zone?.level?.label ?: "绝对封锁",
            gateMinutes = zone?.minutes ?: 0,
            gateReason = zone?.reason.orEmpty(),
            veinFed = hypo.count { it.id in fed },
            veinPending = hypo.count { it.id !in fed },
            courtReady = court?.baseline?.ready == true,
            frontline = court?.baseline?.frontline.orEmpty(),
            examLeft = court?.let { com.zongkong.core.court.Court.daysLeft(it, courtDay) },
            fuseText = fuse?.text.orEmpty(),
            drillsToday = court?.let { com.zongkong.core.court.Court.drillsOn(it, courtDay) } ?: 0,
            drillsGoal = com.zongkong.core.court.Court.DAILY_DRILLS,
            openOrder = court?.let { com.zongkong.core.court.Court.openOrder(it)?.text }.orEmpty(),
            hqAt = hqCfg?.takeIf { it.enabled }?.let { com.zongkong.core.DayClock.hhmm(it.at) }.orEmpty(),
            canHeal = runCatching { A11yGuard.canHeal(ctx) }.getOrDefault(false),
            doctorLine = runCatching { com.changliu.app.infra.a11y.A11yDoctorRuntime.describe(ctx) }.getOrDefault(""),
            doctorEvery = runCatching { com.changliu.app.infra.a11y.A11yDoctorRuntime.everyHours(ctx) }.getOrDefault(6),
            doctorLog = runCatching { com.changliu.app.infra.a11y.A11yDoctorRuntime.log(ctx).take(6) }.getOrDefault(emptyList()),
        )
    }
}
