package com.changliu.app.hub.ledger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 统一账本：三边的"发生了什么"都写到这里，数据页与中枢 AI 读同一份。
 *
 * - 只记事件（包名、时长、判定、数量），不记屏幕文字与截图；
 * - 保留 180 天；
 * - 写入永远异步、失败不影响业务（账本不是关键路径）。
 */
object Ledger {
    private const val KEEP_DAYS = 180

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var db: LedgerDatabase? = null

    fun init(context: Context) {
        if (db != null) return
        synchronized(this) {
            if (db == null) {
                db = runCatching { LedgerDatabase.create(context) }.getOrNull()
            }
        }
    }

    fun emit(
        source: String,
        kind: String,
        pkg: String = "",
        ref: String = "",
        num: Long = 0L,
        payload: String = "",
    ) {
        val d = db ?: return
        scope.launch {
            runCatching {
                d.dao().insert(
                    EventEntity(
                        at = System.currentTimeMillis(),
                        source = source,
                        kind = kind,
                        pkg = pkg,
                        ref = ref,
                        num = num,
                        payload = payload.take(500),
                    ),
                )
            }
        }
    }

    suspend fun events(from: Long, to: Long): List<EventEntity> =
        runCatching { db?.dao()?.between(from, to).orEmpty() }.getOrDefault(emptyList())

    suspend fun sum(kind: String, from: Long, to: Long): Long =
        runCatching { db?.dao()?.sum(kind, from, to) ?: 0L }.getOrDefault(0L)

    suspend fun sumPackages(pkgs: Collection<String>, from: Long, to: Long): Long =
        if (pkgs.isEmpty()) 0L
        else runCatching { db?.dao()?.sumPackages(pkgs.toList(), from, to) ?: 0L }.getOrDefault(0L)

    suspend fun count(kind: String, from: Long, to: Long): Int =
        runCatching { db?.dao()?.count(kind, from, to) ?: 0 }.getOrDefault(0)

    suspend fun countPositive(kind: String, from: Long, to: Long): Int =
        runCatching { db?.dao()?.countPositive(kind, from, to) ?: 0 }.getOrDefault(0)

    suspend fun topPackages(from: Long, to: Long, limit: Int = 5): List<PkgTotal> =
        runCatching { db?.dao()?.topPackages(from, to, limit).orEmpty() }.getOrDefault(emptyList())

    /** 每 10 分钟/每天 0 点后的第一次 tick 调用：把今天与昨天的原始指标落 rollup。 */
    suspend fun rollup(context: Context) {
        val d = db ?: return
        val dao = d.dao()
        val zone = ZoneId.systemDefault()
        for (offset in 0L..1L) {
            val date = LocalDate.now(zone).minusDays(offset)
            val day = date.toString()
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val rows = listOf(
                "screen_min" to dao.sum("app_fg", from, to) / 60,
                "gate_shown" to dao.count("gate_shown", from, to).toLong(),
                "gate_passed" to dao.countPositive("gate_answer", from, to).toLong(),
                "live_min" to dao.sum("live_end", from, to),
                "train_set" to dao.count("train_set", from, to).toLong(),
                "task_done" to dao.count("task_done", from, to).toLong(),
                "flow_gliding" to dao.count("flow_transition", from, to).toLong(),
            )
            rows.forEach { (metric, value) -> dao.putRollup(RollupEntity(day, metric, "", value)) }
        }
        val before = LocalDate.now(zone).minusDays(KEEP_DAYS.toLong())
        dao.pruneEvents(before.atStartOfDay(zone).toInstant().toEpochMilli())
        dao.pruneRollup(before.toString())
    }

    suspend fun clear() {
        val d = db ?: return
        runCatching {
            d.dao().clearEvents()
            d.dao().clearRollup()
        }
    }

    /** 导出账本（JSON）。调用方在 IO 线程执行。 */
    suspend fun exportJson(from: Long, to: Long): String {
        val list = events(from, to)
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("at", e.at)
                    .put("source", e.source)
                    .put("kind", e.kind)
                    .put("pkg", e.pkg)
                    .put("ref", e.ref)
                    .put("num", e.num)
                    .put("payload", e.payload),
            )
        }
        return arr.toString(2)
    }

    fun todayStart(): Long = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun daysAgoStart(days: Long): Long =
        LocalDate.now(ZoneId.systemDefault()).minusDays(days).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
