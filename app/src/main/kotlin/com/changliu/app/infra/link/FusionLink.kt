package com.changliu.app.infra.link

import android.content.Intent
import com.changliu.app.A11yGuard
import com.changliu.app.BuildConfig
import com.changliu.app.ChangliuApp
import com.changliu.infra.fusion.FusionHooks
import com.changliu.infra.fusion.PassPolicy
import com.changliu.infra.fusion.YishouSnapshot
import com.yishou.app.YishouApp
import com.yishou.app.data.Task
import com.yishou.app.link.LinkWire
import com.yishou.app.settings.AppZone
import com.yishou.app.summary.Ledger
import com.yishou.app.summary.LedgerMark
import com.yishou.app.window.WindowActivity
import com.yishou.app.window.WindowClock
import com.zongkong.app.ZkHost
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * 融合后的跨模块直连（替代原来的 ContentProvider + 签名校验）。
 *
 * 一手/总控是平级 library，只能通过 :infra 的 FusionHooks 交换；本对象在 App 启动时把钩子接到真实数据：
 *  - 总控读一手今日账、把行动交给一手、开一手陪练窗口；
 *  - 一手发放行前读取总控的收紧要求（严管 / 专注锁 / 落后）。
 */
object FusionLink {

    private lateinit var app: ChangliuApp

    fun install(context: ChangliuApp) {
        app = context

        FusionHooks.yishouInstalled = { true }
        FusionHooks.yishouSnapshot = { snapshot() }
        FusionHooks.yishouSetTask = { order -> setTask(order) }
        FusionHooks.yishouStartWindow = { startWindow() }
        FusionHooks.openYishouWindow = { openWindow() }
        FusionHooks.zongkongPassPolicy = { passPolicy() }
        FusionHooks.ledgerEmit = { source, kind, pkg, ref, num, payload ->
            com.changliu.app.hub.ledger.Ledger.emit(source, kind, pkg, ref, num, payload)
        }
    }

    /** 一手今日状态：直接读 Room / 设置，不再跨进程。 */
    private fun snapshot(): YishouSnapshot? {
        val y = app as? YishouApp ?: return null
        if (!y.ready) return null
        return runCatching {
            val zone = AppZone.zone
            val now = System.currentTimeMillis()
            val from = Instant.now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            val entries = runBlocking { y.dayLedger.between(from, now + 1) }
            val hit = entries.count { it.mark == LedgerMark.HIT }
            val miss = entries.count { it.mark == LedgerMark.MISS }
            val task = runBlocking { y.database.dao().getCurrentTask() }
            val span = WindowClock.active(now, y.settings.app.value, zone)
            YishouSnapshot(
                appVersion = BuildConfig.VERSION_NAME,
                taskTitle = task?.title.orEmpty(),
                taskGoal = task?.goal.orEmpty(),
                roundsToday = entries.size,
                effectiveToday = hit,
                setSize = LinkWire.SET_SIZE,
                doneSets = hit / LinkWire.SET_SIZE,
                targetSets = 0,
                missToday = miss,
                gateOn = runCatching { A11yGuard.isOn(app) }.getOrDefault(false),
                windowActive = span != null,
                windowEnd = span?.end ?: 0L,
                lastAt = entries.maxOfOrNull { it.at } ?: 0L,
                ledgerLine = Ledger.line(entries).ifBlank { "今天还没练" },
            )
        }.getOrNull()
    }

    /** 总控把一步行动交给一手：同名任务就地更新，否则新建为当前任务。 */
    private suspend fun setTask(order: Map<String, String>): String {
        val y = app as YishouApp
        val dao = y.database.dao()
        val now = System.currentTimeMillis()
        val title = order["title"].orEmpty().ifBlank { "总控交来的一步" }.take(60)
        val goal = order["goal"].orEmpty().take(200)
        val material = order["material"]?.takeIf { it.isNotBlank() }?.take(2000)
        val known = order["known"].orEmpty()
        val stuck = order["stuck"].orEmpty()
        val next = order["next"].orEmpty()

        val current = dao.getCurrentTask()
        if (current != null && current.title == title) {
            dao.editTask(current.copy(goal = goal, material = material), known, stuck, next, now)
        } else {
            dao.createCurrentTask(
                Task(title = title, goal = goal, material = material, isCurrent = true, createdAt = now),
                known,
                stuck,
                next,
                now,
            )
        }
        return "已交给一手：$title"
    }

    private fun startWindow(): String {
        app.startActivity(
            Intent(app, WindowActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("startNow", true),
        )
        return "已开始一手陪练窗口"
    }

    private fun openWindow(): Boolean = runCatching {
        app.startActivity(
            Intent(app, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** 总控此刻对一手的收紧要求。 */
    private fun passPolicy(): PassPolicy = runCatching {
        val store = app.store
        val now = System.currentTimeMillis()
        val inf = com.zongkong.core.Link.influence(
            store.status(now),
            store.config.value,
            store.work.value,
            store.plans.value,
            store.zone,
        )
        PassPolicy(inf.hold, inf.passCap, inf.noOffline, inf.note)
    }.getOrDefault(PassPolicy())
}
