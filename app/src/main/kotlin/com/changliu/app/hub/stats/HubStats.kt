package com.changliu.app.hub.stats

import android.content.Context
import com.changliu.app.Engine
import com.changliu.app.hub.ledger.Ledger
import com.changliu.infra.fusion.FusionHooks
import com.zongkong.app.ZkHost
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 人和 AI 看同一份数：数据页与中枢工具都走这里。 */
object HubStats {

    data class Today(
        val screenMin: Int,
        val entertainmentMin: Int,
        val topApps: List<Pair<String, Int>>,
        val gatesShown: Int,
        val gatesPassed: Int,
        val liveMin: Int,
        val trainSets: Int,
        val tasksDone: Int,
        val gliding: Int,
    ) {
        val passRate: Int get() = if (gatesShown == 0) 0 else (gatesPassed * 100 / gatesShown)
    }

    data class Day(
        val date: LocalDate,
        val screenMin: Int,
        val entertainmentMin: Int,
        val gates: Int,
        val liveMin: Int,
        val trainSets: Int,
    )

    suspend fun today(ctx: Context): Today {
        val from = Ledger.todayStart()
        val to = System.currentTimeMillis()
        val blocked = blockedPackages(ctx)

        val screenSec = Ledger.sum("app_fg", from, to)
        val entSec = Ledger.sumPackages(blocked, from, to)
        val top = Ledger.topPackages(from, to, 5).map { it.pkg to (it.total / 60).toInt() }
        val gatesShown = Ledger.count("gate_shown", from, to)
        val gatesPassed = Ledger.countPositive("gate_answer", from, to)
        val liveMin = Ledger.sum("live_end", from, to).toInt()
        val trainByLedger = Ledger.count("train_set", from, to)
        val trainByYishou = runCatching { FusionHooks.yishouSnapshot?.invoke()?.doneSets ?: 0 }.getOrDefault(0)
        val tasksDone = Ledger.count("task_done", from, to)
        val gliding = Ledger.events(from, to).count { it.kind == "flow_transition" && it.ref == "GLIDING" }

        return Today(
            screenMin = (screenSec / 60).toInt(),
            entertainmentMin = (entSec / 60).toInt(),
            topApps = top,
            gatesShown = gatesShown,
            gatesPassed = gatesPassed,
            liveMin = liveMin,
            trainSets = maxOf(trainByLedger, trainByYishou),
            tasksDone = tasksDone,
            gliding = gliding,
        )
    }

    /** 最近 [days] 天（含今天），按日聚合。 */
    suspend fun week(ctx: Context, days: Int = 7): List<Day> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = Ledger.daysAgoStart((days - 1).toLong())
        val to = System.currentTimeMillis()
        val blocked = blockedPackages(ctx)
        val events = Ledger.events(from, to)

        return (0 until days).map { offset ->
            val date = today.minusDays((days - 1 - offset).toLong())
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val ofDay = events.filter { it.at in start until end }
            val screenSec = ofDay.filter { it.kind == "app_fg" }.sumOf { it.num }
            val entSec = ofDay.filter { it.kind == "app_fg" && it.pkg in blocked }.sumOf { it.num }
            Day(
                date = date,
                screenMin = (screenSec / 60).toInt(),
                entertainmentMin = (entSec / 60).toInt(),
                gates = ofDay.count { it.kind == "gate_shown" },
                liveMin = ofDay.filter { it.kind == "live_end" }.sumOf { it.num }.toInt(),
                trainSets = ofDay.count { it.kind == "train_set" },
            )
        }
    }

    /** 驾驶员与总控两份拦截名单的并集（数据页按它分"娱乐"）。 */
    fun blockedPackages(ctx: Context): Set<String> {
        val driver = runCatching {
            val b = Engine.store.blocking()
            if (b.enabled) b.blocked else emptyList()
        }.getOrDefault(emptyList())
        val zk = runCatching {
            (ctx.applicationContext as ZkHost).store.config.value.blocked.toList()
        }.getOrDefault(emptyList())
        return (driver + zk).toSet()
    }

    fun hourOf(at: Long): Int = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).hour
}
