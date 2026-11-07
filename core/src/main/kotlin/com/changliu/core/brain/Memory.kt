package com.changliu.core.brain

/**
 * 经验账本 → 消化 → 长期记忆（设计方案 §8.2）。
 *
 * 保守原则：同类偏离至少 3 次证据才写；每条结论都能回指到具体事件时间；
 * 不生成原话里没有的具体信息。
 */
data class LedgerEvent(
    val at: Long,
    val event: String,
    val from: String? = null,
    val to: String? = null,
    val grounded: Boolean? = null,
)

data class MemoryCandidate(
    val key: String,
    val text: String,
    val evidence: List<Long>,
)

object MemoryDigest {
    const val MIN_EVIDENCE = 3
    const val WINDOW_MS = 7 * 24 * 60 * 60_000L

    fun digest(
        events: List<LedgerEvent>,
        now: Long,
        windowMs: Long = WINDOW_MS,
    ): List<MemoryCandidate> {
        val since = now - windowMs
        val recent = events.filter { it.at >= since }
        val out = mutableListOf<MemoryCandidate>()

        val glides = recent.filter { it.event == "transition" && it.from == "ONLINE" && it.to == "GLIDING" }
        if (glides.size >= MIN_EVIDENCE) {
            out += MemoryCandidate(
                key = "glide-after-online",
                text = "最近 ${glides.size} 次从「思考在线」滑到「滑翔」，多在刚切进娱乐或回答没依据之后。",
                evidence = glides.map { it.at }.sorted(),
            )
        }

        val ungrounded = recent.filter { it.event == "answer" && it.grounded == false }
        if (ungrounded.size >= MIN_EVIDENCE) {
            out += MemoryCandidate(
                key = "ungrounded-answers",
                text = "最近 ${ungrounded.size} 次回答没有给出依据。",
                evidence = ungrounded.map { it.at }.sorted(),
            )
        }

        val silent = recent.filter { it.event == "transition" && it.to == "SILENT" }
        if (silent.size >= MIN_EVIDENCE) {
            out += MemoryCandidate(
                key = "silent-episodes",
                text = "最近 ${silent.size} 次进入「沉默」超过十分钟。",
                evidence = silent.map { it.at }.sorted(),
            )
        }

        return out
    }
}
