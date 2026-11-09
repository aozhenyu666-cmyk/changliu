package com.zongkong.app.data

import com.zongkong.core.AiConfig
import com.zongkong.core.Config
import com.zongkong.core.NotionConfig
import com.zongkong.core.VoiceConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** config.json 的密钥边界：写盘只写密文，解不开或封不上时一个字节都不能丢。 */
class SecretsTest {

    private fun config(apiKey: String, token: String, voiceKey: String = "vk-1") = Config(
        ai = AiConfig(baseUrl = "https://x", apiKey = apiKey, model = "m"),
        notion = NotionConfig(token = token),
        voice = VoiceConfig(baseUrl = "https://v", apiKey = voiceKey),
    )

    @Test
    fun sealReplacesBothSecrets() {
        val sealed = config("sk-1", "ntn_1").sealSecrets { "S($it)" }
        assertEquals("S(sk-1)", sealed.ai.apiKey)
        assertEquals("S(ntn_1)", sealed.notion.token)
        assertEquals("S(vk-1)", sealed.voice.apiKey)
        // 非密钥字段不动
        assertEquals("https://x", sealed.ai.baseUrl)
        assertEquals("m", sealed.ai.model)
    }

    @Test
    fun sealFailureKeepsPlaintextInsteadOfDroppingIt() {
        val sealed = config("sk-1", "ntn_1").sealSecrets { null }
        assertEquals("sk-1", sealed.ai.apiKey)
        assertEquals("ntn_1", sealed.notion.token)
        assertEquals("vk-1", sealed.voice.apiKey)
    }

    @Test
    fun sealSkipsBlank() {
        var calls = 0
        val sealed = config("", "", "").sealSecrets { calls++; "S($it)" }
        assertEquals(0, calls)
        assertEquals("", sealed.ai.apiKey)
        assertEquals("", sealed.notion.token)
        assertEquals("", sealed.voice.apiKey)
    }

    @Test
    fun openRestoresBothSecrets() {
        val opened = config("S(sk-1)", "S(ntn_1)", "S(vk-1)").openSecrets { it.removePrefix("S(").removeSuffix(")") }
        assertEquals("sk-1", opened.ai.apiKey)
        assertEquals("ntn_1", opened.notion.token)
        assertEquals("vk-1", opened.voice.apiKey)
    }

    @Test
    fun openFailureKeepsOriginalSoOldPlaintextStillWorks() {
        // 老版本盘上就是明文，或者换机后密钥没了：解不开也必须原样可用
        val opened = config("sk-plain", "ntn-plain").openSecrets { null }
        assertEquals("sk-plain", opened.ai.apiKey)
        assertEquals("ntn-plain", opened.notion.token)
        assertEquals("vk-1", opened.voice.apiKey)
    }

    @Test
    fun sealThenOpenRoundTrips() {
        val codec = object {
            fun seal(v: String) = "E:" + v.reversed()
            fun open(v: String) = if (v.startsWith("E:")) v.removePrefix("E:").reversed() else null
        }
        val back = config("sk-1", "ntn_1").sealSecrets { codec.seal(it) }.openSecrets { codec.open(it) }
        assertEquals("sk-1", back.ai.apiKey)
        assertEquals("ntn_1", back.notion.token)
        assertEquals("vk-1", back.voice.apiKey)
    }
}
