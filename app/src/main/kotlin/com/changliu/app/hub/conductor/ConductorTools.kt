package com.changliu.app.hub.conductor

import android.content.Context
import com.changliu.app.AwarenessRuntime
import com.changliu.app.Engine
import com.changliu.app.hub.policy.ConstraintStore
import com.changliu.app.hub.stats.HubStats
import com.changliu.core.hub.AppMode
import com.changliu.core.hub.PolicyEngine
import com.changliu.infra.fusion.FusionHooks
import com.yishou.app.YishouApp
import com.yishou.app.card.Card
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialSource
import com.yishou.app.data.Task
import com.zongkong.core.agent.Param
import com.zongkong.core.agent.ToolSpec
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.WorkOps
import kotlin.random.Random
import org.json.JSONObject
import java.io.File

data class Undo(val type: String, val key: String, val value: String)

data class PendingAction(val prompt: String, val run: suspend () -> ToolOutcome)

data class ToolOutcome(
    val text: String,
    val ok: Boolean = true,
    val undo: Undo? = null,
    val pending: PendingAction? = null,
)

/** 中枢可调用的工具（第一批，P3 MVP）。读类直接执行；收紧直接执行；放宽需 T3 确认。 */
object ConductorTools {

    val specs: List<ToolSpec> = listOf(
        ToolSpec("get_stats", "今天与本周的屏幕、娱乐、拦截、陪练、训练统计", emptyList()),
        ToolSpec("ledger_query", "查最近 N 天的账本事件计数", listOf(Param("days", "integer", "天数，默认 1", false))),
        ToolSpec("train_stats", "一手今日训练状态（组数、有效手数、当前任务）", emptyList()),
        ToolSpec(
            "assign_drill",
            "给一手排一组训练（写入当前任务）",
            listOf(
                Param("topic", "string", "练什么，例如申论", true),
                Param("sets", "integer", "几组，默认 3", false),
            ),
        ),
        ToolSpec(
            "add_material",
            "把一段文字收进一手素材池",
            listOf(
                Param("title", "string", "标题", true),
                Param("text", "string", "正文", true),
            ),
        ),
        ToolSpec(
            "add_action",
            "在总控里加一步行动",
            listOf(
                Param("title", "string", "行动标题", true),
                Param("criteria", "string", "完成依据，可空", false),
            ),
        ),
        ToolSpec("live_summary", "读上一次陪练的结论", emptyList()),
        ToolSpec("agenda_breakpoints", "读驾驶员还挂着的议程断点", emptyList()),
        ToolSpec("bank_search", "在一手题库里按关键词找题", listOf(Param("keyword", "string", "关键词，可空", false))),
        ToolSpec("constraint_get", "查看统一约束名单与模式", emptyList()),
        ToolSpec(
            "constraint_set",
            "把某个 App 的约束模式设为 ALLOW/OBSERVE/GATE/BLOCK；放宽需要确认",
            listOf(
                Param("pkg", "string", "包名", true),
                Param("mode", "string", "ALLOW/OBSERVE/GATE/BLOCK", true, listOf("ALLOW", "OBSERVE", "GATE", "BLOCK")),
            ),
        ),
        ToolSpec(
            "set_grant_minutes",
            "设置答题通过后的放行分钟数；调高需要确认",
            listOf(Param("minutes", "integer", "1-120", true)),
        ),
        ToolSpec(
            "live_start",
            "开始一次思维驾驶员陪练",
            listOf(
                Param("topic", "string", "主题", true),
                Param("minutes", "integer", "分钟", true),
            ),
        ),
        ToolSpec("live_status", "查看当前陪练状态与剩余时间", emptyList()),
        ToolSpec(
            "schedule_wakeup",
            "安排一次自我回访（多少分钟后）",
            listOf(
                Param("in_minutes", "integer", "多少分钟后", true),
                Param("reason", "string", "回访要做什么", false),
            ),
        ),
        ToolSpec(
            "awareness_mode",
            "开启或停止自我察觉（监督模式）",
            listOf(
                Param("mode", "string", "on/off", true, listOf("on", "off")),
                Param("minutes", "integer", "开启时长，默认 60", false),
            ),
        ),
        ToolSpec("undo", "撤销一条 AI 操作（需要操作 id）", listOf(Param("id", "string", "操作 id", true))),
    )

