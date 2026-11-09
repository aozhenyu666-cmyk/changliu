package com.changliu.app.infra.link

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.changliu.app.infra.a11y.A11yExtensions
import com.changliu.app.infra.a11y.ChangliuA11yService
import com.zongkong.app.ZkHost
import com.zongkong.app.system.Notifications
import com.zongkong.core.DayClock
import com.zongkong.core.GatePhase
import com.zongkong.core.Reason
import com.zongkong.core.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 总控原来常驻服务（GuardService）每 20 秒做的后台活，融合后搬到唯一无障碍服务的 tick 上：
 *
 *   到点提醒（关卡开放 / 快截止 / 超时、该报到、娱乐额度、紧急放行结束）
 *   总控者的日程：Reminders.check → HqRunner（总控时间到点叫醒 / 自动进入、参谋主动开口、公审点名）
 *   连续震动的节拍（Buzz.tick）、战友模式解锁问候（Partner.afterUnlock）、桌面宠物刷新
 *   眼睛：截图抽查（按信用强度攒机会）、连续娱乐太久就找你
 *   每 15 分钟：Notion 同步、读一手总结、新装娱乐应用自动进拦截名单、看证据的关卡自动验收
 *
 * 拦截本身（总控时间弹回 / 严管 / 低屏做题）仍在 [ZkGateLink]。哨兵与旧健康协议不搬（融合后只有一个服务）。
 */
