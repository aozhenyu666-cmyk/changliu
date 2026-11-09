package com.changliu.app.infra.link

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager
import android.view.inputmethod.InputMethodManager
import com.changliu.app.infra.a11y.A11yExtensions
import com.zongkong.app.ZkHost
import com.zongkong.app.guard.BlockActivity
import com.zongkong.app.guard.GateActivity as ZkGateActivity
import com.zongkong.app.guard.SentinelClient
import com.zongkong.app.ui.MainActivity as ZkMainActivity
import com.zongkong.core.DayOps
import com.zongkong.core.WatchRule
import com.zongkong.core.hq.Hq
import com.zongkong.core.hq.HqTrigger

/**
 * 总控「总控时间 / 严管 / 低屏模式」接入唯一无障碍服务（实施计划 §5.2 的第一层）。
 *
 * 从原 GuardService 搬来判断逻辑，去掉它自己的 a11y 生命周期、哨兵注册和截图/Notion 后台任务：
 *   总控时间（hq）白名单外 → 弹回总控首页 hq 页
 *   严管（strict）拦截名单 → 弹 BlockActivity
 *   低屏/打开就答题 → 弹总控 GateActivity（放行条写在同一个 watch prefs 里）
 * 返回 true 表示这一层已经弹了页，唯一服务不再叠加驾驶员拦截。
 */
object ZkGateLink {

    private const val PREFS = "watch"
    private const val GRANT = "grant_"
    private const val GATE = "lastGate_"
    private const val DEDUPE_MS = 3_000L
    private const val TICK_SEC_CAP = 2 * 60 + 60

    @Volatile private var fgPkg: String? = null
    @Volatile private var fgSince = 0L
    @Volatile private var lastBounce = 0L
    @Volatile private var lastGateAt = 0L

    @Volatile private var trackedPkg: String? = null
    @Volatile private var trackedSince = 0L

    private var overlays: Set<String> = emptySet()
    private var overlaysAt = 0L

    fun install() {
        A11yExtensions.addWindowObserver { ctx, pkg, _ -> onWindow(ctx, pkg) }
        A11yExtensions.addTicker { ctx, now -> tick(ctx, now) }
    }

    private fun host(ctx: Context): ZkHost? = ctx.applicationContext as? ZkHost

    private fun onWindow(ctx: Context, pkg: String): Boolean {
        val host = host(ctx) ?: return false
        val store = host.store
        val now = System.currentTimeMillis()
        if (pkg !in overlays(ctx) && pkg != ctx.packageName) {
            if (pkg != fgPkg) {
                accountPlay(ctx, now)
                fgPkg = pkg
                fgSince = now
                store.logSwitch(now, pkg)
            }
        }
        // 哨兵的解除闸门生效中（走过冷静期 + 二次手写核验）：总控让路
        if (SentinelClient.released(now)) return false
        if (lockdown(ctx, host, pkg, now)) return true
        if (enforce(ctx, host, pkg, now)) return true
        return watch(ctx, host, pkg, now)
    }

    private fun tick(ctx: Context, now: Long) {
        val host = host(ctx) ?: return
        host.store.heartbeat = now
        if (interactive(ctx)) accountPlay(ctx, now) else fgSince = now
        trackedPkg?.let { watch(ctx, host, it, now) }
        // 没有窗口切换也要管：总控时间刚开始时你正停在别的应用里 → 拉回来；严管开始时正开着娱乐应用 → 弹回
        val p = fgPkg ?: return
        if (SentinelClient.released(now) || !interactive(ctx)) return
        val status = runCatching { host.store.status(now) }.getOrNull() ?: return
        if (host.store.hq.value.active == null && !status.strict) return
        // 和窗口事件同一条路：统一名单里的应用先交给单管道（只弹一页），名单外的才走总控原来的弹回
        if (runCatching { com.changliu.app.hub.policy.GateDirector.intercept(ctx, p) }.getOrDefault(false)) return
        if (host.store.hq.value.active != null) lockdown(ctx, host, p, now) else enforce(ctx, host, p, now)
    }

