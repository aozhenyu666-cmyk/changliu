package com.changliu.app.infra.settings

import android.content.Context
import com.changliu.app.ChangliuApp
import com.changliu.app.Engine
import com.changliu.app.GatewayBrain
import com.changliu.app.Store
import com.changliu.infra.llm.LlmConfig as InfraLlmConfig
import com.yishou.app.speech.SpeechConfig

/**
 * 三端统一设置：一次填好网关/Key/模型，同步给驾驶员、一手、总控。
 *
 * key 的落盘方式各处不同但都合规：
 *  - 驾驶员 llm.json：SecretBox 密文
 *  - 一手 yishou_settings：EncryptedSharedPreferences
 *  - 总控 config.json：写盘前 sealSecrets 密文
 * 明文只在本进程内存里短暂存在，不进日志。
 */
data class UnifiedConfig(
    val baseUrl: String = Store.DEFAULT_GATEWAY,
    val apiKey: String = "",
    val brainModel: String = "gemini-4-argon",
    val lightModel: String = "gemini-2.5-flash-nothinking",
    val visionModel: String = "",
    val speechBaseUrl: String = "",
    val speechApiKey: String = "",
    val sttModel: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    val notionToken: String = "",
    val notionParent: String = "",
)

data class UnifiedStatus(
    val driverReady: Boolean,
    val yishouReady: Boolean,
    val zongkongReady: Boolean,
    val speechReady: Boolean,
    val notionReady: Boolean,
) {
    fun summary(): String = buildString {
        append(if (driverReady || yishouReady || zongkongReady) "大模型已配置" else "大模型未配置")
        append(" · ")
        append(if (speechReady) "语音已配置" else "语音未配置")
        append(" · ")
        append(if (notionReady) "Notion 已连接" else "Notion 未连接")
    }
}

object UnifiedSettings {

    private fun app(ctx: Context) = ctx.applicationContext as ChangliuApp

    fun read(ctx: Context): UnifiedConfig {
        val a = app(ctx)
        val d = runCatching { Engine.store.llmConfig() }.getOrNull()
        val ys = runCatching { a.settings.llm.value }.getOrNull()
        val yv = runCatching { a.settings.vision.value }.getOrNull()
        val ysp = runCatching { a.settings.speech.value }.getOrNull()
        val zc = runCatching { a.store.config.value }.getOrNull()

        val dKey = d?.apiKey.orEmpty()
        val ysKey = ys?.apiKey.orEmpty()
        val zKey = zc?.ai?.apiKey.orEmpty()
        val key = firstNonBlank(dKey, ysKey, zKey)

        val base = when {
            dKey.isNotBlank() -> d?.baseUrl.orEmpty()
            ysKey.isNotBlank() -> ys?.baseUrl.orEmpty()
            zKey.isNotBlank() -> zc?.ai?.baseUrl.orEmpty()
            else -> Store.DEFAULT_GATEWAY
        }.ifBlank { Store.DEFAULT_GATEWAY }

        val brain = when {
            dKey.isNotBlank() -> d?.brainModel.orEmpty()
            ysKey.isNotBlank() -> ys?.model.orEmpty()
            zKey.isNotBlank() -> zc?.ai?.model.orEmpty()
            else -> ""
        }.ifBlank { "gemini-4-argon" }

        val light = d?.lightModel?.ifBlank { "gemini-2.5-flash-nothinking" } ?: "gemini-2.5-flash-nothinking"
        val vision = firstNonBlank(yv?.model.orEmpty(), zc?.ai?.visionModel.orEmpty())

        val speechBase = firstNonBlank(ysp?.baseUrl.orEmpty(), zc?.voice?.baseUrl.orEmpty())
        val speechKey = firstNonBlank(ysp?.apiKey.orEmpty(), zc?.voice?.apiKey.orEmpty())
        val stt = firstNonBlank(ysp?.sttModel.orEmpty(), zc?.voice?.sttModel.orEmpty())
        val tts = firstNonBlank(ysp?.ttsModel.orEmpty(), zc?.voice?.ttsModel.orEmpty())
        val voice = firstNonBlank(ysp?.ttsVoice.orEmpty(), zc?.voice?.ttsVoice.orEmpty())

        val notion = zc?.notion
        return UnifiedConfig(
            baseUrl = base,
            apiKey = key,
            brainModel = brain,
            lightModel = light,
            visionModel = vision,
            speechBaseUrl = speechBase,
            speechApiKey = speechKey,
            sttModel = stt,
            ttsModel = tts,
            ttsVoice = voice,
            notionToken = notion?.token.orEmpty(),
            notionParent = notion?.parentPage.orEmpty(),
        )
    }

