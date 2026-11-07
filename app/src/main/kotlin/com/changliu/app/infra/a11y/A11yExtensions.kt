package com.changliu.app.infra.a11y

import android.content.Context
import com.changliu.core.Verdict
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 三套决策链接入唯一无障碍服务的插口。
 *
 * 融合顺序（实施计划 §5.2）：
 *   总控时间 / 严管（hq）→ 一手关注名单（ys）→ 驾驶员拦截（[com.changliu.app.Intercept]）
 * 前一个返回非 null 就定了；都返回 null 才落到驾驶员规则。
 *
 * ys/zk 源码搬入后，在各自的 App 初始化里 register，不需要再改本服务。
 */
object A11yExtensions {

    fun interface Decider {
        /** 返回 BLOCK 表示要拦；返回 null 表示这一层不管，交给下一层。 */
        fun decide(context: Context, pkg: String, now: Long): Verdict?
    }

    fun interface WindowObserver {
        /** 返回 true 表示这一层已经处理（自己弹了页），唯一 Gate 不再介入。 */
        fun onWindow(context: Context, pkg: String, cls: String?): Boolean
    }

    fun interface Ticker {
        fun tick(context: Context, now: Long)
    }

    // 总控优先（总控时间/严管），其次一手关注名单
    private val hqDeciders = CopyOnWriteArrayList<Decider>()
    private val ysDeciders = CopyOnWriteArrayList<Decider>()
    private val windowObservers = CopyOnWriteArrayList<WindowObserver>()
    private val tickers = CopyOnWriteArrayList<Ticker>()

    fun registerHq(decider: Decider) { hqDeciders.addIfAbsent(decider) }
    fun registerYishou(decider: Decider) { ysDeciders.addIfAbsent(decider) }
    fun addWindowObserver(observer: WindowObserver) { windowObservers.addIfAbsent(observer) }
    fun addTicker(ticker: Ticker) { tickers.addIfAbsent(ticker) }

    fun unregisterHq(decider: Decider) { hqDeciders.remove(decider) }
    fun unregisterYishou(decider: Decider) { ysDeciders.remove(decider) }
    fun removeWindowObserver(observer: WindowObserver) { windowObservers.remove(observer) }
    fun removeTicker(ticker: Ticker) { tickers.remove(ticker) }

    fun decide(context: Context, pkg: String, now: Long): Verdict? {
        for (d in hqDeciders) runCatching { d.decide(context, pkg, now) }.getOrNull()?.let { return it }
        for (d in ysDeciders) runCatching { d.decide(context, pkg, now) }.getOrNull()?.let { return it }
        return null
    }

    fun onWindow(context: Context, pkg: String, cls: String?): Boolean {
        for (o in windowObservers) {
            if (runCatching { o.onWindow(context, pkg, cls) }.getOrDefault(false)) return true
        }
        return false
    }

    fun tick(context: Context, now: Long) {
        for (t in tickers) runCatching { t.tick(context, now) }
    }
}
