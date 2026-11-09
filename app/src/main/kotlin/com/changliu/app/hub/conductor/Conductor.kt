package com.changliu.app.hub.conductor

import android.content.Context
import com.changliu.app.brain.BrainRuntime
import com.changliu.app.hub.policy.ConstraintStore
import com.changliu.app.hub.stats.HubStats
import com.changliu.app.infra.settings.UnifiedSettings
import com.zongkong.core.AiConfig
import com.zongkong.core.ApiResult
import com.zongkong.core.agent.ChatClient
import com.zongkong.core.agent.ChatMsg

data class ChatLine(val role: String, val text: String)

data class ConductorReply(
    val text: String,
    val pending: PendingAction? = null,
    val error: Boolean = false,
)

/**
 * 长流中枢（P3 MVP）：复用总控的工具调用循环，接上中枢自己的工具集。
 * 一次对话最多 4 轮工具调用；放宽约束会停在 T3 确认。
 */
object Conductor {

    suspend fun send(ctx: Context, user: String, history: List<ChatLine>): ConductorReply {
        val cfg = UnifiedSettings.read(ctx)
        if (cfg.apiKey.isBlank()) {
            return ConductorReply("还没有配置模型。先去「我的 → API 与集成」填网关和 key。", error = true)
        }
        val client = ChatClient(
            config = {
                AiConfig(
                    baseUrl = endpointBase(cfg.baseUrl),
                    apiKey = cfg.apiKey,
                    model = cfg.brainModel,
                    jsonMode = true,
                    visionModel = cfg.visionModel,
                )
            },
        )
        val system = systemPrompt(ctx)
        val messages = mutableListOf<ChatMsg>()
        history.takeLast(12).forEach { line ->
            messages += ChatMsg(if (line.role == "user") ChatMsg.USER else ChatMsg.ASSISTANT, line.text)
        }
        messages += ChatMsg(ChatMsg.USER, user)

        var lastText = ""
        repeat(4) {
            when (val r = client.chat(system, messages, ConductorTools.specs)) {
                is ApiResult.Err -> return ConductorReply(r.message, error = true)
                is ApiResult.Ok -> {
                    val turn = r.value
                    if (turn.calls.isEmpty()) {
                        return ConductorReply(turn.content.ifBlank { lastText })
                    }
                    messages += ChatMsg(ChatMsg.ASSISTANT, turn.content, calls = turn.calls)
                    for (call in turn.calls) {
                        val outcome = ConductorTools.execute(ctx, call.name, call.args)
                        if (outcome.pending != null) {
                            return ConductorReply(outcome.text, pending = outcome.pending)
                        }
                        AiActionLog.record(ctx, call.name, call.args, AiActionLog.levelOf(call.name), outcome)
                        messages += ChatMsg(
                            ChatMsg.TOOL,
                            outcome.text,
                            callId = call.id,
                            summary = outcome.text.take(80),
                            ok = outcome.ok,
                        )
                    }
                    lastText = turn.content
                }
            }
        }
        return ConductorReply(lastText.ifBlank { "工具调用次数太多，先停一下。" })
    }

    /** T3 确认后由界面调用：执行 + 记日志。 */
    suspend fun confirm(ctx: Context, pending: PendingAction): ToolOutcome {
        val outcome = pending.run()
        if (outcome.pending == null) {
            AiActionLog.record(ctx, "confirmed", "", com.changliu.core.hub.ToolLevel.T2, outcome)
        }
        return outcome
    }

    private suspend fun systemPrompt(ctx: Context): String {
        val stats = runCatching { HubStats.today(ctx) }.getOrNull()
        val constraints = runCatching { ConstraintStore.summary(ctx) }.getOrNull().orEmpty()
        return buildString {
            append("你是「长流中枢」，统一调度规划、训练、陪练与约束。语气：")
            append(BrainRuntime.moodClause())
            append("\n规则：AI 可以自己收紧，不能自己放松；放宽必须走 T3 确认。硬边界（关无障碍/删账本/改宪章）永远不能做。")
            append("\n工具调用要基于账本与真实数据，不要编造。")
            append("\n约束概况：").append(constraints)
            if (stats != null) {
                append("\n今天：屏幕 ").append(stats.screenMin).append(" 分，娱乐 ").append(stats.entertainmentMin)
                append(" 分，拦截 ").append(stats.gatesShown).append(" 次，训练 ").append(stats.trainSets)
                append(" 组，陪练 ").append(stats.liveMin).append(" 分。")
            }
        }
    }

    private fun endpointBase(raw: String): String {
        val t = raw.trim().trimEnd('/')
        return when {
            t.endsWith("/chat/completions") -> t.removeSuffix("/chat/completions")
            t.endsWith("/v1") -> t
            else -> "$t/v1"
        }
    }
}
