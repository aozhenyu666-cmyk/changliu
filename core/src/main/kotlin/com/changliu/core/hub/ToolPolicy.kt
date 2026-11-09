package com.changliu.core.hub

/**
 * 工具权限分级（计划书第五节）。AI 可以自己收紧，不能自己放松。
 * T3 必须本机确认；T2 立即执行但 10 分钟内可撤销。
 */
enum class ToolLevel { T0, T1, T2, T3 }

data class ToolRule(
    val name: String,
    /** 默认等级（未给出"改后更严/更松"的语境时）。 */
    val defaultLevel: ToolLevel,
)

object ToolPolicy {
    fun levelFor(rule: ToolRule, stricter: Boolean? = null): ToolLevel = when {
        rule.defaultLevel == ToolLevel.T3 && stricter == false -> ToolLevel.T3
        rule.defaultLevel == ToolLevel.T2 && stricter == true -> ToolLevel.T2
        rule.defaultLevel == ToolLevel.T2 && stricter == false -> ToolLevel.T3
        else -> rule.defaultLevel
    }

    fun needsConfirm(level: ToolLevel): Boolean = level == ToolLevel.T3

    fun mayUndo(level: ToolLevel): Boolean = level == ToolLevel.T2

    /** 硬边界：这些动作永远不允许（模型说也不行）。 */
    fun forbidden(tool: String): Boolean = tool in setOf(
        "disable_accessibility", "stop_self", "uninstall_self",
        "block_emergency_app", "upload_screenshot", "edit_charter", "delete_ledger",
    )
}
