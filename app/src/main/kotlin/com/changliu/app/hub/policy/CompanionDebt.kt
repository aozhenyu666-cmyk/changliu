package com.changliu.app.hub.policy

import android.content.Context

/**
 * 伴读欠账：L2/L3 放行时说好要挂刷屏伴读，用户没同意看屏就进去了。
 *
 * 记一笔，下次放行按 [com.changliu.core.hub.GatePlanner.COMPANION_PENALTY] 扣回分钟。
 * 不做成"拒绝就不给进"：看屏授权是系统级敏感权限，用户有权拒绝；
 * 但拒绝也不该白拿——只放不管是这个系统最怕的漏洞。
 *
 * 一次欠账只扣一次；设备重启/隔夜超过 12 小时不再追究。
 */
object CompanionDebt {

    private const val PREFS = "hub_gate"
    private const val KEY_AT = "companion_asked_at"
    /** 超过这个时间的欠账不再追究。 */
    private const val TTL = 12 * 60 * 60 * 1000L

    /** 放行时说要挂伴读，先立字据。 */
    fun mark(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /** 还欠不欠。伴读已经挂上（服务在跑）就当补交了，顺手销账。 */
    fun owed(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        if (runCatching { com.yishou.app.scroll.ScrollService.running.value }.getOrDefault(false)) {
            clear(ctx)
            return false
        }
        val at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_AT, 0L)
        return at > 0 && now - at <= TTL
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_AT).apply()
    }
}