    suspend fun execute(ctx: Context, name: String, argsJson: String): ToolOutcome {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return when (name) {
            "get_stats" -> stats(ctx)
            "ledger_query" -> ledgerQuery(ctx, args.optInt("days", 1).coerceIn(1, 30))
            "train_stats" -> trainStats(ctx)
            "assign_drill" -> assignDrill(ctx, args)
            "add_material" -> addMaterial(ctx, args)
            "add_action" -> addAction(ctx, args)
            "live_summary" -> liveSummary()
            "agenda_breakpoints" -> agendaBreakpoints()
            "bank_search" -> bankSearch(ctx, args.optString("keyword"))
            "constraint_get" -> constraintGet(ctx)
            "constraint_set" -> constraintSet(ctx, args)
            "set_grant_minutes" -> grantMinutes(args)
            "live_start" -> liveStart(args)
            "live_status" -> liveStatus()
            "schedule_wakeup" -> scheduleWakeup(ctx, args)
            "awareness_mode" -> awareness(ctx, args)
            "undo" -> undo(ctx, args.optString("id"))
            else -> ToolOutcome("没有这个工具：$name", ok = false)
        }
    }

    private suspend fun stats(ctx: Context): ToolOutcome {
        val t = HubStats.today(ctx)
        val w = HubStats.week(ctx)
        return ToolOutcome(
            "今天：屏幕 ${t.screenMin} 分，娱乐 ${t.entertainmentMin} 分，拦截 ${t.gatesShown} 次（通过 ${t.gatesPassed}），" +
                "陪练 ${t.liveMin} 分，训练 ${t.trainSets} 组，行动 ${t.tasksDone} 件，滑行 ${t.gliding} 次。\n" +
                "本周：娱乐 ${w.sumOf { it.entertainmentMin }} 分，拦截 ${w.sumOf { it.gates }} 次，陪练 ${w.sumOf { it.liveMin }} 分，" +
                "训练 ${w.sumOf { it.trainSets }} 组。",
        )
    }

