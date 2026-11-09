package com.changliu.infra.fusion

/**
 * 进程内融合钩子。
 *
 * ys / zk 是平级 library，彼此不能直接依赖；原来靠 ContentProvider + 签名校验通信。
 * 融合成一个 APK 后，由 :app 在启动时把这些钩子接到真实实现，调用方优先走进程内，
 * 没接钩子时仍保留旧的跨 App 路径（用于单独运行/测试）。
 */
data class YishouSnapshot(
    val appVersion: String = "",
    val taskTitle: String = "",
    val taskGoal: String = "",
    val roundsToday: Int = 0,
    val effectiveToday: Int = 0,
    val setSize: Int = 3,
    val doneSets: Int = 0,
    val targetSets: Int = 0,
    val missToday: Int = 0,
    val gateOn: Boolean = false,
    val windowActive: Boolean = false,
    val windowEnd: Long = 0L,
    val lastAt: Long = 0L,
    val ledgerLine: String = "",
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "appVersion" to appVersion,
        "taskTitle" to taskTitle,
        "taskGoal" to taskGoal,
        "roundsToday" to roundsToday,
        "effectiveToday" to effectiveToday,
        "setSize" to setSize,
        "doneSets" to doneSets,
        "targetSets" to targetSets,
        "missToday" to missToday,
        "gateOn" to gateOn,
        "windowActive" to windowActive,
        "windowEnd" to windowEnd,
        "lastAt" to lastAt,
        "ledgerLine" to ledgerLine,
    )
}

/** 总控对一手的收紧要求（由 :zk 的 Link.influence 算出来，:ys 放行时读取）。 */
data class PassPolicy(
    val hold: Boolean = false,
    val passCap: Int = 0,
    val noOffline: Boolean = false,
    val note: String = "",
)

object FusionHooks {
    /** 一手是否可用（融合包里恒为 true）。 */
    @Volatile
    var yishouInstalled: (() -> Boolean)? = null

    @Volatile
    var yishouSnapshot: (() -> YishouSnapshot?)? = null

    /** 把总控的一步行动交给一手；返回给用户看的一句话。 */
    @Volatile
    var yishouSetTask: (suspend (Map<String, String>) -> String?)? = null

    /** 在一手里开一局陪练窗口。 */
    @Volatile
    var yishouStartWindow: (suspend () -> String?)? = null

    @Volatile
    var openYishouWindow: (() -> Boolean)? = null

    /** 一手发放行前读取：总控严管/专注锁/落后时收紧。 */
    @Volatile
    var zongkongPassPolicy: (() -> PassPolicy)? = null

    /** 统一账本写入（三边 legacy 模块用；app 接到 Ledger.emit）。 */
    @Volatile
    var ledgerEmit: ((source: String, kind: String, pkg: String, ref: String, num: Long, payload: String) -> Unit)? = null
}
