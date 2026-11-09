package com.changliu.app.hub.policy

import android.content.Context
import com.yishou.app.YishouApp
import com.yishou.app.card.Card
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

data class BankQuestion(val text: String, val cardId: Long)

/** 从一手题库里挑一道现有题，拦截时优先用它（不再每次都临时编题）。 */
object QuestionBank {
    fun pick(ctx: Context, limit: Int = 200): BankQuestion? {
        val app = ctx.applicationContext as? YishouApp ?: return null
        if (!app.ready) return null
        val rows = runCatching { runBlocking { app.database.cards().recent(limit) } }.getOrNull()
            ?: return null
        return rows.shuffled().firstNotNullOfOrNull { row ->
            runCatching {
                val question = Card.parse(JSONObject(row.json)).question
                question.takeIf { it.isNotBlank() }?.let { BankQuestion(it, row.id) }
            }.getOrNull()
        }
    }
}