    private suspend fun ledgerQuery(ctx: Context, days: Int): ToolOutcome {
        val from = com.changliu.app.hub.ledger.Ledger.daysAgoStart((days - 1).toLong())
        val events = com.changliu.app.hub.ledger.Ledger.events(from, System.currentTimeMillis())
        val top = events.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }.take(8)
        return ToolOutcome("最近 $days 天共 ${events.size} 条事件：" + top.joinToString("，") { "${it.key} ${it.value}" })
    }

    private fun trainStats(ctx: Context): ToolOutcome {
        val s = FusionHooks.yishouSnapshot?.invoke()
            ?: return ToolOutcome("一手还没启动好", ok = false)
        return ToolOutcome(
            "一手今天 ${s.doneSets}/${s.targetSets} 组，有效 ${s.effectiveToday} 手，${s.ledgerLine}" +
                if (s.taskTitle.isNotBlank()) "；在练「${s.taskTitle}」" else "",
        )
    }

    private fun bankSearch(ctx: Context, keyword: String): ToolOutcome {
        val app = ctx.applicationContext as? YishouApp ?: return ToolOutcome("一手不可用", ok = false)
        if (!app.ready) return ToolOutcome("一手还在启动", ok = false)
        val rows = runCatching { kotlinx.coroutines.runBlocking { app.database.cards().recent(300) } }.getOrNull()
            ?: return ToolOutcome("读题库失败", ok = false)
        val hits = rows.asSequence().mapNotNull { row ->
            runCatching { Card.parse(JSONObject(row.json)).question }.getOrNull()
        }.filter { keyword.isBlank() || it.contains(keyword) }.take(3).toList()
        return if (hits.isEmpty()) ToolOutcome("题库里没找到「$keyword」")
        else ToolOutcome(hits.mapIndexed { i, q -> "${i + 1}. ${q.take(80)}" }.joinToString("\n"))
    }

    private fun constraintGet(ctx: Context): ToolOutcome {
        val p = ConstraintStore.load(ctx)
        val lines = p.apps.values.sortedBy { it.pkg }.take(20).joinToString("\n") { "${it.pkg} ${it.mode.name}（${it.category}）" }
        return ToolOutcome("${ConstraintStore.summary(ctx)}\n$lines")
    }

    private fun constraintSet(ctx: Context, args: JSONObject): ToolOutcome {
        val pkg = args.optString("pkg")
        if (pkg.isBlank()) return ToolOutcome("缺少 pkg", ok = false)
        val mode = runCatching { AppMode.valueOf(args.optString("mode").uppercase()) }.getOrNull()
            ?: return ToolOutcome("mode 只能是 ALLOW/OBSERVE/GATE/BLOCK", ok = false)
        val profile = ConstraintStore.load(ctx)
        val old = profile.apps[pkg]?.mode ?: AppMode.GATE
        val looser = PolicyEngine.rank(mode) < PolicyEngine.rank(old)
        fun apply(): ToolOutcome {
            ConstraintStore.setMode(ctx, pkg, mode)
            ConstraintStore.syncToLegacy(ctx, ConstraintStore.load(ctx))
            return ToolOutcome(
                "已把 $pkg 设为 ${mode.name}",
                undo = Undo("constraint_mode", pkg, old.name),
            )
        }
        return if (looser) {
            ToolOutcome(
                "要把 $pkg 从 ${old.name} 放宽到 ${mode.name}，需要你确认",
                pending = PendingAction("AI 想把 $pkg 从 ${old.name} 放宽到 ${mode.name}，确认？") { apply() },
            )
        } else apply()
    }

    private fun grantMinutes(args: JSONObject): ToolOutcome {
        val minutes = args.optInt("minutes", 0).coerceIn(1, 120)
        val b = Engine.store.blocking()
        val old = b.minutes
        fun apply(): ToolOutcome {
            Engine.store.saveBlocking(Engine.store.blocking().copy(minutes = minutes))
            com.changliu.app.Intercept.invalidate()
            return ToolOutcome("放行时长已设为 $minutes 分钟", undo = Undo("grant_minutes", "", old.toString()))
        }
        return if (minutes > old) {
            ToolOutcome(
                "要把放行从 $old 分钟调到 $minutes 分钟，需要你确认",
                pending = PendingAction("AI 想把放行时长从 $old 分钟调到 $minutes 分钟，确认？") { apply() },
            )
        } else apply()
    }

    private fun liveStart(args: JSONObject): ToolOutcome {
        val topic = args.optString("topic").ifBlank { "现在这一件事" }
        val minutes = args.optInt("minutes", 15).coerceIn(5, 120)
        Engine.start(topic, "", minutes)
        return ToolOutcome("已开始陪练：$topic（$minutes 分钟）")
    }

    private fun liveStatus(): ToolOutcome {
        val snap = Engine.snapshot ?: return ToolOutcome("当前没有陪练")
        val left = ((snap.endsAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        return ToolOutcome("陪练中：${snap.task.ifBlank { snap.topic }}，剩 $left 分钟")
    }

    private fun scheduleWakeup(ctx: Context, args: JSONObject): ToolOutcome {
        val minutes = args.optInt("in_minutes", 30).coerceIn(5, 24 * 60)
        val reason = args.optString("reason").ifBlank { "回访" }
        val dir = File(ctx.filesDir, "hub").apply { mkdirs() }
        File(dir, "wake.json").writeText(
            JSONObject().put("at", System.currentTimeMillis() + minutes * 60_000L).put("reason", reason).toString(),
        )
        return ToolOutcome("已安排 $minutes 分钟后回访：$reason")
    }

    private fun awareness(ctx: Context, args: JSONObject): ToolOutcome {
        val on = args.optString("mode").equals("on", ignoreCase = true)
        if (on) AwarenessRuntime.start(args.optInt("minutes", 60).coerceIn(10, 240))
        else AwarenessRuntime.stop()
        return ToolOutcome(if (on) "监督模式已开启" else "监督模式已停止")
    }

    private fun addMaterial(ctx: Context, args: JSONObject): ToolOutcome {
        val title = args.optString("title").ifBlank { "中枢收进来的素材" }.take(60)
        val text = args.optString("text")
        if (text.isBlank()) return ToolOutcome("缺少正文", ok = false)
        val app = ctx.applicationContext as? YishouApp ?: return ToolOutcome("一手不可用", ok = false)
        if (!app.ready) return ToolOutcome("一手还在启动", ok = false)
        val id = runCatching {
            kotlinx.coroutines.runBlocking {
                app.database.think().insertMaterial(
                    Material(
                        title = title,
                        text = text.take(8000),
                        source = MaterialSource.TYPED,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }.getOrElse { return ToolOutcome("写素材失败：${it.message}", ok = false) }
        return ToolOutcome("已收进一手素材池：#$id「$title」")
    }

    private fun addAction(ctx: Context, args: JSONObject): ToolOutcome {
        val title = args.optString("title").ifBlank { "下一步" }.take(60)
        val criteria = args.optString("criteria").take(200)
        val zk = ctx.applicationContext as? com.zongkong.app.ZkHost ?: return ToolOutcome("总控不可用", ok = false)
        val (next, rec) = WorkOps.create(
            zk.store.work.value,
            Kind.ACTION,
            mapOf(F.TITLE to title, F.CRITERIA to criteria),
            System.currentTimeMillis(),
            Random.Default,
        )
        zk.store.updateWork { next }
        return ToolOutcome("已加行动「$title」（${rec.key}）")
    }

    private fun liveSummary(): ToolOutcome {
        val last = Engine.store.last() ?: return ToolOutcome("还没有陪练记录")
        val text = last.conclusion?.takeIf { it.isNotBlank() } ?: last.task.ifBlank { last.topic }
        return ToolOutcome("上次陪练：$text（结束原因 ${last.reason.ifBlank { "未知" }}）")
    }

    private fun agendaBreakpoints(): ToolOutcome {
        val open = Engine.store.agenda().open().take(6)
        if (open.isEmpty()) return ToolOutcome("议程是空的")
        return ToolOutcome(open.mapIndexed { i, item -> "${i + 1}. ${item.text.take(80)}" }.joinToString("\n"))
    }

    private fun assignDrill(ctx: Context, args: JSONObject): ToolOutcome {
        val topic = args.optString("topic").ifBlank { "今天的主线" }.take(40)
        val sets = args.optInt("sets", 3).coerceIn(1, 10)
        val app = ctx.applicationContext as? YishouApp ?: return ToolOutcome("一手不可用", ok = false)
        if (!app.ready) return ToolOutcome("一手还在启动", ok = false)
        val dao = app.database.dao()
        val now = System.currentTimeMillis()
        val title = "训练：$topic"
        runCatching {
            kotlinx.coroutines.runBlocking {
                val current = dao.getCurrentTask()
                if (current != null && current.title == title) {
                    dao.editTask(current, "", "", "", now)
                } else {
                    dao.createCurrentTask(
                        Task(
                            title = title.take(60),
                            goal = "完成 $sets 组",
                            material = null,
                            isCurrent = true,
                            createdAt = now,
                        ),
                        "", "", "", now,
                    )
                }
            }
        }.getOrElse { return ToolOutcome("排训练失败：${it.message}", ok = false) }
        return ToolOutcome("已给一手排 $sets 组「$topic」，打开一手就能看到当前任务")
    }

    private suspend fun undo(ctx: Context, id: String): ToolOutcome {
        if (id.isBlank()) return ToolOutcome("缺少操作 id", ok = false)
        return AiActionLog.undo(ctx, id)
    }

    suspend fun applyUndo(ctx: Context, undo: Undo) {
        when (undo.type) {
            "constraint_mode" -> {
                ConstraintStore.setMode(ctx, undo.key, runCatching { AppMode.valueOf(undo.value) }.getOrDefault(AppMode.GATE))
                ConstraintStore.syncToLegacy(ctx, ConstraintStore.load(ctx))
            }
            "grant_minutes" -> {
                val m = undo.value.toIntOrNull() ?: return
                Engine.store.saveBlocking(Engine.store.blocking().copy(minutes = m))
                com.changliu.app.Intercept.invalidate()
            }
        }
    }
}
