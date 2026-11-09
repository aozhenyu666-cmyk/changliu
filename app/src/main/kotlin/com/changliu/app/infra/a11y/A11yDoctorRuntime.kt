package com.changliu.app.infra.a11y

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import com.changliu.app.A11yGuard
import com.changliu.app.DriverDeviceAdmin
import com.changliu.app.Shell
import com.changliu.app.infra.gate.GateActivity
import com.changliu.core.A11yDoctor
import java.time.Instant
import java.time.ZoneId

/**
 * 无障碍医生（插件）：常驻守护每次复查都问它一句。
 *
 *   被摘掉 → 写回（[A11yGuard.ensure]）
 *   开着却僵死（“此服务出现故障”）→ 自己“关一下再开”
 *   定时重启（默认每 6 小时，熄屏、没在答题 / 总控时间里才做）→ 防国产 ROM 久了僵掉
 *   什么写权限都没有 → 只提醒你手动去开
 *
 * 判断规则在 [A11yDoctor]（纯 Kotlin，有单元测试）；这里只采样和执行。每次动手都记一笔，在“我的 → 无障碍医生”里看。
 */
object A11yDoctorRuntime {
    private const val PREFS = "a11y_doctor"
    private const val K_EVERY = "every_hours"
    private const val K_LAST_RESTART = "last_restart"
    private const val K_LAST_SCHEDULED = "last_scheduled"
    private const val K_DAY = "day"
    private const val K_COUNT = "count"
    private const val K_LOG = "log"
    private const val LOG_MAX = 30
    val EVERY_CHOICES = listOf(0, 3, 6, 12)

    @Volatile private var disconnectedSince = 0L
    @Volatile private var restarting = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 定时重启间隔（小时），0 = 关。 */
    fun everyHours(ctx: Context): Int = prefs(ctx).getInt(K_EVERY, 6)

    fun setEveryHours(ctx: Context, h: Int) {
        prefs(ctx).edit().putInt(K_EVERY, h.coerceIn(0, 24)).apply()
    }

    fun lastRestartAt(ctx: Context): Long = prefs(ctx).getLong(K_LAST_RESTART, 0L)

    /** 最近的修复记录（新的在前）：“11月9日 21:30 · 重启 · 开着但没在运行 · 成功”。 */
    fun log(ctx: Context): List<String> =
        prefs(ctx).getString(K_LOG, "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun day(now: Long) = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun restartsToday(ctx: Context, now: Long): Int {
        val p = prefs(ctx)
        return if (p.getString(K_DAY, "") == day(now)) p.getInt(K_COUNT, 0) else 0
    }

    private fun record(ctx: Context, now: Long, what: String, reason: String, ok: Boolean) {
        val z = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val line = "%d月%d日 %02d:%02d · %s · %s · %s".format(z.monthValue, z.dayOfMonth, z.hour, z.minute, what, reason, if (ok) "成功" else "没成")
        val next = (listOf(line) + log(ctx)).take(LOG_MAX).joinToString("\n")
        prefs(ctx).edit().putString(K_LOG, next).apply()
    }

