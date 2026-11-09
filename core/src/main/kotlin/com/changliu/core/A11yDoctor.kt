package com.changliu.core

/**
 * 无障碍医生：判断唯一无障碍服务现在该怎么救。纯函数，Android 外壳只负责采样和执行。
 *
 * 三种病：
 *  1. 被摘掉（不在系统的启用列表里，或总开关是 0）→ 写回去；
 *  2. 僵死（在列表里、开关也开着，但服务没连上，或者亮屏好几分钟一个事件都没有——
 *     就是系统设置里显示“此服务出现故障”的那种）→ 先摘掉再写回，等于替你“关一下再开”；
 *  3. 久了容易僵（国产 ROM 常见）→ 可选的定时重启：熄屏时、没在答题 / 总控时间里才做。
 *
 * 没有写权限（WRITE_SECURE_SETTINGS / 设备所有者 / Shizuku 都没有）时只能提醒你手动去开。
 */
object A11yDoctor {

    enum class Action { NONE, WRITE_BACK, RESTART, NOTIFY_MANUAL }

    data class Config(
        /** 在列表里却没连上，多久算僵死。 */
        val disconnectedMs: Long = 90_000L,
        /** 亮屏时多久一个事件都没有算僵死（静止页面也会偶尔有事件，留宽一点）。 */
        val silentMs: Long = 10 * 60_000L,
        /** 定时重启的间隔；0 = 不定时重启。 */
        val scheduledEveryMs: Long = 6 * 3_600_000L,
        /** 两次重启至少隔多久（防止来回抖）。 */
        val cooldownMs: Long = 10 * 60_000L,
        /** 一天最多重启几次（超过就只提醒，别和系统打架）。 */
        val maxRestartsPerDay: Int = 12,
    )

    data class Observation(
        val now: Long,
        /** 组件在 enabled_accessibility_services 里。 */
        val listed: Boolean,
        /** accessibility_enabled == 1。 */
        val switchOn: Boolean,
        /** 服务实例真的连上了（onServiceConnected 之后、onUnbind 之前）。 */
        val connected: Boolean,
        /** 列表里有、却没连上，是从什么时候开始的（0 = 现在没这个问题）。 */
        val disconnectedSince: Long,
        /** 最近一次收到无障碍事件（或连上）的时刻。 */
        val lastEventAt: Long,
        val screenOn: Boolean,
        /** 有办法自己写 secure settings。 */
        val canWrite: Boolean,
        val lastRestartAt: Long,
        val restartsToday: Int,
        /** 上一次定时重启（或任何重启）的时刻。 */
        val lastScheduledAt: Long,
        /** 正在答题 / 总控时间 / 通话里：别动它。 */
        val busy: Boolean,
    )

    data class Decision(val action: Action, val reason: String) {
        companion object {
            val OK = Decision(Action.NONE, "")
        }
    }

    fun decide(o: Observation, c: Config = Config()): Decision {
        // 1. 被摘掉
        if (!o.listed || !o.switchOn) {
            return if (o.canWrite) Decision(Action.WRITE_BACK, if (!o.listed) "无障碍被系统摘掉了" else "无障碍总开关被关了")
            else Decision(Action.NOTIFY_MANUAL, "无障碍被关了，没有写回权限")
        }
        val canRestart = o.canWrite &&
            o.now - o.lastRestartAt >= c.cooldownMs &&
            o.restartsToday < c.maxRestartsPerDay
        // 2. 僵死：开着但没连上
        if (!o.connected && o.disconnectedSince > 0 && o.now - o.disconnectedSince >= c.disconnectedMs) {
            return when {
                canRestart -> Decision(Action.RESTART, "开着但没在运行（${(o.now - o.disconnectedSince) / 1000} 秒）")
                o.canWrite -> Decision.OK // 冷却中或今天次数用完：等一等，别和系统打架
                else -> Decision(Action.NOTIFY_MANUAL, "开着但没在运行，需要手动关一下再开")
            }
        }
        // 2'. 僵死：亮屏很久一个事件都没有
        if (o.connected && o.screenOn && o.lastEventAt > 0 && o.now - o.lastEventAt >= c.silentMs) {
            return if (canRestart) Decision(Action.RESTART, "亮屏 ${(o.now - o.lastEventAt) / 60_000} 分钟收不到事件")
            else Decision.OK
        }
        // 3. 定时重启：熄屏、不忙、到点
        if (c.scheduledEveryMs > 0 && canRestart && !o.screenOn && !o.busy &&
            o.now - maxOf(o.lastScheduledAt, o.lastRestartAt) >= c.scheduledEveryMs
        ) {
            return Decision(Action.RESTART, "定时重启（每 ${c.scheduledEveryMs / 3_600_000} 小时一次）")
        }
        return Decision.OK
    }

    /** 重启 = 先把自己从列表里拿掉，再加回去。只动自己的组件。 */
    fun without(current: String?, me: String): String =
        current.orEmpty().split(':').map(String::trim).filter { it.isNotEmpty() && !it.equals(me, ignoreCase = true) }.joinToString(":")

    fun with(current: String?, me: String): String {
        val set = LinkedHashSet<String>()
        current.orEmpty().split(':').map(String::trim).filter { it.isNotEmpty() }.forEach { set.add(it) }
        if (set.none { it.equals(me, ignoreCase = true) }) set.add(me)
        return set.joinToString(":")
    }
}
