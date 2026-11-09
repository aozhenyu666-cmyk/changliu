package com.changliu.core.hub

/** 主动唤醒触发源（计划书 §4.4）。 */
enum class WakeTrigger {
    MORNING_UNLOCK,
    ENTERTAINMENT_80,
    ENTERTAINMENT_100,
    ACTION_OVERDUE,
    GLIDING_10MIN,
    LIVE_ENDED,
    GATE_FAIL_3,
    NO_PROGRESS_2H,
    DAILY_REVIEW,
    WEEKLY_REVIEW,
    AI_SCHEDULED,
}

data class WakeState(
    val day: String,
    val count: Int = 0,
    val lastAt: Map<WakeTrigger, Long> = emptyMap(),
)

data class WakeDecision(val allow: Boolean, val reason: String)

object WakeRules {
    const val DAILY_CAP = 12
    const val COOLDOWN_MS = 30 * 60_000L

    /** 夜间只收紧不开口。 */
    fun isNight(hour: Int): Boolean = hour >= 23 || hour < 6

    fun decide(
        trigger: WakeTrigger,
        now: Long,
        state: WakeState,
        today: String,
        hour: Int,
        tokenBudgetLeft: Boolean = true,
    ): WakeDecision {
        val count = if (state.day == today) state.count else 0
        if (count >= DAILY_CAP) return WakeDecision(false, "今天主动唤醒已达上限")
        if (!tokenBudgetLeft) return WakeDecision(false, "今日 token 预算用尽")
        val last = state.lastAt[trigger] ?: 0L
        if (now - last < COOLDOWN_MS) return WakeDecision(false, "同因 30 分钟冷却")
        if (isNight(hour)) return WakeDecision(false, "夜间不开口")
        return WakeDecision(true, "允许唤醒")
    }

    fun afterWake(state: WakeState, trigger: WakeTrigger, now: Long, today: String): WakeState {
        val count = if (state.day == today) state.count else 0
        return WakeState(today, count + 1, state.lastAt + (trigger to now))
    }
}
