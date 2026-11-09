package com.changliu.app.hub.conductor

import android.content.Context
import com.changliu.core.hub.ToolLevel
import org.json.JSONObject
import java.io.File

data class ActionRow(
    val id: String,
    val at: Long,
    val tool: String,
    val args: String,
    val level: String,
    val summary: String,
    val undoType: String,
    val undoKey: String,
    val undoValue: String,
    val undone: Boolean,
)

/** AI 操作日志：每条工具调用都记；T1/T2 可撤销，撤销本身也记一条。 */
object AiActionLog {
    private const val FILE = "ai_actions.jsonl"

    private fun file(ctx: Context) = File(File(ctx.filesDir, "hub").apply { mkdirs() }, FILE)

    fun levelOf(tool: String): ToolLevel = when (tool) {
        "constraint_set", "set_grant_minutes" -> ToolLevel.T2
        "live_start", "schedule_wakeup", "awareness_mode", "undo" -> ToolLevel.T1
        else -> ToolLevel.T0
    }

    @Synchronized
    fun record(ctx: Context, tool: String, args: String, level: ToolLevel, outcome: ToolOutcome) {
        runCatching {
            val at = System.currentTimeMillis()
            val row = JSONObject()
                .put("id", "$at-${(1000..9999).random()}")
                .put("at", at)
                .put("tool", tool)
                .put("args", args.take(400))
                .put("level", level.name)
                .put("summary", outcome.text.take(300))
                .put("undoType", outcome.undo?.type.orEmpty())
                .put("undoKey", outcome.undo?.key.orEmpty())
                .put("undoValue", outcome.undo?.value.orEmpty())
                .put("undone", false)
            file(ctx).appendText(row.toString() + "\n")
        }
    }

    fun list(ctx: Context, limit: Int = 40): List<ActionRow> = runCatching {
        file(ctx).readLines().takeLast(limit).mapNotNull { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            ActionRow(
                id = o.optString("id"),
                at = o.optLong("at"),
                tool = o.optString("tool"),
                args = o.optString("args"),
                level = o.optString("level"),
                summary = o.optString("summary"),
                undoType = o.optString("undoType"),
                undoKey = o.optString("undoKey"),
                undoValue = o.optString("undoValue"),
                undone = o.optBoolean("undone", false),
            )
        }.asReversed()
    }.getOrDefault(emptyList())

    suspend fun undo(ctx: Context, id: String): ToolOutcome {
        val row = list(ctx, 200).firstOrNull { it.id == id }
            ?: return ToolOutcome("找不到操作 $id", ok = false)
        if (row.undone) return ToolOutcome("这条已经撤销过了", ok = false)
        if (row.undoType.isBlank()) return ToolOutcome("这条操作不支持撤销", ok = false)
        ConductorTools.applyUndo(ctx, Undo(row.undoType, row.undoKey, row.undoValue))
        runCatching {
            file(ctx).appendText(
                JSONObject()
                    .put("id", "${System.currentTimeMillis()}-undo")
                    .put("at", System.currentTimeMillis())
                    .put("tool", "undo")
                    .put("args", id)
                    .put("level", "T1")
                    .put("summary", "撤销了 ${row.tool}：${row.summary.take(80)}")
                    .put("undone", true)
                    .toString() + "\n",
            )
        }
        return ToolOutcome("已撤销：${row.tool}（${row.summary.take(60)}）")
    }
}
