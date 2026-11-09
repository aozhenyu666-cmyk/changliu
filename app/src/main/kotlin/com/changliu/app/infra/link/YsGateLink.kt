package com.changliu.app.infra.link

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import com.changliu.app.infra.a11y.A11yExtensions
import com.yishou.app.YishouApp
import com.yishou.app.gate.GateActivity as YsGateActivity
import com.yishou.app.settings.AppZone
import com.yishou.app.system.ExternalGrace
import com.yishou.app.system.Notifications as YsNotifications
import com.yishou.app.window.WindowClock
import com.yishou.app.window.WindowRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 一手「关注名单」接入唯一无障碍服务（实施计划 §5.2 的第二层）。
 *
 * 原 GateService 的逻辑：前台应用在 watched 里、没有有效放行条、伴读也没登记为材料应用 → 弹入口思考页。
 * 放行条查询是 suspend（Room），所以这里异步查；返回 true 表示"这个名字我管了"，
 * 统一服务不会再叠一层驾驶员的拦截页。
 */
object YsGateLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastLaunch = ConcurrentHashMap<String, Long>()
    private val querying = ConcurrentHashMap.newKeySet<String>()
    private val shortJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var currentPkg: String? = null
    private var systemAllowed: Set<String> = emptySet()
    private var homes: Set<String> = emptySet()
    private var systemAllowedAt = 0L

    fun install() {
        A11yExtensions.addWindowObserver { ctx, pkg, _ -> onWindow(ctx, pkg) }
    }

    /** 无障碍连上时补一手自己的常驻通知。 */
    fun onConnected(ctx: Context) {
        runCatching { YsNotifications.showRunning(ctx) }
    }

    private fun onWindow(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName) return false
        val app = ctx.applicationContext as? YishouApp ?: return false
        runCatching { app.partner.foreground(pkg) }
        val prefs = app.settings.app.value
        val now = System.currentTimeMillis()
        val system = systemAllowed(ctx)
        val isHome = pkg in homes
        if (pkg !in system && pkg !in WindowRules.STATIC_SYSTEM) currentPkg = pkg else if (isHome) currentPkg = null

        if (app.partner.allows(pkg)) return false
        if (prefs.groupOf(pkg) == null && ExternalGrace.active(now)) return false

        // 陪练窗口进行中：先按开局规则决定放行 / 短暂停留 / 立即拉回
        if (prefs.groupOf(pkg) == null && WindowClock.active(now, prefs, AppZone.zone) != null) {
            when (WindowRules.decide(pkg, prefs, system, now)) {
                WindowRules.Decision.ALLOW -> {
                    shortJobs.remove(pkg)?.cancel()
                    return true
                }
                WindowRules.Decision.SHORT -> {
                    scheduleShort(ctx, app, pkg, prefs.windowShortMinutes)
                    return true
                }
                WindowRules.Decision.BLOCK -> {
                    launchGate(ctx, pkg, YsGateActivity.WINDOW_GROUP)
                    return true
                }
            }
        }

        val group = prefs.groupOf(pkg) ?: return false
        if (YsGateActivity.isShowing) return true

        val last = lastLaunch[pkg] ?: 0L
        // 同一个应用打开时会连续发出好几次切换事件，2 秒内只处理一次
        if (now - last < 2_000) return true
        lastLaunch[pkg] = now
        if (!querying.add(pkg)) return true

        scope.launch {
            try {
                val pass = runCatching { app.database.dao().activePass(group, now) }.getOrNull()
                if (pass != null) return@launch
                if (app.partner.allows(pkg)) return@launch
                launchGate(ctx, pkg, group)
            } finally {
                querying.remove(pkg)
            }
        }
        // 已经接管：即使放行条有效，也不要让驾驶员再弹一次自己的门
        return true
    }

    private fun launchGate(ctx: Context, pkg: String, group: String) {
        runCatching { ctx.startActivity(YsGateActivity.intent(ctx, pkg, group)) }
    }

    /** 可以短暂切过去的应用（微信、QQ 等）：到时还停在这里就拉回陪练。 */
    private fun scheduleShort(ctx: Context, app: YishouApp, pkg: String, minutes: Int) {
        if (shortJobs[pkg]?.isActive == true) return
        shortJobs[pkg] = scope.launch {
            try {
                delay(minutes.coerceAtLeast(1) * 60_000L)
                val prefs = app.settings.app.value
                if (currentPkg == pkg && WindowClock.active(System.currentTimeMillis(), prefs, AppZone.zone) != null) {
                    launchGate(ctx, pkg, YsGateActivity.WINDOW_GROUP)
                }
            } finally {
                shortJobs.remove(pkg)
            }
        }
    }

    /** 桌面、输入法、相机、拨号：开局规则永远放行（10 分钟刷新）。 */
    private fun systemAllowed(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (now - systemAllowedAt < 10 * 60_000L && systemAllowed.isNotEmpty()) return systemAllowed
        val pm = ctx.packageManager
        fun handlers(i: Intent) = runCatching {
            pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        homes = handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)).toSet()
        val set = buildSet {
            addAll(homes)
            addAll(handlers(Intent(MediaStore.ACTION_IMAGE_CAPTURE)))
            addAll(handlers(Intent(Intent.ACTION_DIAL)))
            runCatching {
                ctx.getSystemService(InputMethodManager::class.java)
                    ?.enabledInputMethodList?.forEach { add(it.packageName) }
            }
        }
        systemAllowed = set
        systemAllowedAt = now
        return set
    }
}