    fun apply(ctx: Context, c: UnifiedConfig) {
        val a = app(ctx)
        val base = c.baseUrl.trim().ifBlank { Store.DEFAULT_GATEWAY }
        val key = c.apiKey.trim()
        val brain = c.brainModel.trim().ifBlank { "gemini-4-argon" }
        val light = c.lightModel.trim().ifBlank { "gemini-2.5-flash-nothinking" }
        val vision = c.visionModel.trim()

        // 驾驶员（密文落盘；清空时连旧密文一起删）
        runCatching {
            if (key.isBlank()) Engine.store.clearLlmKey()
            Engine.store.saveLlmConfig(
                Store.LlmConfig(
                    enabled = key.isNotBlank(),
                    baseUrl = base,
                    brainModel = brain,
                    lightModel = light,
                    apiKey = key,
                ),
            )
        }
        // 一手（EncryptedSharedPreferences）
        runCatching { a.settings.saveLlm(InfraLlmConfig(base, key, brain, jsonMode = true)) }
        if (vision.isNotBlank()) {
            runCatching { a.settings.saveVision(InfraLlmConfig(base, key, vision, jsonMode = false)) }
        }
        runCatching {
            a.settings.saveSpeech(
                SpeechConfig(
                    baseUrl = c.speechBaseUrl.trim(),
                    apiKey = c.speechApiKey.trim(),
                    sttModel = c.sttModel.trim(),
                    ttsModel = c.ttsModel.trim(),
                    ttsVoice = c.ttsVoice.trim(),
                ),
            )
        }
        // 总控（config.json 写盘前 sealSecrets）
        runCatching {
            a.store.updateConfig { cfg ->
                cfg.copy(
                    ai = cfg.ai.copy(
                        baseUrl = base,
                        apiKey = key,
                        model = brain,
                        jsonMode = true,
                        visionModel = vision,
                    ),
                    notion = cfg.notion.copy(
                        token = c.notionToken.trim(),
                        parentPage = c.notionParent.trim().ifBlank { cfg.notion.parentPage },
                    ),
                    voice = cfg.voice.copy(
                        baseUrl = c.speechBaseUrl.trim(),
                        apiKey = c.speechApiKey.trim(),
                        sttModel = c.sttModel.trim(),
                        ttsModel = c.ttsModel.trim(),
                        ttsVoice = c.ttsVoice.trim(),
                    ),
                )
            }
        }
    }

    fun status(ctx: Context): UnifiedStatus {
        val a = app(ctx)
        val d = runCatching { Engine.store.llmConfig() }.getOrNull()
        val ys = runCatching { a.settings.llm.value }.getOrNull()
        val ysp = runCatching { a.settings.speech.value }.getOrNull()
        val zc = runCatching { a.store.config.value }.getOrNull()
        return UnifiedStatus(
            driverReady = d?.ready == true,
            yishouReady = ys?.isComplete == true,
            zongkongReady = zc?.ai?.ready == true,
            speechReady = ysp?.sttReady == true || ysp?.ttsReady == true,
            notionReady = zc?.notion?.token?.isNotBlank() == true,
        )
    }

    /** 用当前表单值测一次连接（不落盘）。调用方在 IO 线程执行。 */
    fun test(ctx: Context, c: UnifiedConfig): Result<String> {
        val cfg = Store.LlmConfig(
            enabled = true,
            baseUrl = c.baseUrl.trim().ifBlank { Store.DEFAULT_GATEWAY },
            brainModel = c.brainModel.trim().ifBlank { "gemini-4-argon" },
            lightModel = c.lightModel.trim(),
            apiKey = c.apiKey.trim(),
        )
        return GatewayBrain.test(cfg)
    }

    fun summary(ctx: Context): String = status(ctx).summary()

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}
