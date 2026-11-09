package com.changliu.app.hub.policy

import android.content.Context
import com.changliu.app.ChangliuApp
import com.changliu.app.Engine
import com.changliu.app.Intercept
import com.changliu.core.hub.AppMode
import com.changliu.core.hub.ConstraintApp
import com.changliu.core.hub.ConstraintProfile
import com.yishou.app.YishouApp
import com.zongkong.app.ZkHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 统一约束配置：一份 App 名单 + 模式，首次从三份旧名单取并集，之后单向同步回三边。
 * 旧页面仍能看；统一修改走这里。
 */
object ConstraintStore {
    private const val DIR = "hub"
    private const val FILE = "constraints.json"

    fun load(ctx: Context): ConstraintProfile = runCatching {
        val f = File(File(ctx.filesDir, DIR), FILE)
        if (!f.exists()) return ConstraintProfile()
        val o = JSONObject(f.readText())
        val apps = o.optJSONArray("apps") ?: JSONArray()
        val map = buildMap {
            for (i in 0 until apps.length()) {
                val a = apps.optJSONObject(i) ?: continue
                val pkg = a.optString("pkg").takeIf { it.isNotBlank() } ?: continue
                val mode = runCatching { AppMode.valueOf(a.optString("mode")) }.getOrDefault(AppMode.GATE)
                put(pkg, ConstraintApp(pkg, a.optString("category", "娱乐"), mode))
            }
        }
        ConstraintProfile(map, o.optInt("minChars", 15), o.optBoolean("nightBan", false))
    }.getOrDefault(ConstraintProfile())

    fun save(ctx: Context, profile: ConstraintProfile) {
        runCatching {
            val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
            val apps = JSONArray()
            profile.apps.values.sortedBy { it.pkg }.forEach { a ->
                apps.put(JSONObject().put("pkg", a.pkg).put("category", a.category).put("mode", a.mode.name))
            }
            File(dir, FILE).writeText(
                JSONObject()
                    .put("apps", apps)
                    .put("minChars", profile.minChars)
                    .put("nightBan", profile.nightBan)
                    .toString(2),
            )
        }
    }

    fun setMode(ctx: Context, pkg: String, mode: AppMode): ConstraintProfile {
        val p = load(ctx)
        val app = p.apps[pkg] ?: ConstraintApp(pkg)
        val next = p.copy(apps = p.apps + (pkg to app.copy(mode = mode)))
        save(ctx, next)
        return next
    }

    /** 首次启动：三份名单取并集；已存在条目的模式不覆盖。 */
    fun importFromLegacy(ctx: Context): ConstraintProfile {
        val app = ctx.applicationContext as ChangliuApp
        val existing = load(ctx)
        val apps = existing.apps.toMutableMap()

        val blocking = runCatching { Engine.store.blocking() }.getOrNull()
        if (blocking?.enabled == true) {
            blocking.blocked.forEach { pkg -> apps.putIfAbsent(pkg, ConstraintApp(pkg, "娱乐", AppMode.GATE)) }
        }
        val zk = runCatching { app.store.config.value }.getOrNull()
        zk?.blocked?.forEach { pkg ->
            apps[pkg] = (apps[pkg] ?: ConstraintApp(pkg, "娱乐", AppMode.BLOCK)).copy(mode = AppMode.BLOCK)
        }
        val ys = runCatching { (app as YishouApp).settings.app.value }.getOrNull()
        ys?.watched?.forEach { (pkg, group) ->
            apps.putIfAbsent(pkg, ConstraintApp(pkg, group, AppMode.GATE))
        }

        val profile = ConstraintProfile(
            apps = apps,
            minChars = blocking?.minChars ?: existing.minChars,
            nightBan = blocking?.nightBan ?: existing.nightBan,
        )
        save(ctx, profile)
        return profile
    }

    /** 把统一 Profile 同步回三边旧配置（旧页面仍可看）。 */
    fun syncToLegacy(ctx: Context, profile: ConstraintProfile) {
        val app = ctx.applicationContext as ChangliuApp
        val gateApps = profile.apps.values.filter { it.mode == AppMode.GATE }.map { it.pkg }
        val blockApps = profile.apps.values.filter { it.mode == AppMode.BLOCK }.map { it.pkg }

        // 驾驶员：GATE + BLOCK 都交给它拦
        runCatching {
            val b = Engine.store.blocking()
            Engine.store.saveBlocking(
                b.copy(
                    enabled = gateApps.isNotEmpty() || blockApps.isNotEmpty(),
                    blocked = (gateApps + blockApps).distinct(),
                    minChars = profile.minChars.coerceIn(15, 200),
                    nightBan = profile.nightBan,
                ),
            )
            Intercept.invalidate()
        }
        // 总控：只同步"直接拦"
        runCatching { app.store.updateConfig { it.copy(blocked = blockApps.toSet()) } }
        // 一手：关注名单只放"答题放行"
        runCatching {
            val y = app as YishouApp
            y.settings.updateApp { prefs ->
                val next = prefs.watched.toMutableMap()
                gateApps.forEach { pkg -> next.putIfAbsent(pkg, "娱乐") }
                // 不再由统一 Profile 管的关注项保留用户原设置，不主动删
                prefs.copy(watched = next)
            }
        }
    }

    fun summary(ctx: Context): String {
        val p = load(ctx)
        val n = p.apps.size
        val block = p.apps.values.count { it.mode == AppMode.BLOCK }
        val gate = p.apps.values.count { it.mode == AppMode.GATE }
        val observe = p.apps.values.count { it.mode == AppMode.OBSERVE }
        return if (n == 0) "还没导入名单" else "统一 $n 个 · 拦 $block · 答题 $gate · 观察 $observe"
    }
}
