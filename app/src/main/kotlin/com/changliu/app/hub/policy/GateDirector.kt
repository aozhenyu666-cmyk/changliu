package com.changliu.app.hub.policy

import android.content.Context
import com.changliu.app.Engine
import com.changliu.app.Intercept
import com.changliu.app.infra.gate.GateActivity
import com.changliu.core.hub.AppMode
import com.changliu.core.hub.GateInput
import com.changliu.core.hub.GatePlan
import com.changliu.core.hub.GatePlanner
import com.changliu.infra.fusion.FusionHooks
import com.zongkong.app.ZkHost

/**
 * 唯一拦截编排器：名单 → 总控定级 → 关卡放行。
 *
 * 以前是三链抢答（ZkGateLink / YsGateLink / Intercept 谁先返回 true 谁弹页），
 * 现在只有一条管道：统一名单命中后，由总控给出信用分与放行上限，[GatePlanner] 算出关卡，
 * 统一交给 [GateActivity]。一层处理完就不再往下叠页。
 */
object GateDirector {

    /** 总控此刻的态度：信用、严管、总控时间、专注锁、放行上限。 */
    data class ZkContext(
        val credit: Int = GatePlanner.NORMAL,
        val strict: Boolean = false,
        val hq: Boolean = false,
        val hold: Boolean = false,
        val passCap: Int = 0,
    )

    fun zkContext(ctx: Context): ZkContext = runCatching {
        val host = ctx.applicationContext as? ZkHost ?: return ZkContext()
        val now = System.currentTimeMillis()
        val store = host.store
        val policy = FusionHooks.zongkongPassPolicy?.invoke()
        ZkContext(
            credit = store.credibility(now),
            strict = store.status(now).strict,
            hq = store.hq.value.active != null,
            hold = policy?.hold == true,
            passCap = policy?.passCap ?: 0,
        )
    }.getOrDefault(ZkContext())

    /** 给 App 内其他页看的一句话：当前防线处于哪一档。 */
    fun currentPlan(ctx: Context, pkg: String): GatePlan? {
        val app = ConstraintStore.load(ctx).apps[pkg] ?: return null
        val z = zkContext(ctx)
        return GatePlanner.plan(
            GateInput(
                mode = app.mode,
                credit = z.credit,
                strict = z.strict,
                hqActive = z.hq,
                hold = z.hold,
                passCap = z.passCap,
                opensToday = opensToday(ctx, pkg),
                baseMinutes = Intercept.minutes(ctx),
            ),
        )
    }

    /**
     * 壳层展示用：假定此刻打开一个受控 App，会走到哪一关、给几分钟。
     * 名单是空的也照样给一档——用户要先看得见防线现在有多紧，才知道该干什么把信用分挣回来。
     */
    fun zone(ctx: Context): GatePlan? {
        val z = zkContext(ctx)
        return GatePlanner.plan(
            GateInput(
                mode = AppMode.GATE,
                credit = z.credit,
                strict = z.strict,
                hqActive = z.hq,
                hold = z.hold,
                passCap = z.passCap,
                opensToday = 1,
                baseMinutes = Intercept.minutes(ctx),
                companionMissed = CompanionDebt.owed(ctx),
            ),
        )
    }

    /**
     * 返回 true 表示这一层已经接管（弹了页或判定无需弹页），外面的旧链不要再拍一次。
     * 名单里没有这个 App 时返回 false，交给驾驶员/一手的老规则。
     */
    fun intercept(ctx: Context, pkg: String): Boolean {
        val app = ConstraintStore.load(ctx).apps[pkg] ?: return false
        if (app.mode == AppMode.ALLOW) return false

        val z = zkContext(ctx)
        val owed = CompanionDebt.owed(ctx)
        val plan = GatePlanner.plan(
            GateInput(
                mode = app.mode,
                credit = z.credit,
                strict = z.strict,
                hqActive = z.hq,
                hold = z.hold,
                passCap = z.passCap,
                opensToday = opensToday(ctx, pkg),
                baseMinutes = Intercept.minutes(ctx),
                companionMissed = owed,
            ),
        ) ?: return false
        // 只有真的给了放行分钟才算把欠账扣掉；封死那一档什么也没给，不能销账
        if (owed && plan.minutes > 0) CompanionDebt.clear(ctx)

        if (GateActivity.visible) return true
        if (!Intercept.claimTrigger(pkg, System.currentTimeMillis())) return true
        runCatching { ctx.startActivity(GateActivity.intent(ctx, pkg, plan)) }
        return true
    }

    private fun opensToday(ctx: Context, pkg: String): Int =
        runCatching { Engine.store.blocking().opensToday[pkg] ?: 0 }.getOrDefault(0)
}
