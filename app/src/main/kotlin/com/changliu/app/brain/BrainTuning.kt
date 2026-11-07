package com.changliu.app.brain

import android.content.Context
import com.changliu.core.brain.MemoryFact
import com.changliu.core.brain.Mood
import com.changliu.core.brain.Tuning
import com.changliu.core.brain.TuningProposal
import org.json.JSONObject

data class ActiveTuning(
    val key: String,
    val title: String,
    val deltas: Map<Mood, Double>,
    val until: Long,
    val reason: String,
)

/**
 * 周复盘自提规则的落地（设计方案 §8.3）：
 * 只调整语气权重，7 天自动过期；随时可恢复默认；不碰拦截红线与宪章。
 */
object BrainTuning {
    private const val PREFS = "brain_tuning"
    private const val ACTIVE = "active"

    fun proposals(ctx: Context): List<TuningProposal> {
        val facts = BrainMemory.entries(ctx).map { MemoryFact(it.key, it.evidence) }
        val active = active(ctx)
        return Tuning.propose(facts).filter { it.key != active?.key }
    }

    @Synchronized
    fun active(ctx: Context): ActiveTuning? {
        val raw = prefs(ctx).getString(ACTIVE, null) ?: return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val until = o.optLong("until", 0L)
        if (until <= System.currentTimeMillis()) {
            prefs(ctx).edit().remove(ACTIVE).apply()
            return null
        }
        val deltasObj = o.optJSONObject("deltas") ?: JSONObject()
        val deltas = Mood.entries.mapNotNull { mood ->
            val d = deltasObj.optDouble(mood.name, 0.0)
            if (d != 0.0) mood to d else null
        }.toMap()
        return ActiveTuning(
            key = o.optString("key"),
            title = o.optString("title"),
            deltas = deltas,
            until = until,
            reason = o.optString("reason"),
        )
    }

    fun extra(ctx: Context): Map<Mood, Double> = active(ctx)?.deltas.orEmpty()

    @Synchronized
    fun apply(ctx: Context, proposal: TuningProposal) {
        val until = System.currentTimeMillis() + Tuning.HOLD_DAYS * 24 * 3600_000L
        val deltas = JSONObject().apply {
            proposal.deltas.forEach { (mood, delta) -> put(mood.name, delta) }
        }
        val o = JSONObject()
            .put("key", proposal.key)
            .put("title", proposal.title)
            .put("until", until)
            .put("reason", proposal.detail)
            .put("deltas", deltas)
        prefs(ctx).edit().putString(ACTIVE, o.toString()).apply()
        BrainRuntime.refreshMood(ctx)
    }

    @Synchronized
    fun reset(ctx: Context) {
        prefs(ctx).edit().remove(ACTIVE).apply()
        BrainRuntime.refreshMood(ctx)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