object ZkBackground {
    private const val TAG = "ZkBackground"
    private const val SYNC_MS = 15 * 60_000L

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "zk-background").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    @Volatile private var fgPkg: String? = null
    @Volatile private var fgStartedAt = 0L
    @Volatile private var unlockedAt = 0L
    @Volatile private var lastSync = 0L
    @Volatile private var receiverOn = false
    @Volatile private var petAttached: ChangliuA11yService? = null

    // 截图抽查
    @Volatile private var lastShot = 0L
    @Volatile private var shotDebt = 0.0
    @Volatile private var nextShotAt = 0L
    @Volatile private var lastWatch = 0L

    fun install() {
        A11yExtensions.addTicker { ctx, now -> tick(ctx, now) }
    }

    private fun host(ctx: Context): ZkHost? = ctx.applicationContext as? ZkHost

    private fun interactive(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive != false

    /** 唯一无障碍每次确认了前台应用就调一次（不经过决策链，单管道接管了也照样记）。 */
    fun noteForeground(ctx: Context, pkg: String) {
        if (pkg != fgPkg) {
            fgPkg = pkg
            fgStartedAt = System.currentTimeMillis()
        }
        ensureReceiver(ctx)
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                // 锁屏时攒下的截图机会，解锁后过一会儿补上；战友模式下打个招呼
                nextShotAt = now + kotlin.random.Random.nextLong(20_000, 180_000)
                unlockedAt = now
            }
            fgStartedAt = now
        }
    }

    private fun ensureReceiver(ctx: Context) {
        if (receiverOn) return
        receiverOn = true
        runCatching {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            val app = ctx.applicationContext
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(screen, f)
        }.onFailure { receiverOn = false }
    }

    /** 唯一无障碍的 20 秒 tick（主线程）：轻活在这里，重活丢后台线程。 */
    private fun tick(ctx: Context, now: Long) {
        val host = host(ctx) ?: return
        ensureReceiver(ctx)
        attachPet()
        val status = runCatching { host.store.status(now) }.getOrNull() ?: return
        runCatching { alerts(ctx, host, status, now) }.onFailure { Log.w(TAG, "提醒出错", it) }
        runCatching { com.zongkong.app.system.Pet.refresh(ctx) }
        if (!busy.compareAndSet(false, true)) return
        io.execute {
            try {
                background(ctx, host, now)
            } catch (e: Throwable) {
                Log.w(TAG, "后台检查出错", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** 桌面宠物要挂在无障碍窗口上：服务重连（无障碍医生重启过）后重新挂一次。 */
    private fun attachPet() {
        val svc = ChangliuA11yService.instance ?: return
        if (petAttached === svc) return
        petAttached = svc
        runCatching { com.zongkong.app.system.Pet.attach(svc) }
    }

    private fun background(ctx: Context, host: ZkHost, now: Long) {
        runCatching { com.zongkong.app.system.Reminders.check(ctx) }.onFailure { Log.w(TAG, "到点检查出错", it) }
        runCatching { com.zongkong.app.system.Buzz.tick(ctx) }
        runCatching { watch(ctx, host, now) }.onFailure { Log.w(TAG, "眼睛出错", it) }
        runCatching { com.zongkong.app.system.Partner.afterUnlock(ctx, unlockedAt, now) }
        if (now - lastSync > SYNC_MS) {
            lastSync = now
            host.appScope.launchIo {
                runCatching { host.actions.sync() }.onFailure { Log.w(TAG, "同步失败", it) }
                runCatching { host.actions.absorbYishou() }.onFailure { Log.w(TAG, "读一手总结失败", it) }
                runCatching { com.zongkong.app.guard.AutoBlock.apply(ctx) }
                runCatching {
                    val passed = host.actions.autoVerify()
                    if (passed.isNotEmpty()) Notifications.alert(ctx, "自动验收通过", passed.joinToString("、"), "rhythm")
                }
            }
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launchIo(block: suspend () -> Unit) {
        launch(Dispatchers.IO) { block() }
    }

    /** 总控者的眼睛：截图抽查；连续娱乐太久就找你。 */
    private fun watch(ctx: Context, host: ZkHost, now: Long) {
        val store = host.store
        shots(ctx, host, now)
        val pkg = fgPkg ?: return
        if (!interactive(ctx) || pkg == ctx.packageName || pkg == "com.android.systemui") return
        if (store.firstAlert("prune")) store.prune(now)
        val boss = store.config.value.boss
        val minutes = (now - fgStartedAt) / 60_000
        val binge = com.zongkong.core.boss.Intensity.bingeMinutes(boss, store.stance(now))
        if (binge > 0 && pkg in store.config.value.blocked && fgStartedAt > 0 && minutes >= binge &&
            store.firstAlert("binge:$fgStartedAt")
        ) {
            snap(ctx, host, pkg, now, force = true)
            val name = com.zongkong.app.data.AppNames.label(ctx, pkg)
            host.chat.proactive(
                "binge",
                "[触发·连续娱乐] 用户已经连续用「$name」$minutes 分钟（${com.zongkong.core.boss.Timeline.hm(fgStartedAt, store.zone)} 开始）。" +
                    "先看 device_timeline（最近 2 小时）和档案里今天的主线、约定，再找用户：说出你看到的数字，问一个问题，" +
                    "最后给【选项】现在去做主线｜再 10 分钟就停｜进总控时间。用户选“再 10 分钟”就用 commit 记成约定。",
                fallback = "「$name」已经连续 $minutes 分钟了。现在停下，回到主线。",
            )
            com.zongkong.app.system.Buzz.once(ctx, com.zongkong.app.system.Buzz.Level.FIRM)
        }
    }

    /** 截图抽查：机会按时间一直攒（熄屏、锁屏时也攒），亮屏能截时用掉；间隔随机。 */
    private fun shots(ctx: Context, host: ZkHost, now: Long) {
        val store = host.store
        val boss = store.config.value.boss
        val dt = if (lastWatch == 0L) 0L else (now - lastWatch).coerceIn(0, 3_600_000L)
        lastWatch = now
        val rate = com.zongkong.core.boss.Intensity.shotsPerHour(boss, store.stance(now))
        if (!boss.shots || rate <= 0) { shotDebt = 0.0; return }
        shotDebt = (shotDebt + rate * dt / 3_600_000.0).coerceAtMost(rate * 2.0)
        val pkg = fgPkg ?: return
        if (!interactive(ctx) || pkg == ctx.packageName || pkg == "com.android.systemui") return
        if (shotDebt >= 1.0 && now >= nextShotAt) {
            val gap = (3_600_000L / rate).coerceAtLeast(3 * 60_000L)
            nextShotAt = now + kotlin.random.Random.nextLong(gap / 3, gap)
            snap(ctx, host, pkg, now, banked = true)
        }
    }

    /** 截一张屏（Android 11 起无障碍服务能截）。只存在手机上；电话、长流自己不截。 */
    private fun snap(ctx: Context, host: ZkHost, pkg: String, now: Long, force: Boolean = false, banked: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val store = host.store
        if (!store.config.value.boss.shots) return
        if (pkg in com.zongkong.core.hq.ALWAYS_ALLOWED || pkg == ctx.packageName) return
        if (!force && now - lastShot < 3 * 60_000) return
        if (now - lastShot < 2_000) return
        val svc = ChangliuA11yService.instance ?: return
        lastShot = now
        runCatching {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    } finally {
                        hb.close()
                    }
                    bmp ?: return
                    if (banked) shotDebt = (shotDebt - 1.0).coerceAtLeast(0.0)
                    io.execute {
                        runCatching {
                            val w = 720
                            val small = if (bmp.width > w) android.graphics.Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
                            java.io.File(store.shotsDir, "${now}_$pkg.jpg").outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, it) }
                        }.onFailure { Log.w(TAG, "存截图失败", it) }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "截图失败：$errorCode")
                }
            })
        }.onFailure { Log.w(TAG, "截图失败", it) }
    }

    /** 每条提醒每天只发一次。 */
    private fun alerts(ctx: Context, host: ZkHost, status: Status, now: Long) {
        val store = host.store
        for (g in status.gates) {
            if (g.done) continue
            val id = g.gate.id
            val name = "${g.gate.dept.label}「${g.gate.title}」"
            when {
                g.phase == GatePhase.OVERDUE && store.firstAlert("over:$id") ->
                    Notifications.alert(ctx, "$name 已超时", "现在起拦截名单里的应用都会被弹回。交了就放行。", "gate/$id")
                g.phase == GatePhase.DUE_SOON && store.firstAlert("soon:$id") ->
                    Notifications.alert(ctx, "$name 还有 ${((g.deadlineAt - now) / 60_000).coerceAtLeast(1)} 分钟截止", g.gate.instruction.take(60), "gate/$id")
                g.blocking && g.phase != GatePhase.OVERDUE && awake(host, status, now) && store.firstAlert("open:$id") ->
                    Notifications.alert(ctx, "$name 开放了", "交了才放行娱乐应用，${DayClock.hhmm(g.gate.deadline)} 截止。", "gate/$id")
            }
        }
        status.silenceDueAt?.let { due ->
            val silent = status.reasons.any { it is Reason.Silence }
            if (silent && store.firstAlert("sil:$due")) {
                Notifications.alert(ctx, "该报到了", "已经 ${store.config.value.silenceHours} 小时没有汇报。报一句现在在做什么。", "report")
            } else if (!silent && due - now in 0..15 * 60_000L && store.firstAlert("silwarn:$due")) {
                Notifications.alert(ctx, "15 分钟内报个到", "太久不汇报会进入严管。", "report")
            }
        }
        if (status.quotaMin > 0) {
            if (status.reasons.any { it is Reason.Quota } && store.firstAlert("quota")) {
                Notifications.alert(ctx, "今天的娱乐额度用完了", "${status.playMin} 分钟。娱乐应用今天不再放行。", null)
            } else if (status.playMin * 10 >= status.quotaMin * 8 && store.firstAlert("quota80")) {
                Notifications.alert(ctx, "娱乐已用 ${status.playMin}/${status.quotaMin} 分钟", "快到上限了。", null)
            }
        }
        store.today.value.emergencies.lastOrNull()?.let { e ->
            if (now >= e.until && now - e.until < 5 * 60_000 && store.firstAlert("emg:${e.until}")) {
                Notifications.alert(ctx, "紧急放行结束", "恢复检查。", null)
            }
        }
    }

    private fun awake(host: ZkHost, status: Status, now: Long): Boolean {
        val c = host.store.config.value
        val date = java.time.LocalDate.parse(status.date)
        val start = DayClock.at(date, c.activeStart, host.store.zone)
        val end = DayClock.at(date, c.activeEnd, host.store.zone).let { if (it <= start) it + 24 * 3600_000L else it }
        return now in start until end
    }
}