    private fun busy(ctx: Context): Boolean {
        if (GateActivity.visible) return true
        val inHq = runCatching { (ctx.applicationContext as com.zongkong.app.ZkHost).store.hq.value.active != null }.getOrDefault(false)
        if (inHq) return true
        val mode = runCatching { ctx.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun observe(ctx: Context, now: Long): A11yDoctor.Observation {
        val me = A11yGuard.component(ctx)
        val listed = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty().split(':').any { it.trim().equals(me, true) }
        val switchOn = runCatching { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 }.getOrDefault(false)
        val connected = ChangliuA11yService.running
        disconnectedSince = when {
            !listed || !switchOn || connected -> 0L
            disconnectedSince == 0L -> now
            else -> disconnectedSince
        }
        val p = prefs(ctx)
        return A11yDoctor.Observation(
            now = now,
            listed = listed,
            switchOn = switchOn,
            connected = connected,
            disconnectedSince = disconnectedSince,
            lastEventAt = ChangliuA11yService.lastEventAt,
            screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive != false,
            canWrite = A11yGuard.canHeal(ctx),
            lastRestartAt = p.getLong(K_LAST_RESTART, 0L),
            restartsToday = restartsToday(ctx, now),
            lastScheduledAt = p.getLong(K_LAST_SCHEDULED, 0L).takeIf { it > 0 } ?: ChangliuA11yService.connectedAt,
            busy = busy(ctx),
        )
    }

    /**
     * 常驻守护每次复查调一次（后台线程）。返回无障碍此刻是否确认可用。
     */
    fun check(ctx: Context): Boolean {
        if (restarting) return false
        val now = System.currentTimeMillis()
        val o = observe(ctx, now)
        val config = A11yDoctor.Config(scheduledEveryMs = everyHours(ctx) * 3_600_000L)
        val d = A11yDoctor.decide(o, config)
        when (d.action) {
            A11yDoctor.Action.NONE -> {
                if (o.listed && o.switchOn) A11yGuard.clearDownNotice(ctx)
                return o.listed && o.switchOn && o.connected
            }
            A11yDoctor.Action.WRITE_BACK -> {
                val ok = A11yGuard.ensure(ctx)
                record(ctx, now, "写回", d.reason, ok)
                if (!ok && !A11yGuard.canHeal(ctx)) A11yGuard.notifyDown(ctx)
                return ok
            }
            A11yDoctor.Action.NOTIFY_MANUAL -> {
                A11yGuard.notifyDown(ctx)
                return false
            }
            A11yDoctor.Action.RESTART -> {
                val scheduled = d.reason.startsWith("定时")
                restart(ctx, d.reason, scheduled)
                return false
            }
        }
    }

    /** “我的”页上的“现在重启一次”。 */
    fun restartNow(ctx: Context): Boolean {
        if (!A11yGuard.canHeal(ctx)) return false
        Thread { restart(ctx, "手动重启", scheduled = false) }.start()
        return true
    }

    /** 关一下再开：先摘掉自己，等 1.5 秒，再写回。只动自己的组件。 */
    private fun restart(ctx: Context, reason: String, scheduled: Boolean) {
        if (restarting) return
        restarting = true
        val now = System.currentTimeMillis()
        try {
            val p = prefs(ctx)
            val today = day(now)
            val count = if (p.getString(K_DAY, "") == today) p.getInt(K_COUNT, 0) else 0
            val e = p.edit().putLong(K_LAST_RESTART, now).putString(K_DAY, today).putInt(K_COUNT, count + 1)
            if (scheduled) e.putLong(K_LAST_SCHEDULED, now)
            e.apply()
            val me = A11yGuard.component(ctx)
            val current = runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull()
            val off = A11yDoctor.without(current, me)
            val on = A11yDoctor.with(current, me)
            val ok = when {
                A11yGuard.canWriteDirectly(ctx) -> runCatching {
                    Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, off)
                    Thread.sleep(1_500)
                    Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, on)
                    Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    true
                }.getOrDefault(false)
                DriverDeviceAdmin.isOwner(ctx) -> runCatching {
                    val dpm = ctx.getSystemService(DevicePolicyManager::class.java)!!
                    val admin = DriverDeviceAdmin.component(ctx)
                    dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, off)
                    Thread.sleep(1_500)
                    dpm.setSecureSetting(admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, on)
                    dpm.setSecureSetting(admin, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                    true
                }.getOrDefault(false)
                Shell.granted() -> {
                    val lock = Object()
                    var result = false
                    var finished = false
                    Shell.run(
                        "settings put secure enabled_accessibility_services '$off'; sleep 2; " +
                            "settings put secure enabled_accessibility_services '$on'; settings put secure accessibility_enabled 1",
                    ) { r, _ -> synchronized(lock) { result = r; finished = true; lock.notifyAll() } }
                    synchronized(lock) { if (!finished) lock.wait(15_000) }
                    result
                }
                else -> false
            }
            disconnectedSince = 0L
            record(ctx, now, "重启", reason, ok)
        } finally {
            restarting = false
        }
    }

    /** 给首页 / “我的”一句话：现在靠什么活着、定时重启开没开。 */
    fun describe(ctx: Context): String {
        val h = everyHours(ctx)
        val heal = if (A11yGuard.canHeal(ctx)) "掉了或僵死会自己救" else "没有写回权限：掉了只能提醒你手动开"
        return heal + if (h > 0) "；每 $h 小时在熄屏时重启一次" else "；定时重启已关"
    }
}
