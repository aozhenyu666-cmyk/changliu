package com.changliu.app.hub.policy

import android.content.Context

/**
 * 已并入 [GateDirector]：统一名单 → 总控定级 → 关卡放行只有一条管道。
 * 保留这个入口只为旧调用点不炸，逻辑不再在这里。
 */
@Deprecated("改用 GateDirector.intercept")
object HubPolicy {
    fun intercept(ctx: Context, pkg: String): Boolean = GateDirector.intercept(ctx, pkg)
}
