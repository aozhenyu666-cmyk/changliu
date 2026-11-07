package com.changliu.core.brain

/** 主动开口的触发源（设计方案 §5.1/§5.4）。 */
enum class Trigger { NONE, HEARTBEAT, ONLINE_TO_GLIDING, SILENT, MORNING_UNLOCK }

/** 干预分级（设计方案 §7.3）；主动搭话默认 L0，沉默降阻用 L1。 */
enum class Level { L0, L1, L2, L3 }

data class ProactiveInput(
    val now: Long,
    val trigger: Trigger,
    val state: FlowState,
    /** 刚连答几句 / 正在输入：心跳不要插嘴。 */
    val pressureHigh: Boolean = false,
    val lastActAt: Long = 0L,
    val lastSameCauseAt: Long = 0L,
    val morningAskedToday: Boolean = false,
)

data class ProactiveDecision(val speak: Boolean, val level: Level, val reason: String)

/**
 * 调度纪律（借 Mika 账本 + Hermes 克制）：
 * 同因 90 秒冷却、总冷却 90 秒、交互压力时不插嘴、沉浸交给强制页、离线不说话。
 */
object ProactivePolicy {
    const val COOLDOWN_MS = 90_000L

    fun decide(input: ProactiveInput): ProactiveDecision {
        if (input.trigger == Trigger.NONE) return ProactiveDecision(false, Level.L0, "没有触发")
        if (input.state == FlowState.OFFLINE) return ProactiveDecision(false, Level.L0, "离线")
        if (input.state == FlowState.IMMERSED) return ProactiveDecision(false, Level.L0, "沉浸交给强制页")
        if (input.now - input.lastSameCauseAt < COOLDOWN_MS) {
            return ProactiveDecision(false, Level.L0, "同因 90 秒冷却")
        }
        if (input.now - input.lastActAt < COOLDOWN_MS) {
            return ProactiveDecision(false, Level.L0, "总冷却")
        }
        if (input.pressureHigh && input.trigger == Trigger.HEARTBEAT) {
            return ProactiveDecision(false, Level.L0, "交互压力，不插嘴")
        }
        if (input.trigger == Trigger.MORNING_UNLOCK && input.morningAskedToday) {
            return ProactiveDecision(false, Level.L0, "今天已经问过")
        }
        val level = if (input.trigger == Trigger.SILENT) Level.L1 else Level.L0
        return ProactiveDecision(true, level, "允许开口")
    }
}

/** 模板开口（规则层，不依赖模型；mood 只改语气）。 */
object ProactiveText {
    fun forTrigger(trigger: Trigger, mood: Mood): String = when (trigger) {
        Trigger.ONLINE_TO_GLIDING -> when (mood) {
            Mood.WARM -> "刚才那一步，其实你还没说依据。回到题干，指一个词给我。"
            Mood.TEASE -> "抓一半就跑？依据是哪个词，说清楚再走。"
            Mood.COLD -> "停一下。依据。"
            Mood.STRICT -> "你又想直接过去。依据在哪一句？"
            Mood.WILD -> "停。依据呢？别糊弄。"
        }
        Trigger.SILENT -> when (mood) {
            Mood.WARM -> "十分钟没动静了。先说一句：你现在在做什么？"
            Mood.TEASE -> "人还在吗？一句话就行，别装死。"
            Mood.COLD -> "十分钟。汇报一句。"
            Mood.STRICT -> "沉默十分钟了。现在在做什么，说。"
            Mood.WILD -> "十分钟不吭声。说话。"
        }
        Trigger.MORNING_UNLOCK -> when (mood) {
            Mood.WARM -> "早上好。今天只钉一件事，你选哪件？"
            Mood.TEASE -> "新的一天。别又摊一桌，挑一件。"
            Mood.COLD -> "今天钉哪件事？"
            Mood.STRICT -> "今天的目标，说一件，具体到能验收。"
            Mood.WILD -> "今天到底要干什么？说一件。"
        }
        Trigger.HEARTBEAT -> when (mood) {
            Mood.WARM -> "还在想吗？把刚才那一步的依据说给我听。"
            Mood.TEASE -> "别急着翻页。刚才那句，是凭感觉还是看见了什么？"
            Mood.COLD -> "说依据。"
            Mood.STRICT -> "停一下，依据是什么？"
            Mood.WILD -> "依据。别糊弄。"
        }
        Trigger.NONE -> ""
    }
}
