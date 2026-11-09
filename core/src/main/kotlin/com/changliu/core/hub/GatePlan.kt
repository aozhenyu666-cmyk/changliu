package com.changliu.core.hub

/**
 * 拦截关卡：一次拦截要过几道、放行多久，全部由这里算，不在页面里拍脑袋。
 *
 * 单向棘轮：宽松只来自信用分回升（时间衰减），收紧来自总控口令 / 信用分 / 当日重开次数。
 * 任何一档都只会被更严的输入推翻，不存在"聊两句就放宽"的路径。
 */
enum class GateLevel(val n: Int, val label: String) {
    /** L1：主线断点快答，答一句依据就放行。 */
    DRILL(1, "断点快答"),

    /** L2：高频专项闯关，要连过几轮。 */
    TRAIN(2, "专项闯关"),

    /** L3：证据核验，先交进展、答不出就上陪练。 */
    EVIDENCE(3, "证据核验"),

    /** L4：绝对封锁，没有输入框。 */
    LOCK(4, "绝对封锁"),
}

/** 算一道门需要的全部输入。调用方只负责取数，不给意见。 */
data class GateInput(
    /** 统一约束里这个 App 的模式。 */
    val mode: AppMode = AppMode.GATE,
    /** 总控 14 天滚动信用分（起点 70）。 */
    val credit: Int = 70,
    /** 总控严管中。 */
    val strict: Boolean = false,
    /** 总控时间进行中。 */
    val hqActive: Boolean = false,
    /** 总控专注锁 / 落后：这一轮不给放行。 */
    val hold: Boolean = false,
    /** 总控允许的最大放行分钟；0 = 不限。 */
    val passCap: Int = 0,
    /** 今天这个 App 已经放行过几次。 */
    val opensToday: Int = 0,
    /** 驾驶员设置里的默认放行分钟。 */
    val baseMinutes: Int = 10,
    /** 上次 L2/L3 放行说了要挂刷屏伴读，但没挂上（用户没同意看屏）。 */
    val companionMissed: Boolean = false,
)

/** 一道门的结论。 */
data class GatePlan(
    val level: GateLevel,
    val gate: GateMode,
    /** 要连续答对几轮才放行；LOCK 为 0。 */
    val rounds: Int,
    /** 放行分钟；LOCK 为 0。 */
    val minutes: Int,
    val reason: String,
    /** 放行后必须挂刷屏伴读（只看不管 = 等于没拦）。 */
    val companion: Boolean = false,
) {
    val locked: Boolean get() = level == GateLevel.LOCK
}

object GatePlanner {

    /** 低于这个分，直接封死，连答题入口都不给。 */
    const val FLOOR = 40

    /** 观察警告期上界。 */
    const val WARY = 60

    /** 正常信任上界。 */
    const val NORMAL = 70

    /** 单次放行的天花板：再宽松的档位也不会超过它。 */
    const val MAX_MINUTES = 10

    /** 上次放行没挂上伴读，这次从分钟里扣回来。 */
    const val COMPANION_PENALTY = 3

    fun plan(i: GateInput): GatePlan? {
        if (i.mode == AppMode.ALLOW) return null

        // 收紧方向：任一条命中就封死
        if (i.hqActive) return lock(GateMode.HQ, "总控时间里，不开放")
        if (i.hold) return lock(GateMode.BLOCK, "总控专注锁：先做完手上的事")
        if (i.strict) return lock(GateMode.BLOCK, "严管中")
        if (i.mode == AppMode.BLOCK) return lock(GateMode.BLOCK, "这个 App 统一设为「直接拦」")
        if (i.credit < FLOOR) return lock(GateMode.BLOCK, "信用 ${i.credit}：先回桌面")

        val cap = if (i.passCap > 0) i.passCap else MAX_MINUTES
        // 欠账只扣放行分钟，不改关卡、不封死：想少扣就这次把伴读挂上
        val debit = if (i.companionMissed) COMPANION_PENALTY else 0
        val tail = if (debit > 0) " · 上次没挂伴读 −${debit}分" else ""
        fun mins(m: Int) = (minOf(minOf(m, cap), MAX_MINUTES) - debit).coerceAtLeast(1)

        return when {
            i.credit < WARY || i.mode == AppMode.OBSERVE ->
                GatePlan(GateLevel.EVIDENCE, GateMode.GATE, 1, mins(3), "信用 ${i.credit}：先交证据$tail", companion = true)

            i.credit < NORMAL || i.opensToday >= 2 ->
                GatePlan(GateLevel.TRAIN, GateMode.GATE, 2, mins(5), "信用 ${i.credit}：连过两轮$tail", companion = true)

            else -> GatePlan(
                GateLevel.DRILL,
                GateMode.GATE,
                1,
                mins(i.baseMinutes.coerceIn(1, MAX_MINUTES)),
                "信用 ${i.credit}$tail",
            )
        }
    }

    private fun lock(gate: GateMode, reason: String) =
        GatePlan(GateLevel.LOCK, gate, rounds = 0, minutes = 0, reason = reason)
}
