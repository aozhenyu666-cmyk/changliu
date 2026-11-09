package com.changliu.app.brain

import android.content.Context
import com.changliu.app.infra.gate.GateActivity
import com.changliu.app.infra.overlay.ProactiveOverlay
import com.changliu.core.brain.FlowEstimator
import com.changliu.core.brain.FlowEvidence
import com.changliu.core.brain.FlowState
import com.changliu.core.brain.Level
import com.changliu.core.brain.Mood
import com.changliu.core.brain.MoodEngine
import com.changliu.core.brain.ProactiveInput
import com.changliu.core.brain.ProactivePolicy
import com.changliu.core.brain.ProactiveText
import com.changliu.core.brain.Trigger
import com.changliu.core.brain.clause
import com.changliu.core.brain.label
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * 长流引擎的运行时（设计方案 §4–§6 的最小落地）：
 *
 * - 五态估计：由唯一无障碍服务喂前台/娱乐时长，由 GateActivity 喂回答质量；
 * - mood：随机心跳 [120,240] 秒抽一次，带 5–8% 突发尖峰与 3–8 分钟持续；
 * - 经验账本：`filesDir/brain/ledger.jsonl`，只记状态/语气/判定，不记屏幕内容。
 *
 * mood 只影响"怎么说话"，不影响拦截/锁定是否触发。
 */
object BrainRuntime {
    private const val COOLDOWN_MS = 90_000L

    private val moodEngine = MoodEngine()
    private val rng = Random.Default

    @Volatile var state: FlowState = FlowState.ONLINE
        private set
    @Volatile var mood: Mood = Mood.WARM
        private set
    @Volatile var lastTransition: String = ""
        private set

    private var entPkg: String? = null
    private var entStartedAt = 0L
    private var lastAnswerAt = 0L
    private var lastGrounded: Boolean? = null
    private var lastDelay: Long? = null
    private var lastInteractionAt = System.currentTimeMillis()
    private var lastSpeakAt = 0L
    private var nextHeartbeatAt = 0L

    // 主动开口的账本（调度纪律）
    private var lastActAt = 0L
    private val causeAt = mutableMapOf<Trigger, Long>()
    private var pendingTrigger = Trigger.NONE
    private var lastMorningDay = Long.MIN_VALUE
    private var lastDigestAt = 0L

    fun stateLabel(): String = state.label()
    fun moodLabel(): String = mood.label()
    fun moodClause(): String = mood.clause()

    /** 无障碍看到前台切换（娱乐判定由调用方按两份拦截名单取并集）。 */
    @Synchronized
    fun onApp(ctx: Context, pkg: String, entertainment: Boolean, interactive: Boolean, now: Long) {
        val own = pkg == ctx.packageName
        if (entertainment) {
            if (entPkg != pkg) {
                entPkg = pkg
                entStartedAt = now
            }
        } else if (!own && entPkg != null) {
            // 切到别的应用才算娱乐中断；弹自己的 Gate 页时保持沉浸计时，避免来回抖状态
            entPkg = null
            entStartedAt = 0L
        }
        lastInteractionAt = now
        evaluate(ctx, now, interactive)
    }

    /** GateActivity 判完一道题：有依据/没依据、用了多久。 */
    @Synchronized
    fun onAnswer(ctx: Context, grounded: Boolean, delayMs: Long, now: Long) {
        lastAnswerAt = now
        lastGrounded = grounded
        lastDelay = delayMs
        lastInteractionAt = now
        evaluate(ctx, now, interactive = true)
        ledger(ctx, "answer", JSONObject().put("grounded", grounded).put("delay_ms", delayMs))
    }

    @Synchronized
    fun onInteraction(now: Long) {
        lastInteractionAt = now
    }

