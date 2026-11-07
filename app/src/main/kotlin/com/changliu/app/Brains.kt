package com.changliu.app

import com.changliu.core.Brain

/**
 * 选大脑：优先直连网关（启用且填了 key），其次配对的大肥鱼（DSH Go），都没有就 null。
 * 返回 null 时驾驶员/拦截门回落本地模板——离线也能用。
 */
object Brains {
    fun best(store: Store): Brain? {
        val llm = store.llmConfig()
        if (llm.ready) return GatewayBrain(llm)
        return store.pairing()?.let(DshLink::brain)
    }
}