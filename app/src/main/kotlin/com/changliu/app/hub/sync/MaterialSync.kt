package com.changliu.app.hub.sync

import android.content.Context
import com.yishou.app.YishouApp
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialSource
import com.zongkong.app.ZkHost
import com.zongkong.core.vein.VStatus

/**
 * 四部 → 一手的自动炼题管道：脉络里还没验证的假设，自动落成一手素材池里的一段材料。
 *
 * 只做单向、只写不删：脉络是源头，一手是弹药库。素材进了池子，拦截时的出题路由
 * （QuestionRouter：主线断点 → 题库 → 素材 → 模型 → 模板）就能拿它问你，
 * 于是"你在四部里想的东西"几小时后会变成拦住你刷抖音的那道题。
 *
 * 不自动生成四选一题卡：没有真实干扰项的题是假题，宁可用素材+模型现场出。
 */
object MaterialSync {

    private const val PREFS = "hub_sync"
    private const val KEY_SEEN = "vein_seen"
    private const val MAX_TEXT = 2000
    /** 一次最多落多少条，避免首次同步把整个脉络刷进素材池。 */
    private const val BATCH = 20

    /** 返回这次新落进素材池的条数。 */
    fun run(ctx: Context): Int {
        val y = ctx.applicationContext as? YishouApp ?: return 0
        if (!y.ready) return 0
        val host = ctx.applicationContext as? ZkHost ?: return 0

        val nodes = runCatching { host.store.vein.value.nodes }.getOrNull() ?: return 0
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableSet()

        val fresh = nodes
            .filter { it.vStatus == VStatus.HYPO && it.id !in seen }
            .filter { it.claim.isNotBlank() || it.note.isNotBlank() }
            .take(BATCH)
        if (fresh.isEmpty()) return 0

        var added = 0
        val now = System.currentTimeMillis()
        for (n in fresh) {
            val text = buildString {
                if (n.claim.isNotBlank()) append(n.claim.trim())
                if (n.note.isNotBlank()) { if (isNotEmpty()) append('\n'); append(n.note.trim()) }
                if (n.verify.isNotBlank()) { if (isNotEmpty()) append('\n'); append("验证：").append(n.verify.trim()) }
                if (isNotEmpty()) append('\n').append("（脉络·").append(n.system).append('）')
            }.take(MAX_TEXT)
            if (text.isBlank()) continue
            val ok = runCatching {
                kotlinx.coroutines.runBlocking {
                    y.database.think().insertMaterial(
                        Material(
                            title = n.title.take(60),
                            text = text,
                            source = MaterialSource.TYPED,
                            createdAt = now,
                        ),
                    )
                }
            }.isSuccess
            if (ok) {
                added++
                seen += n.id
            }
        }
        if (added > 0) runCatching { prefs.edit().putStringSet(KEY_SEEN, seen).apply() }
        return added
    }
}
