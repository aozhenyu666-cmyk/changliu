package com.changliu.app.brain

import android.content.Context
import com.changliu.core.brain.LedgerEvent
import com.changliu.core.brain.MemoryDigest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MemoryEntry(val key: String, val text: String, val evidence: Int, val updatedAt: Long)

/**
 * 可读、可删、可暂停的长期记忆（设计方案 §8.1–§8.3）。
 *
 * - 只从 `brain/ledger.jsonl` 消化，同类证据 ≥3 次才写；
 * - 删除 = 记墓碑，之后不再自动写回；
 * - 暂停后不新增，已有内容仍可查看。
 */
object BrainMemory {
    private const val PREFS = "brain_memory"
    private const val PAUSED = "paused"
    private const val TOMBS = "tombstones"

    fun paused(ctx: Context): Boolean = prefs(ctx).getBoolean(PAUSED, false)

    fun setPaused(ctx: Context, paused: Boolean) {
        prefs(ctx).edit().putBoolean(PAUSED, paused).apply()
    }

    fun entries(ctx: Context): List<MemoryEntry> = readMemory(ctx).sortedByDescending { it.updatedAt }

    fun ledgerSize(ctx: Context): Int = runCatching { ledgerFile(ctx).readLines().count { it.isNotBlank() } }.getOrDefault(0)

    @Synchronized
    fun clear(ctx: Context, key: String) {
        val tombs = prefs(ctx).getStringSet(TOMBS, emptySet()).orEmpty().toMutableSet()
        tombs.add(key)
        prefs(ctx).edit().putStringSet(TOMBS, tombs).apply()
        writeMemory(ctx, readMemory(ctx).filterNot { it.key == key })
    }

    /** 从账本重新消化。暂停时不动。 */
    @Synchronized
    fun digest(ctx: Context) {
        if (paused(ctx)) return
        val events = readLedger(ctx)
        if (events.isEmpty()) return
        val candidates = MemoryDigest.digest(events, System.currentTimeMillis())
        if (candidates.isEmpty()) return
        val tombs = prefs(ctx).getStringSet(TOMBS, emptySet()).orEmpty()
        val existing = readMemory(ctx).associateBy { it.key }
        val now = System.currentTimeMillis()
        val merged = candidates
            .filterNot { it.key in tombs }
            .map { c ->
                val prev = existing[c.key]?.evidence ?: 0
                MemoryEntry(c.key, c.text, maxOf(prev, c.evidence.size), now)
            }
        if (merged.isNotEmpty()) writeMemory(ctx, merged)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(ctx: Context) = File(ctx.filesDir, "brain").apply { mkdirs() }

    private fun ledgerFile(ctx: Context) = File(dir(ctx), "ledger.jsonl")

    private fun readLedger(ctx: Context): List<LedgerEvent> = runCatching {
        ledgerFile(ctx).readLines().mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            LedgerEvent(
                at = o.optLong("at", 0L),
                event = o.optString("event"),
                from = o.optString("from").takeIf { it.isNotBlank() },
                to = o.optString("to").takeIf { it.isNotBlank() },
                grounded = if (o.has("grounded")) o.optBoolean("grounded") else null,
            )
        }
    }.getOrDefault(emptyList())

    private fun readMemory(ctx: Context): List<MemoryEntry> = runCatching {
        val f = File(dir(ctx), "memory.json")
        if (!f.exists()) return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            MemoryEntry(
                key = o.optString("key"),
                text = o.optString("text"),
                evidence = o.optInt("evidence", 0),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeMemory(ctx: Context, entries: List<MemoryEntry>) {
        runCatching {
            val a = JSONArray()
            entries.forEach { e ->
                a.put(
                    JSONObject()
                        .put("key", e.key)
                        .put("text", e.text)
                        .put("evidence", e.evidence)
                        .put("updatedAt", e.updatedAt),
                )
            }
            File(dir(ctx), "memory.json").writeText(a.toString(2))
        }
    }
}
