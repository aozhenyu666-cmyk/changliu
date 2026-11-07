package com.changliu.core.brain

import kotlin.random.Random

/**
 * mood 引擎（设计方案 §6.2）：加权 + 大随机 + 突发尖峰 + 短暂持续。
 *
 * 态度随机，执法确定：mood 只决定"怎么说话"，不改变拦截/锁定是否触发。
 */
enum class Mood { WARM, TEASE, COLD, STRICT, WILD }

fun Mood.label(): String = when (this) {
    Mood.WARM -> "温和"
    Mood.TEASE -> "调侃"
    Mood.COLD -> "冷淡"
    Mood.STRICT -> "严厉"
    Mood.WILD -> "暴躁"
}

/** 给模型的一句话语气提示。 */
fun Mood.clause(): String = when (this) {
    Mood.WARM -> "今天语气可以暖一点，像熟人说话。"
    Mood.TEASE -> "可以懒洋洋地调侃，但别刻薄。"
    Mood.COLD -> "语气收着，简短，不哄人。"
    Mood.STRICT -> "语气严厉，直接点破，不留情面。"
    Mood.WILD -> "今天有点暴躁：句子短、冲一点；只对事，不骂人。"
}

data class MoodPick(val mood: Mood, val heldUntil: Long, val spike: Boolean)

class MoodEngine(
    private val random: Random = Random.Default,
    private val holdMs: LongRange = 3 * 60_000L..8 * 60_000L,
    private val spikeChance: Double = 0.06,
) {
    private var current: Mood? = null
    private var until = 0L

    /** 当前语气是否还在持续期内（不重新抽取）。 */
    fun current(now: Long): Mood? = current?.takeIf { now < until }

    /** 权重被复盘调整后立刻重抽一次，不等当前语气自然到期。 */
    @Synchronized
    fun reset() {
        current = null
        until = 0L
    }

    fun pick(
        now: Long,
        credit: Int = 70,
        state: FlowState = FlowState.ONLINE,
        extra: Map<Mood, Double> = emptyMap(),
    ): MoodPick {
        current(now)?.let { return MoodPick(it, until, false) }

        val spike = random.nextDouble() < spikeChance
        val mood = if (spike) {
            if (random.nextBoolean()) Mood.WILD else Mood.WARM
        } else {
            weightedPick(weights(credit, state, extra))
        }
        // 尖峰短一些（一阵脾气），普通语气持续更久，形成情绪连贯
        val hold = if (spike) {
            val lo = holdMs.first / 2
            random.nextLong(lo, holdMs.first + 1)
        } else {
            random.nextLong(holdMs.first, holdMs.last + 1)
        }
        current = mood
        until = now + hold
        return MoodPick(mood, until, spike)
    }

    /** 基线权重：信用低、滑翔/沉浸 → 严与冷的权重更高；[extra] 是周复盘自提的临时增量。 */
    fun weights(credit: Int, state: FlowState, extra: Map<Mood, Double> = emptyMap()): Map<Mood, Double> {
        val w = mutableMapOf(
            Mood.WARM to 26.0,
            Mood.TEASE to 20.0,
            Mood.COLD to 14.0,
            Mood.STRICT to 26.0,
            Mood.WILD to 14.0,
        )
        when {
            credit >= 80 -> w[Mood.WARM] = w.getValue(Mood.WARM) + 12
            credit < 40 -> {
                w[Mood.STRICT] = w.getValue(Mood.STRICT) + 18
                w[Mood.COLD] = w.getValue(Mood.COLD) + 8
                w[Mood.WILD] = w.getValue(Mood.WILD) + 6
            }
            credit < 60 -> {
                w[Mood.STRICT] = w.getValue(Mood.STRICT) + 8
                w[Mood.COLD] = w.getValue(Mood.COLD) + 4
            }
        }
        when (state) {
            FlowState.GLIDING -> {
                w[Mood.STRICT] = w.getValue(Mood.STRICT) + 16
                w[Mood.WARM] = (w.getValue(Mood.WARM) - 6).coerceAtLeast(4.0)
            }
            FlowState.IMMERSED -> {
                w[Mood.STRICT] = w.getValue(Mood.STRICT) + 20
                w[Mood.COLD] = w.getValue(Mood.COLD) + 6
            }
            FlowState.SILENT -> {
                w[Mood.WARM] = w.getValue(Mood.WARM) + 8
                w[Mood.TEASE] = w.getValue(Mood.TEASE) + 4
            }
            FlowState.ONLINE -> w[Mood.WARM] = w.getValue(Mood.WARM) + 8
            FlowState.OFFLINE -> Unit
        }
        extra.forEach { (mood, delta) -> w[mood] = (w[mood] ?: 0.0) + delta }
        return w.mapValues { (_, v) -> v.coerceAtLeast(2.0) }
    }

    /**
     * 大随机分量：在权重上乘 [0.4, 1.8] 的随机系数再抽，
     * 让"今天怎么说话"不能被用户预测，但基线仍随信用/状态移动。
     */
    fun weightedPick(w: Map<Mood, Double>): Mood {
        val mixed = w.mapValues { (_, v) -> v * (0.4 + random.nextDouble() * 1.4) }
        val total = mixed.values.sum()
        var r = random.nextDouble() * total
        for ((mood, weight) in mixed) {
            r -= weight
            if (r <= 0.0) return mood
        }
        return mixed.maxByOrNull { it.value }?.key ?: Mood.WARM
    }
}
