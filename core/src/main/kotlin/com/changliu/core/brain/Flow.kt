package com.changliu.core.brain

/**
 * 长流五态估计（设计方案 §4.2）。
 *
 * 证据 = 回答质量 × 响应延迟 × 应用类别（娱乐持续时长）× 屏幕/交互状态。
 * 纯函数，时钟和信号都由调用方注入，便于单测。
 */
enum class FlowState { ONLINE, GLIDING, IMMERSED, SILENT, OFFLINE }

fun FlowState.label(): String = when (this) {
    FlowState.ONLINE -> "思考在线"
    FlowState.GLIDING -> "滑翔"
    FlowState.IMMERSED -> "沉浸"
    FlowState.SILENT -> "沉默"
    FlowState.OFFLINE -> "离线"
}

data class FlowEvidence(
    /** 屏幕亮且解锁 */
    val interactive: Boolean = true,
    /** 连续停留在娱乐应用的时长 */
    val entertainmentMs: Long = 0L,
    /** 距离上一次作答的时长 */
    val sinceLastAnswerMs: Long = Long.MAX_VALUE,
    /** 上一次回答是否有依据；null = 还没有回答 */
    val lastAnswerGrounded: Boolean? = null,
    /** 上一次回答用了多久 */
    val lastAnswerDelayMs: Long? = null,
    /** 距离上一次任何交互的时长 */
    val silenceMs: Long = 0L,
)

data class FlowEstimate(
    val state: FlowState,
    val confidence: Float,
    val reasons: List<String>,
)

object FlowEstimator {
    const val SILENT_MS = 10 * 60_000L
    const val IMMERSED_MS = 10 * 60_000L
    const val FAST_ANSWER_MS = 15_000L

    fun estimate(e: FlowEvidence): FlowEstimate {
        if (!e.interactive) {
            return FlowEstimate(FlowState.OFFLINE, 1f, listOf("熄屏或锁屏"))
        }
        if (e.silenceMs >= SILENT_MS) {
            return FlowEstimate(FlowState.SILENT, 0.8f, listOf("超过 ${SILENT_MS / 60_000} 分钟没有交互"))
        }
        val grounded = e.lastAnswerGrounded
        val fast = e.lastAnswerDelayMs?.let { it in 0..FAST_ANSWER_MS } == true

        if (e.entertainmentMs >= IMMERSED_MS && grounded != true) {
            return FlowEstimate(
                FlowState.IMMERSED,
                0.85f,
                listOf("娱乐已持续 ${e.entertainmentMs / 60_000} 分钟", "最近没有有依据的输出"),
            )
        }
        if (grounded == false) {
            return FlowEstimate(
                FlowState.GLIDING,
                if (fast) 0.8f else 0.65f,
                listOf("上一答没有依据") + if (fast) listOf("而且回答很快") else emptyList(),
            )
        }
        if (grounded == null && e.entertainmentMs in 1..(IMMERSED_MS - 1)) {
            return FlowEstimate(FlowState.GLIDING, 0.55f, listOf("刚切进娱乐，还没有输出"))
        }
        return FlowEstimate(FlowState.ONLINE, if (grounded == true) 0.85f else 0.5f, listOf("没有恶化证据"))
    }
}
