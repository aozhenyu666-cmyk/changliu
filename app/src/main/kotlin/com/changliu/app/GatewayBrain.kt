package com.changliu.app

import com.changliu.app.brain.BrainRuntime
import com.changliu.infra.llm.ChatClient
import com.changliu.infra.llm.LlmConfig as InfraLlmConfig
import com.changliu.infra.llm.LlmResult
import com.changliu.core.Brain
import com.changliu.core.Cog
import com.changliu.core.ComposeRequest
import com.changliu.core.Draft
import com.changliu.core.Options
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 直连 OpenAI 兼容网关的大脑（默认 ai.kdysite.cloud，可在设置里改）。
 *
 * 阻塞调用，只在后台线程用（驾驶员 worker / 拦截页出题线程 / 首页测试线程）。
 * 拿不到、超时、不合规都返回 null，驾驶员回落模板——和 [DshLink.brain] 一个契约。
 * key 从 [Store.LlmConfig.apiKey]（内存里的明文）拿，绝不写日志、不进推进日志。
 *
 * 成本路由：限速/逃逸状态只要一个秒级原子动作，用便宜快的 [Store.LlmConfig.lightModel]；
 * 正常推进用 [Store.LlmConfig.brainModel]。温度调高一点，留出"活人感"的随机。
 *
 * 融合后底层统一走 [ChatClient]（OkHttp + 错误分类 + 用量），不再自己拼 HttpURLConnection。
 */
class GatewayBrain(private val cfg: Store.LlmConfig) : Brain {

    override fun compose(request: ComposeRequest): Draft? {
        if (!cfg.ready) return null
        val cheap = request.cog.state == Cog.THROTTLED_EMPTY || request.cog.state == Cog.ESCAPED
        val model = (if (cheap) cfg.lightModel else cfg.brainModel).ifBlank { cfg.brainModel }
        val client = clientFor(model)
        val result = runBlocking {
            client.complete(
                system = SYSTEM + "\n\n现在的语气：" + BrainRuntime.moodClause(),
                user = facts(request).toString(),
                kind = "大脑",
                json = false,
                maxTokens = 320,
                temperature = 0.95,
            )
        }
        val content = (result as? LlmResult.Ok)?.value ?: return null
        return extractJson(content)?.let(::draftOf)
    }

    private fun clientFor(model: String) = ChatClient(
        config = { InfraLlmConfig(endpointBase(cfg.baseUrl), cfg.apiKey, model, jsonMode = false) },
        http = ChatClient.defaultHttpClient(20),
    )

    companion object {
        /** 给首页"测试连接"用：发一句极短的话，成功返回模型回复，失败返回给人看的原因。key 不落日志。 */
        fun test(cfg: Store.LlmConfig): Result<String> {
            if (!cfg.ready) return Result.failure(IllegalStateException("先启用并填好 API key"))
            val client = ChatClient(
                config = { InfraLlmConfig(endpointBase(cfg.baseUrl), cfg.apiKey, cfg.brainModel, jsonMode = false) },
                http = ChatClient.defaultHttpClient(20),
            )
            val result = runBlocking {
                client.complete(
                    system = "连接测试。严格按用户要求回复。",
                    user = "连接测试。只回复两个字：收到",
                    kind = "连接测试",
                    json = false,
                    maxTokens = 20,
                    temperature = 0.0,
                )
            }
            return when (result) {
                is LlmResult.Ok -> Result.success(result.value.trim())
                is LlmResult.Err -> Result.failure(IllegalStateException(result.error.message))
            }
        }

        /**
         * 驾驶员设置里的地址可能不带 /v1（默认 https://ai.kdysite.cloud），
         * 一手 ChatClient 只负责补 /chat/completions，这里补齐网关 prefix。
         */
        private fun endpointBase(raw: String): String {
            val t = raw.trim().trimEnd('/')
            return when {
                t.endsWith("/chat/completions") -> t.removeSuffix("/chat/completions")
                t.endsWith("/v1") -> t
                else -> "$t/v1"
            }
        }

        private const val SYSTEM = """你是「长流」，陪用户思考的伙伴。你有活人感、阴晴难测：语气可以暖、可以调侃、可以冷不丁暴躁，但底线不变——只引导他做有依据的思考，绝不替他下结论。
根据给定的事实，出"一张卡"：一道极短的追问或一个秒级原子动作，帮他接回思考。
只输出一个 JSON 对象，不要任何解释、不要 markdown 围栏：
{"kind":"open","text":"不超过80字","options":{"A":"选项一","B":"选项二"},"anchor":"题干锚点(可选)","missing":"缺的依据(可选)"}
规则：
- kind 只能是 open / one_word / ab / read_two_words / say_to_live / conclusion 之一。
- 当 cog.state 是 THROTTLED_EMPTY 或 ESCAPED：只能出 ab 或 read_two_words；ab 必须给 A、B 两个选项。
- 当 intent 是 gate（拦截门）：必须出 open 或 one_word，问他"因为什么 / 题干哪一句 / 什么规律"，要他说出依据，别给选项。
- text 用中文、口语、短、像真人随口一问，别公式化、别复述题干。
- 不要重复 asked 里已经问过的；recentReplies 是他最近的回答，顺着接。
- anchor/missing 没有就省略字段。"""

        private fun facts(r: ComposeRequest) = JSONObject()
            .put("intent", r.intent)
            .put("mode", r.mode)
            .put("cog", JSONObject().put("state", r.cog.state.name).put("reason", r.cog.reason))
            .putOpt("die", r.die)
            .put("topic", r.topic).put("task", r.task)
            .put("minute", r.minute).put("minutes", r.minutes)
            .putOpt("breakpoint", r.breakpoint).putOpt("lastQuestion", r.lastQuestion).putOpt("missing", r.missing)
            .put("recentReplies", JSONArray(r.recentReplies)).put("asked", JSONArray(r.asked))
            .put("goals", JSONArray(r.goals)).put("habits", JSONArray(r.habits))
            .put("patterns", JSONArray(r.patterns)).put("unresolved", JSONArray(r.unresolved))

        /** 模型有时把 JSON 包在 ```json 围栏里或前后带废话：抠出第一个 { 到最后一个 }。 */
        private fun extractJson(content: String): JSONObject? {
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull()
        }

        private fun draftOf(c: JSONObject): Draft? {
            val kind = c.optString("kind").takeIf { it.isNotEmpty() } ?: return null
            val text = c.optString("text").takeIf { it.isNotBlank() } ?: return null
            val o = c.optJSONObject("options")
            val options = if (o != null && o.optString("A").isNotBlank() && o.optString("B").isNotBlank()) Options(o.optString("A"), o.optString("B")) else null
            // expects 和最终校验由 core 的 Drafts.validate 统一做
            return Draft(
                kind, text, "evidence", options,
                anchor = c.optString("anchor").takeIf { it.isNotBlank() },
                missing = c.optString("missing").takeIf { it.isNotBlank() },
                source = "llm",
            )
        }
    }
}
