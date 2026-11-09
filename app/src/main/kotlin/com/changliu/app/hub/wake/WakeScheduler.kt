package com.changliu.app.hub.wake

import android.content.Context
import com.changliu.app.Engine
import com.changliu.app.brain.BrainRuntime
import com.changliu.app.hub.ledger.Ledger
import com.changliu.app.hub.ledger.LedgerDatabase
import com.changliu.app.hub.stats.HubStats
import com.changliu.app.infra.overlay.ProactiveOverlay
import com.changliu.core.brain.FlowState
import com.changliu.core.hub.WakeRules
import com.changliu.core.hub.WakeState
import com.changliu.core.hub.WakeTrigger
import com.zongkong.app.ZkHost
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * P4 主动唤醒（本地规则版）：挂在 KeepAliveService 的 tick 上。
 * 每次触发都走 WakeRules（每日上限/同因冷却/夜间不开口），只出 L0 卡，不调用模型。
 */
object WakeScheduler {
    private const val PREFS = "hub_wake"

    fun tick(ctx: Context) {
        runCatching { process(ctx) }
    }

    private fun process(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone).toString()
        val time = LocalTime.now(zone)

        val saved = runCatching { JSONObject(prefs.getString("state", "{}") ?: "{}") }.getOrDefault(JSONObject())
        var day = saved.optString("day", today)
        var count = if (day == today) saved.optInt("count", 0) else 0
        val lastObj = if (day == today) saved.optJSONObject("last") ?: JSONObject() else JSONObject()
        var glidingSince = if (day == today) saved.optLong("glidingSince", 0L) else 0L
        var lastLocked = saved.optBoolean("lastLocked", false)
        var changed = false

        fun lastOf(trigger: WakeTrigger): Long = lastObj.optLong(trigger.name, 0L)

        fun fire(trigger: WakeTrigger, text: String) {
            val state = WakeState(today, count, buildMap {
                WakeTrigger.entries.forEach { t -> lastOf(t).takeIf { it > 0 }?.let { put(t, it) } }
            })
            val decision = WakeRules.decide(trigger, now, state, today, time.hour)
            if (!decision.allow) return
            ProactiveOverlay.show(ctx, text, "提醒")
            count += 1
            lastObj.put(trigger.name, now)
            changed = true
        }

        // AI 自己安排的回访
        val wakeFile = File(File(ctx.filesDir, "hub"), "wake.json")
        if (wakeFile.exists()) {
            val o = runCatching { JSONObject(wakeFile.readText()) }.getOrNull()
            val at = o?.optLong("at", 0L) ?: 0L
            if (at in 1..now) {
                fire(WakeTrigger.AI_SCHEDULED, "你让我这会儿回来：${o?.optString("reason").orEmpty().ifBlank { "看看现在到哪一步了" }}")
                wakeFile.delete()
            }
        }

        // 滑行超过 10 分钟
        if (BrainRuntime.state == FlowState.GLIDING) {
            if (glidingSince == 0L) { glidingSince = now; changed = true }
            else if (now - glidingSince >= 10 * 60_000L) {
                fire(WakeTrigger.GLIDING_10MIN, "滑了十分钟了。回到主线：下一步做什么？")
            }
        } else if (glidingSince != 0L) { glidingSince = 0L; changed = true }

        // 娱乐额度 80% / 100%
        val todayStats = runCatching { kotlinx.coroutines.runBlocking { HubStats.today(ctx) } }.getOrNull()
        val quota = quotaMinutes(ctx)
        if (todayStats != null && quota > 0) {
            val used = todayStats.entertainmentMin
            when {
                used >= quota -> fire(WakeTrigger.ENTERTAINMENT_100, "今天娱乐额度用完了（$used/$quota 分钟）。要不要开严管？")
                used * 100 / quota >= 80 -> fire(WakeTrigger.ENTERTAINMENT_80, "娱乐已经用了 $used/$quota 分钟。收一收，回主线。")
            }
        }

        // 同一类题连续失败 3 次
        val fail3 = runCatching {
            kotlinx.coroutines.runBlocking {
                val from = Ledger.todayStart()
                Ledger.events(from, now).count { it.kind == "gate_answer" && it.num == 0L } >= 3
            }
        }.getOrDefault(false)
        if (fail3) fire(WakeTrigger.GATE_FAIL_3, "同一个门今天已经答错 3 次。换个题型，或者直接拦。")

        // 陪练结束
        val locked = Engine.locked
        if (lastLocked && !locked) {
            fire(WakeTrigger.LIVE_ENDED, "陪练结束了。写一句结论，再从题库挑一道后续练习。")
        }
        if (locked != lastLocked) { lastLocked = locked; changed = true }

        // 日/周复盘
        if (time.hour == 21 && time.minute >= 30) {
            if (LocalDate.now(zone).dayOfWeek.value == 7) {
                fire(WakeTrigger.WEEKLY_REVIEW, "周日了。对照本周账本和计划，指出一个落后的里程碑。")
            } else {
                fire(WakeTrigger.DAILY_REVIEW, "晚上九点半。复盘今天，定明天第一步。")
            }
        }

        if (changed || day != today) {
            prefs.edit().putString(
                "state",
                JSONObject()
                    .put("day", today)
                    .put("count", count)
                    .put("last", lastObj)
                    .put("glidingSince", glidingSince)
                    .put("lastLocked", lastLocked)
                    .toString(),
            ).apply()
        }
    }

    private fun quotaMinutes(ctx: Context): Int {
        val driver = runCatching { Engine.store.blocking().dailyMinutes }.getOrDefault(0)
        val zk = runCatching {
            val store = (ctx.applicationContext as ZkHost).store
            store.status().quotaMin
        }.getOrDefault(0)
        return maxOf(driver, zk)
    }
}
