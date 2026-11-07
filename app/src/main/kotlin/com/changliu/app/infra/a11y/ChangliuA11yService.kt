package com.changliu.app.infra.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.changliu.app.A11yGuard
import com.changliu.app.AwarenessRuntime
import com.changliu.app.Engine
import com.changliu.app.brain.BrainRuntime
import com.changliu.app.infra.keepalive.KeepAliveService
import com.changliu.app.Intercept
import com.changliu.app.ObservationShots
import com.changliu.app.UsageMonitor
import com.changliu.app.WatchdogJob
import com.changliu.app.infra.gate.GateActivity
import com.changliu.app.infra.gate.GateCover
import com.changliu.app.infra.gate.GateTrace
import com.changliu.core.Verdict

/**
 * 长流唯一无障碍服务：合并驾驶员 WatchService + 一手 GateService + 总控 GuardService。
 *
 * 一个服务的好处：国产 ROM 一键清理只掉一个，看门狗也只需要写回一个组件。
 *
 *   事件主干（驾驶员的复核逻辑，最严谨）：
 *     只看 WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED → 事件包名用 rootInActiveWindow 复核
 *     → 交决策链（总控 → 一手 → 驾驶员）→ 要拦：HOME 送走 → 100ms 后拉起唯一 GateActivity
 *     → 系统静默拦后台弹出时，1.2 秒后补通知 + GateCover 遮罩兜底
 *   每 5 秒复查一次：放行到期后用户一直停在 App 里也要重新拦
 *   20 秒周期 tick：给总控 Watch/accountPlay、一手到期复查、健康自检留钩子
 *
 * 只读包名和窗口类名，不读屏幕文字。
 */
class ChangliuA11yService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastBlockedPkg = ""
    private var lastSeenPkg = ""
    private var lastActionAt = 0L
    private var lastBeatAt = 0L
    private var lastCheckedAt = 0L

    private val cover by lazy { GateCover(this) }

    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            // 总控 Watch.tick / 一手到期复查 / 健康自检都在这里挂
            A11yExtensions.tick(this@ChangliuA11yService, now)
            // 五态估计：20 秒重估一次；语气心跳也在这里抽
            BrainRuntime.tick(this@ChangliuA11yService, now, AwarenessRuntime.screenAvailable())
            heartbeat()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        running = true
        Engine.watchConnected = true
        ObservationShots.connected(this)
        GateTrace.init(this)
        val initial = runCatching { rootInActiveWindow }.getOrNull()
        Engine.onForeground(this, initial?.packageName?.toString(), null)
        @Suppress("DEPRECATION") runCatching { initial?.recycle() }
        A11yGuard.clearDownNotice(this)
        WatchdogJob.schedule(this)
        KeepAliveService.sync(this)
        // 各业务模块自己的"我在运行"提示 / 前台跟踪
        runCatching { com.changliu.app.infra.link.YsGateLink.onConnected(this) }
        runCatching { com.yishou.app.gate.GateService.markConnected(true) }
        runCatching { com.zongkong.app.guard.GuardService.markRunning(true) }

        main.removeCallbacks(ticker)
        main.postDelayed(ticker, TICK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        heartbeat()
        if (event.eventType and TARGET_EVENTS == 0) return
        if (!AwarenessRuntime.screenAvailable()) { Engine.onForeground(this, null, null); return }
        val pkg = event.packageName?.toString() ?: return

        // 事件包名和当前活动窗口不一致时不当真（快速切换、输入法浮层都会产生这种事件）
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val active = root?.packageName?.toString()
        @Suppress("DEPRECATION") runCatching { root?.recycle() }
        if (active.isNullOrBlank() || active != pkg) { Engine.onForeground(this, null, null); return }

        // 回到我们自己的页 / 别的应用：遮罩立刻收起，不常驻
        if (active == packageName || cover.pkg?.let { it != active } == true) cover.hide()

        // 同一个 App 内部的内容变化不重复判断；但每 5 秒复查一次（放行到期要重拦）
        Engine.onForeground(this, active, event.className?.toString())
        runCatching { com.yishou.app.gate.GateService.markForeground(active) }
        // 长流引擎：前台/娱乐信号（两份拦截名单取并集）
        runCatching {
            val entertainment =
                Intercept.rules(this).blocked.contains(active) ||
                    (applicationContext as com.zongkong.app.ZkHost).store.config.value.blocked.contains(active)
            BrainRuntime.onApp(this, active, entertainment, interactive = true, now = System.currentTimeMillis())
        }
        val now = SystemClock.uptimeMillis()
        if (active == lastSeenPkg && now - lastCheckedAt < RECHECK_MS) return
        lastSeenPkg = active
        lastCheckedAt = now
        // 先走总控 → 一手的决策链；任一层自己弹了页就不再让驾驶员兜底
        val handled = A11yExtensions.onWindow(this, active, event.className?.toString())
        if (!handled && active != packageName) maybeBlock(active)
    }

    private fun maybeBlock(pkg: String) {
        if (GateActivity.visible) return
        val wall = System.currentTimeMillis()
        val verdict = A11yExtensions.decide(this, pkg, wall) ?: Intercept.decide(this, pkg, wall)
        if (verdict != Verdict.BLOCK) return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockedPkg && now - lastActionAt < COOLDOWN_MS) return
        if (!Intercept.claimTrigger(pkg, wall)) return
        lastBlockedPkg = pkg
        lastActionAt = now
        // 先把 App 送走，再从桌面拉起拦截页：顺序反了会被系统的"后台启动"限制挡住，或者盖不上去
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({
            val ok = runCatching { startActivity(GateActivity.intent(this, pkg)) }.isSuccess
            lastSeenPkg = "" // 拦完清掉，用户再打开时要重新判断
            lastCheckedAt = 0L
            if (!ok) lastBlockedPkg = "" // 拉不起来就允许下一次重试
            // 后台启动被系统静默丢弃时不会抛异常，1.2 秒后还没看到拦截页就补通知 + 遮罩
            main.postDelayed({
                if (!GateActivity.visible) {
                    UsageMonitor.notifyBlocked(this, pkg)
                    cover.show(pkg, label(pkg)) {
                        runCatching { startActivity(GateActivity.intent(this, pkg)) }
                    }
                }
            }, 1_200L)
        }, LAND_DELAY_MS)
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("它")

    /** 每 30 秒一次：确认看门狗还排着、常驻守护还活着（用户清过调度的话会被清掉）。 */
    private fun heartbeat() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBeatAt < 30_000L) return
        lastBeatAt = now
        WatchdogJob.schedule(this)
        KeepAliveService.sync(this)
    }

    override fun onInterrupt() {
        Engine.onForeground(this, null, null)
        lastSeenPkg = ""
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        running = false
        Engine.watchConnected = false
        ObservationShots.connected(null)
        Engine.onForeground(this, null, null)
        runCatching { com.yishou.app.gate.GateService.markConnected(false) }
        runCatching { com.yishou.app.gate.GateService.markForeground(null) }
        runCatching { com.zongkong.app.guard.GuardService.markRunning(false) }
        main.removeCallbacksAndMessages(null)
        cover.hide()
        super.onDestroy()
    }

    companion object {
        /** 唯一无障碍是否真的连上（给总控的权限状态桥用）。 */
        @Volatile
        var running = false
            private set

        private const val TARGET_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        const val LAND_DELAY_MS = 100L
        const val COOLDOWN_MS = 1_000L
        const val RECHECK_MS = 5_000L
        const val TICK_MS = 20_000L
    }
}
