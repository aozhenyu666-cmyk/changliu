package com.changliu.core.hub

/** 每个 App 的约束模式；合并时按"最严者胜"排序。 */
enum class AppMode { ALLOW, OBSERVE, GATE, BLOCK }

enum class GateMode { GATE, BLOCK, HQ, YS_ENTRY }

data class ConstraintApp(
    val pkg: String,
    val category: String = "娱乐",
    val mode: AppMode = AppMode.GATE,
)

data class ConstraintProfile(
    val apps: Map<String, ConstraintApp> = emptyMap(),
    val minChars: Int = 15,
    val nightBan: Boolean = false,
)

data class ConstraintOpinion(val mode: AppMode, val reason: String)

/** 三边各给意见，取最严：BLOCK > GATE > OBSERVE > ALLOW。 */
object PolicyEngine {
    fun rank(mode: AppMode): Int = when (mode) {
        AppMode.ALLOW -> 0
        AppMode.OBSERVE -> 1
        AppMode.GATE -> 2
        AppMode.BLOCK -> 3
    }

    fun decide(opinions: List<ConstraintOpinion>): ConstraintOpinion? =
        opinions.maxByOrNull { rank(it.mode) }

    /** 统一拦截页该用哪个模式；返回 null 表示不拦。 */
    fun gateModeFor(mode: AppMode, zkStrict: Boolean, hqActive: Boolean): GateMode? = when {
        hqActive -> GateMode.HQ
        mode == AppMode.BLOCK || zkStrict -> GateMode.BLOCK
        mode == AppMode.GATE -> GateMode.GATE
        else -> null
    }

    /** 夜间只收紧不开口：夜间的 OBSERVE/GATE 升为 BLOCK？不，夜间仍走 GATE，只是不主动搭话。 */
    fun nightMaySpeak(night: Boolean): Boolean = !night
}
