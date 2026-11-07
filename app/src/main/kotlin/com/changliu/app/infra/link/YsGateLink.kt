package com.changliu.app.infra.link

import android.content.Context
import com.changliu.app.infra.a11y.A11yExtensions
import com.yishou.app.YishouApp
import com.yishou.app.gate.GateActivity as YsGateActivity
import com.yishou.app.system.Notifications as YsNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        val group = app.settings.app.value.groupOf(pkg) ?: return false
        if (app.partner.allows(pkg)) return false
        if (YsGateActivity.isShowing) return true

        val now = System.currentTimeMillis()
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
                runCatching { ctx.startActivity(YsGateActivity.intent(ctx, pkg, group)) }
            } finally {
                querying.remove(pkg)
            }
        }
        // 已经接管：即使放行条有效，也不要让驾驶员再弹一次自己的门
        return true
    }
}