    /** 总控时间：白名单以外的应用（包括桌面）一出现就回到总控时间。 */
    private fun lockdown(ctx: Context, host: ZkHost, pkg: String, now: Long): Boolean {
        if (pkg == ctx.packageName || !interactive(ctx)) return false
        val store = host.store
        val cfg = store.config.value.hq
        if (store.config.value.pausedUntil > now) return false
        var session = store.hq.value.active
        if (session == null && store.absence(now) == Hq.Absence.FORCE &&
            !Hq.allows(pkg, ctx.packageName, cfg, overlays(ctx))
        ) {
            session = host.actions.hq.start(HqTrigger.ESCALATION)
        }
        if (session == null) return false
        if (Hq.allows(pkg, ctx.packageName, cfg, overlays(ctx))) return false
        if (now - lastBounce < 1_500) return true
        lastBounce = now
        store.updateToday(now) { DayOps.bounce(it) }
        runCatching {
            ctx.startActivity(
                Intent(ctx, ZkMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(ZkMainActivity.EXTRA_ROUTE, "hq"),
            )
        }
        return true
    }

    /** 严管：拦截名单里的应用直接弹回。 */
    private fun enforce(ctx: Context, host: ZkHost, pkg: String, now: Long): Boolean {
        if (pkg == ctx.packageName) return false
        val store = host.store
        if (pkg !in store.config.value.blocked) return false
        if (!interactive(ctx)) return false
        val status = store.status(now)
        if (!status.strict) return false
        if (now - lastBounce < 1_500) return true
        lastBounce = now
        store.updateToday(now) { DayOps.bounce(it) }
        runCatching { ctx.startActivity(BlockActivity.intent(ctx, pkg)) }
        return true
    }

    /** 低屏模式 / 打开就答题：连续用满时限或没有放行条时弹总控的做题页。 */
    private fun watch(ctx: Context, host: ZkHost, pkg: String, now: Long): Boolean {
        val rule = ruleFor(host, pkg)
        if (rule == null) {
            trackedPkg = null
            trackedSince = 0L
            return false
        }
        if (trackedPkg != pkg) {
            trackedPkg = pkg
            trackedSince = now
        }
        val grant = prefs(ctx).getLong(GRANT + pkg, 0L)
        val due = if (rule.onOpen) grant else maxOf(trackedSince + rule.maxMinutes * 60_000L, grant)
        if (now < due) return false
        val lastFromPrefs = runCatching { prefs(ctx).getLong(GATE + pkg, 0L) }.getOrDefault(0L)
        if (now - maxOf(lastGateAt, lastFromPrefs) < DEDUPE_MS) return true
        lastGateAt = now
        trackedSince = now
        runCatching { prefs(ctx).edit().putLong(GATE + pkg, now).apply() }
        runCatching { ctx.startActivity(ZkGateActivity.intent(ctx, pkg)) }
        return true
    }

    /** 这个应用归谁管：自己加的低屏规则，或拦截名单里的娱乐应用（打开就答题）。 */
    private fun ruleFor(host: ZkHost, pkg: String): WatchRule? {
        val config = host.store.config.value
        val rule = config.watch.ruleFor(pkg, config.blocked) ?: return null
        // 严管时拦截名单里的应用由 BlockActivity 管，这里不重复弹
        if (pkg in config.blocked && host.store.status().strict) return null
        return rule
    }

    /** 把前台娱乐应用这段时间记进今天的娱乐时长。 */
    private fun accountPlay(ctx: Context, now: Long) {
        val host = host(ctx) ?: return
        val p = fgPkg
        if (p != null && p in host.store.config.value.blocked && interactive(ctx)) {
            val sec = (now - fgSince) / 1000
            if (sec in 1..TICK_SEC_CAP) host.store.updateToday(now) { DayOps.addPlay(it, p, sec) }
        }
        fgSince = now
    }

    private fun overlays(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (now - overlaysAt < 10 * 60_000 && overlays.isNotEmpty()) return overlays
        overlays = buildSet {
            add("com.android.systemui")
            runCatching {
                ctx.getSystemService(InputMethodManager::class.java)
                    ?.enabledInputMethodList?.forEach { add(it.packageName) }
            }
        }
        overlaysAt = now
        return overlays
    }

    private fun interactive(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive != false

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
