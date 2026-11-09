package com.changliu.infra.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 把 API key 这类敏感串用 AndroidKeyStore 里的 AES/GCM 密钥封起来再落盘。
 *
 * 密钥本身在系统密钥库里（多数机型有硬件 backing），导不出来；盘上只有密文 + IV。
 * 明文 key 只在内存里短暂存在，不写日志、不进会话推进日志、不上报。
 * 取不到时（换机、密钥被清、密文损坏）返回 null，调用方按"没配 key"处理，回落模板。
 *
 * alias 用的是驾驶员原有的 `changliu.llm.key`：三端合并后共用同一把系统密钥，
 * 驾驶员盘上已有的密文照样能打开，不需要迁移。
 */
object SecretBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "changliu.llm.key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** 明文 -> "base64(iv).base64(密文)"；失败返回 null（调用方宁可不存也不落明文）。 */
    fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." + Base64.encodeToString(ct, Base64.NO_WRAP)
    }.getOrNull()

    /** 密文 -> 明文；失败返回 null。 */
    fun open(sealed: String): String? = runCatching {
        val parts = sealed.split('.')
        if (parts.size != 2) return@runCatching null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}
