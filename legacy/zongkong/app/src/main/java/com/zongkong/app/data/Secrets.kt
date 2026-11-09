package com.zongkong.app.data

import android.util.Log
import com.changliu.infra.secret.SecretBox
import com.zongkong.core.Config

private const val TAG = "ZkSecrets"

/**
 * config.json 落盘前后的密钥处理：写盘前把 AI key / Notion token / 语音 key 换成密文，读盘后解回来。
 *
 * 解不开或封不上的（老版本明文、换机丢密钥、密钥库出事）一律**原样保留**：
 * 宁可弱一阵，也不能把用户配好的 key 弄丢。原样留下的明文在下次写入时会被封起来。
 * [seal] / [open] 可注入，方便脱离 AndroidKeyStore 在单元测试里验这两条边界。
 */
internal fun Config.sealSecrets(seal: (String) -> String? = SecretBox::seal): Config {
    fun s(v: String): String {
        if (v.isBlank()) return v
        val sealed = seal(v)
        if (sealed == null) Log.w(TAG, "封不上，本次按明文写")
        return sealed ?: v
    }
    return copy(
        ai = ai.copy(apiKey = s(ai.apiKey)),
        notion = notion.copy(token = s(notion.token)),
        voice = voice.copy(apiKey = s(voice.apiKey)),
    )
}

internal fun Config.openSecrets(open: (String) -> String? = SecretBox::open): Config = copy(
    ai = ai.copy(apiKey = open(ai.apiKey) ?: ai.apiKey),
    notion = notion.copy(token = open(notion.token) ?: notion.token),
    voice = voice.copy(apiKey = open(voice.apiKey) ?: voice.apiKey),
)
