package com.zongkong.core

import com.changliu.infra.llm.ChatClient
import com.changliu.infra.llm.LlmConfig
import com.changliu.infra.llm.LlmError
import com.changliu.infra.llm.LlmResult
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** 大模型接口。验收、测试连接都走这里，方便测试时替换。 */
fun interface LlmApi {
    suspend fun complete(system: String, user: String): ApiResult<String>
}

/**
 * 长流融合：真正的 HTTP 实现在 :infra（驾驶员、一手、总控共用一份）。
 * 这里只做两件事——把总控的 [AiConfig] 翻译成 [LlmConfig]，把 [LlmResult] 翻回 [ApiResult]。
 * 别在本文件里写请求逻辑；要改就改 :infra。
 */
class LlmClient(
    private val config: () -> AiConfig,
    private val http: OkHttpClient = Net.client(),
) : LlmApi {

    private val chat = ChatClient(config = { config().toLlmConfig() }, http = http)

    override suspend fun complete(system: String, user: String): ApiResult<String> {
        val cfg = config()
        // 这句措辞是总控自己给用户看的，和 :infra 的兜底文案不同，保持不变
        if (!cfg.ready) return ApiResult.Err("还没有配置 AI 接口（设置 → AI 接口）")
        return when (val r = chat.complete(system, user, json = cfg.jsonMode, temperature = 0.2)) {
            is LlmResult.Ok -> ApiResult.Ok(r.value)
            is LlmResult.Err -> ApiResult.Err(r.error.message)
        }
    }

    companion object {
        /** 用户可能填 https://api.deepseek.com、…/v1 或完整的 …/chat/completions。 */
        fun chatUrl(baseUrl: String): String? = ChatClient.chatCompletionsUrl(baseUrl)

        /** 状态码措辞统一走 :infra，别在这里再写一份。 */
        fun httpMessage(code: Int, detail: String): String = LlmError.httpMessage(code, detail)

        /** 从模型回复里取出 JSON 对象。有的模型会包一层 ```json，或者前后多说几句。 */
        fun extractJson(text: String): JsonObject? {
            Net.parseObject(text.trim())?.let { return it }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return Net.parseObject(text.substring(start, end + 1))
        }
    }
}

private fun AiConfig.toLlmConfig() = LlmConfig(
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    jsonMode = jsonMode,
)