    /** 20 秒 tick：重估状态；到心跳点抽一次语气；按调度纪律决定是否主动开口。 */
    @Synchronized
    fun tick(ctx: Context, now: Long, interactive: Boolean) {
        evaluate(ctx, now, interactive)

        // 状态转移（在 onApp/evaluate 里已判定）优先于心跳
        var trigger = pendingTrigger
        pendingTrigger = Trigger.NONE

        if (nextHeartbeatAt == 0L) {
            // 冷启动先排下一次心跳，不在刚打开 App 时立刻搭话
            nextHeartbeatAt = now + randomHeartbeat()
        } else if (now >= nextHeartbeatAt) {
            nextHeartbeatAt = now + randomHeartbeat()
            val pick = moodEngine.pick(now, credit(ctx), state, BrainTuning.extra(ctx))
            mood = pick.mood
            ledger(ctx, "heartbeat", JSONObject().put("spike", pick.spike))
            if (trigger == Trigger.NONE) trigger = Trigger.HEARTBEAT
        }

        val day = today()
        if (trigger == Trigger.NONE && isMorning() && lastMorningDay != day) {
            trigger = Trigger.MORNING_UNLOCK
        }

        val pressure = lastGrounded == true && (now - lastAnswerAt) in 0..60_000L
        val decision = ProactivePolicy.decide(
            ProactiveInput(
                now = now,
                trigger = trigger,
                state = state,
                pressureHigh = pressure,
                lastActAt = lastActAt,
                lastSameCauseAt = causeAt[trigger] ?: 0L,
                morningAskedToday = lastMorningDay == day,
            ),
        )
        if (decision.speak && interactive && !GateActivity.visible) {
            val text = ProactiveText.forTrigger(trigger, mood)
            // L1：沉默降阻，先用语音开口；L0 只出卡
            if (decision.level == Level.L1) runCatching { VoiceNudge.speak(ctx, text) }
            ProactiveOverlay.show(ctx, text, mood.label())
            lastActAt = now
            causeAt[trigger] = now
            markSpoke(now)
            if (trigger == Trigger.MORNING_UNLOCK) lastMorningDay = day
            ledger(
                ctx,
                "proactive",
                JSONObject()
                    .put("trigger", trigger.name)
                    .put("level", decision.level.name)
                    .put("voice", decision.level == Level.L1),
            )
        }

        // 每 10 分钟从账本消化一次长期记忆（暂停时 BrainMemory 自己会跳过）
        if (now - lastDigestAt > 10 * 60_000L) {
            lastDigestAt = now
            BrainMemory.digest(ctx)
        }
    }

    /** 调度纪律：同因 90 秒冷却；离线不说话。 */
    fun shouldSpeak(now: Long, pressure: Boolean = false): Boolean = when {
        now - lastSpeakAt < COOLDOWN_MS -> false
        state == FlowState.OFFLINE -> false
        pressure -> false
        else -> true
    }

    fun markSpoke(now: Long) {
        lastSpeakAt = now
    }

    /** 复盘调整权重后立刻重抽语气，不等当前语气自然到期。 */
    @Synchronized
    fun refreshMood(ctx: Context) {
        moodEngine.reset()
        mood = moodEngine.pick(System.currentTimeMillis(), credit(ctx), state, BrainTuning.extra(ctx)).mood
    }

    private fun evaluate(ctx: Context, now: Long, interactive: Boolean) {
        val evidence = FlowEvidence(
            interactive = interactive,
            entertainmentMs = if (entPkg != null) (now - entStartedAt).coerceAtLeast(0) else 0L,
            sinceLastAnswerMs = if (lastAnswerAt > 0) now - lastAnswerAt else Long.MAX_VALUE,
            lastAnswerGrounded = lastGrounded,
            lastAnswerDelayMs = lastDelay,
            silenceMs = (now - lastInteractionAt).coerceAtLeast(0),
        )
        val estimate = FlowEstimator.estimate(evidence)
        if (estimate.state != state) {
            val previous = state
            state = estimate.state
            lastTransition = "${previous.label()} → ${state.label()}"
            pendingTrigger = when (state) {
                FlowState.GLIDING -> Trigger.ONLINE_TO_GLIDING
                FlowState.SILENT -> Trigger.SILENT
                else -> Trigger.NONE
            }
            ledger(
                ctx,
                "transition",
                JSONObject()
                    .put("from", previous.name)
                    .put("to", state.name)
                    .put("why", estimate.reasons.joinToString("；")),
            )
            com.changliu.app.hub.ledger.Ledger.emit(
                "brain",
                "flow_transition",
                ref = state.name,
                num = 1L,
                payload = estimate.reasons.joinToString("；"),
            )
            mood = moodEngine.pick(now, credit(ctx), state, BrainTuning.extra(ctx)).mood
        } else if (moodEngine.current(now) == null) {
            mood = moodEngine.pick(now, credit(ctx), state, BrainTuning.extra(ctx)).mood
        }
    }

    /** 总控信用（0–100，起点 70）：直接取 Boss 的 14 天账本。 */
    private fun credit(ctx: Context): Int = runCatching {
        val app = ctx.applicationContext as com.zongkong.app.ZkHost
        val store = app.store
        com.zongkong.core.boss.Boss.credibility(
            store.dossier.value,
            store.hq.value.sessions,
            System.currentTimeMillis(),
            store.work.value.events,
        )
    }.getOrDefault(70)

    private fun randomHeartbeat(): Long = rng.nextLong(120_000L, 240_001L)

    private fun today(): Long = runCatching { java.time.LocalDate.now().toEpochDay() }.getOrDefault(0L)

    private fun isMorning(): Boolean = runCatching { java.time.LocalTime.now().hour in 6..10 }.getOrDefault(false)

    private fun ledger(ctx: Context, event: String, fields: JSONObject) {
        runCatching {
            val dir = File(ctx.filesDir, "brain").apply { mkdirs() }
            val row = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("event", event)
                .put("state", state.name)
                .put("mood", mood.name)
            fields.keys().forEach { key -> row.put(key, fields.get(key)) }
            File(dir, "ledger.jsonl").appendText(row.toString() + "\n")
        }
    }
}
