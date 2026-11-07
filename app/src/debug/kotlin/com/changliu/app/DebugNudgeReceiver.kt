package com.changliu.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.changliu.app.brain.VoiceNudge
import com.changliu.app.infra.overlay.ProactiveOverlay

/** 只在调试构建里存在：用 adb 广播手动触发一次 L0/L1 搭话，便于验收排版与 TTS。 */
class DebugNudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val text = intent?.getStringExtra("text") ?: "调试：这是长流的主动搭话。"
        val mood = intent?.getStringExtra("mood") ?: "调试"
        if (intent?.getBooleanExtra("voice", false) == true) VoiceNudge.speak(context, text)
        ProactiveOverlay.show(context, text, mood)
    }
}
