package com.changliu.core.brain

/**
 * 周复盘自提规则（设计方案 §8.3）：只允许"自收紧 / 调权重"，且必须可回滚。
 *
 * 输入是已消化的记忆事实（key + 证据次数），输出是语气权重的临时增量。
 * 不能放宽红线、不能改宪章、不能删账本——那些不在这里。
 */
data class MemoryFact(val key: String, val evidence: Int)

data class TuningProposal(
    val key: String,
    val title: String,
    val detail: String,
    val deltas: Map<Mood, Double>,
    val evidence: Int,
)

object Tuning {
    const val MIN_EVIDENCE = 5
    const val HOLD_DAYS = 7

    fun propose(facts: List<MemoryFact>): List<TuningProposal> {
        val out = mutableListOf<TuningProposal>()
        facts.firstOrNull { it.key == "glide-after-online" && it.evidence >= MIN_EVIDENCE }?.let { f ->
            out += TuningProposal(
                key = "stricter-on-glide",
                title = "滑翔偏多：把语气收紧一周",
                detail = "最近 ${f.evidence} 次从思考在线滑到滑翔。接下来一周，严/冷权重提高，先要依据再放过。",
                deltas = mapOf(Mood.STRICT to 10.0, Mood.COLD to 4.0),
                evidence = f.evidence,
            )
        }
        facts.firstOrNull { it.key == "ungrounded-answers" && it.evidence >= MIN_EVIDENCE }?.let { f ->
            out += TuningProposal(
                key = "stricter-on-ungrounded",
                title = "没依据的回答偏多：提高严厉权重",
                detail = "最近 ${f.evidence} 次回答没给出依据。建议把严厉权重提高 8，持续一周。",
                deltas = mapOf(Mood.STRICT to 8.0),
                evidence = f.evidence,
            )
        }
        facts.firstOrNull { it.key == "silent-episodes" && it.evidence >= 3 }?.let { f ->
            out += TuningProposal(
                key = "warmer-on-silent",
                title = "沉默偏多：把语气放软一点",
                detail = "最近 ${f.evidence} 次进入十分钟沉默。建议提高暖/调侃权重，先把人接出来。",
                deltas = mapOf(Mood.WARM to 8.0, Mood.TEASE to 4.0),
                evidence = f.evidence,
            )
        }
        return out
    }
}
