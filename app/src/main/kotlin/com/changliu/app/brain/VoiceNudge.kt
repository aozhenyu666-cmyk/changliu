package com.changliu.app.brain

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * L1 语音先开口（设计方案 §7.3）：不方便打字时先把一句话说出来。
 * 只做 TTS，不录音、不需要麦克风权限；用户可在「我的」里关掉。
 */
object VoiceNudge {
    private const val PREFS = "brain_voice"
    private const val KEY = "enabled"

    private var tts: TextToSpeech? = null
    private var ready = false

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY, enabled).apply()
    }

    @Synchronized
    fun speak(ctx: Context, text: String) {
        if (!enabled(ctx) || text.isBlank()) return
        if (tts == null) {
            val app = ctx.applicationContext
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) runCatching { tts?.language = Locale.SIMPLIFIED_CHINESE }
            }
        }
        if (!ready) return
        runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "changliu-${System.currentTimeMillis()}")
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
