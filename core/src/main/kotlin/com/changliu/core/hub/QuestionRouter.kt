package com.changliu.core.hub

/** 出题来源，按优先级从高到低。 */
enum class QuestionSource { MAINLINE, BANK, MATERIAL, MODEL, TEMPLATE }

data class QuestionChoice(val source: QuestionSource, val text: String = "")

/**
 * 出题路由：按「主线断点 → 一手题库 → 素材+骰子 → 模型 → 模板」取第一个可用的。
 * 纯函数，调用方把"当前哪些源可用"传进来。
 */
object QuestionRouter {
    private val ORDER = listOf(
        QuestionSource.MAINLINE,
        QuestionSource.BANK,
        QuestionSource.MATERIAL,
        QuestionSource.MODEL,
        QuestionSource.TEMPLATE,
    )

    fun choose(available: Collection<QuestionSource>): QuestionSource =
        ORDER.firstOrNull { it in available } ?: QuestionSource.TEMPLATE

    fun order(): List<QuestionSource> = ORDER
}
