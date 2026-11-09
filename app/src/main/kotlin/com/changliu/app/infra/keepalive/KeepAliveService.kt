package com.changliu.app.infra.keepalive

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.changliu.app.A11yGuard
import com.changliu.app.AwarenessRuntime
import com.changliu.app.Engine
import com.changliu.app.Intercept
import com.changliu.app.MainActivity
import com.changliu.app.R
import com.changliu.app.UsageMonitor
import com.changliu.app.hub.ledger.Ledger
import com.zongkong.app.healthwire.LocalGuardian as ZkLocalGuardian
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长流唯一常驻服务：合并驾驶员 LockService（陪练/锁定的前台服务 + 音频状态监听）
 * 与 GuardService（拦截守护：15 秒/60 秒复查无障碍，掉了立刻写回）。
 *
 * 为什么合成一个：国产 ROM 一键清理，前台服务掉一个和掉三个没区别，
 * 但两个服务各自持有一条常驻通知会让用户困惑。这里按当前状态切通知文案：
 *   陪练中 → "长流 · 陪练中：<label>"（顺带挂 AudioSignals）
 *   守护中 → "长流 · 拦截守护：<无障碍状态>"
 *
 * 融合后总控 KeepAliveService / 一手 WindowService 的职责也并入本类（阶段三）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTE_ID = 4
        private const val ON_MS = 15_000L
        private const val OFF_MS = 60_000L
        private const val BACKSTOP_MS = 5 * 60_000L
        private const val EXTRA_LABEL = "label"

        @Volatile var running = false
            private set

        @Volatile private var lockActive = false
        @Volatile private var lockLabel = ""

        /** 锁定/陪练、拦截或监督任一开着，就需要常驻。 */
        fun needed(context: Context): Boolean =
            lockActive || Engine.locked || Intercept.enabled(context) ||
                AwarenessRuntime.active || Engine.store.settings().peerGuard

        fun sync(context: Context) {
            if (needed(context)) start(context) else stop(context)
        }

        fun start(context: Context) {
            if (running) return
            val i = Intent(context, KeepAliveService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        /** 陪练/锁定开始：前台服务 + 音频状态监听。 */
        fun startLock(context: Context, label: String) {
            lockActive = true
            lockLabel = label
            start(context)
        }

        /** 陪练/锁定结束：撤掉音频监听；拦截守护还开着就继续常驻。 */
        fun stopLock(context: Context) {
            lockActive = false
            lockLabel = ""
            sync(context)
        }

        fun stop(context: Context) {
            lockActive = false
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }

        /** 进程被杀后的兜底：5 分钟一次的闹钟会把服务再拉起来（系统限制下可能延后）。 */
        fun scheduleBackstop(context: Context) {
            runCatching {
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val pi = PendingIntent.getBroadcast(
                    context, 0, Intent(context, KeepAlarmReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + BACKSTOP_MS, pi)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var screenOn = true
    private var lastLabel = ""
    @Volatile private var lastPeerAt = 0L
    @Volatile private var lastZkCheck = 0L
    @Volatile private var lastRollupAt = 0L
    @Volatile private var lastZkSyncAt = 0L
    @Volatile private var lastSyncMaterialsAt = 0L
    private val checking = AtomicBoolean(false)
    private var signals: AudioSignals? = null

    private fun locked(): Boolean = lockActive || Engine.locked

    private val triggers = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; checkOnce() }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; checkOnce() }
                // 解锁、装包、改时间、充电：都顺手复查一次，不用等下一个 15 秒
                else -> checkOnce()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            checkOnce()
            main.postDelayed(this, if (screenOn) ON_MS else OFF_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        registerTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() }?.let {
            lockActive = true
            lockLabel = it
        }
        // 前台服务必须尽快 startForeground；失败就停掉自己，避免系统判 ANR
        val started = runCatching { startForegroundNotification() }.isSuccess
        if (!started) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        main.removeCallbacks(ticker)
        main.post(ticker)
        scheduleBackstop(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉后仍然常驻；进程真被杀还有闹钟和 JobScheduler 兜底
        scheduleBackstop(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(ticker)
        runCatching { unregisterReceiver(triggers) }
        stopAudio()
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) }
        super.onDestroy()
    }

    private fun checkOnce() {
        if (!needed(this)) {
            stopSelf()
            return
        }
        syncAudio()
        if (!checking.compareAndSet(false, true)) return
        Thread {
            try {
                val on = A11yGuard.isOn(this)
                if (!on) {
                    val healed = A11yGuard.ensure(this)
                    if (!healed && !A11yGuard.canHeal(this)) A11yGuard.notifyDown(this)
                } else {
                    A11yGuard.clearDownNotice(this)
                }
                val now = System.currentTimeMillis()
                if (now - lastPeerAt > 5 * 60_000L) {
                    lastPeerAt = now
                    A11yGuard.ensurePeers(this)
                }
                UsageMonitor.check(this)
                // P4 主动唤醒（本地规则，只出 L0 卡）
                runCatching { com.changliu.app.hub.wake.WakeScheduler.tick(this) }
                // 账本 rollup：每 10 分钟一次，顺带清理 180 天前的数据
                if (now - lastRollupAt > 10 * 60_000L) {
                    lastRollupAt = now
                    runCatching { kotlinx.coroutines.runBlocking { Ledger.rollup(this@KeepAliveService) } }
                }
                // 四部 → 一手：脉络里的假设落成素材，拦截时才有专属题可问
                if (now - lastSyncMaterialsAt > 10 * 60_000L) {
                    lastSyncMaterialsAt = now
                    runCatching { com.changliu.app.hub.sync.MaterialSync.run(this@KeepAliveService) }
                }
                // 总控原来的 60 秒本地规则兜底（紧急暂停/锁屏）保留；
                // SelfHeal/Watchdog 属于旧 GuardService 的健康协议，融合后会误报「无障碍被关」，
                // 健康检查统一交给 A11yGuard（实施计划 §5.4）。
                if (now - lastZkCheck > 60_000L) {
                    lastZkCheck = now
                    runCatching { ZkLocalGuardian.tick(this) }
                }
                // 总控 Notion 双向同步：原 GuardService 每 15 分钟一次，这里补回来（没配就快速返回）
                if (now - lastZkSyncAt > 15 * 60_000L) {
                    lastZkSyncAt = now
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            (applicationContext as com.zongkong.app.ZkHost).actions.sync()
                        }
                    }
                }
                updateNotification(on)
                scheduleBackstop(this)
            } finally {
                checking.set(false)
            }
        }.start()
    }

    private fun registerTriggers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(triggers, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(triggers, filter)
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "长流常驻", NotificationManager.IMPORTANCE_LOW))
        val n = notification(if (locked()) "陪练中" else "守护中", lockTitle())
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTE_ID, n)
    }

    private fun updateNotification(a11yOn: Boolean) {
        // 服务已经被停掉（needed=false 触发的 stopSelf）时别再补通知
        if (!running || !needed(this)) return
        val label = if (locked()) {
            lockLabel.ifBlank { "陪练中" }
        } else if (a11yOn) {
            "无障碍已开"
        } else if (A11yGuard.canHeal(this)) {
            "正在把无障碍写回去"
        } else {
            "无障碍没开，需要手动授权"
        }
        if (label == lastLabel) return
        lastLabel = label
        main.post {
            runCatching {
                getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification(label, lockTitle()))
            }
        }
    }

    private fun lockTitle(): String = if (locked()) "长流 · 陪练中" else "长流 · 拦截守护"

    private fun notification(label: String, title: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_driver)
            .setContentTitle(title)
            .setContentText(label)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** 锁定期间挂着音频状态监听：只看有没有人在录、有没有语音在播，不碰声音内容。 */
    @Synchronized
    private fun syncAudio() {
        if (locked() && signals == null) {
            signals = AudioSignals(this) { rec, play -> Engine.onAudio(rec, play) }.also { it.start() }
        } else if (!locked() && signals != null) {
            stopAudio()
        }
    }

    @Synchronized
    private fun stopAudio() {
        signals?.stop()
        signals = null
    }
}

/** 5 分钟兜底闹钟：服务被系统清掉后，尽量把它再拉起来。 */
class KeepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!KeepAliveService.needed(context)) return
        KeepAliveService.start(context)
    }
}

/**
 * 系统音频状态：只看"有没有人在录"和"有没有助手/语音类声音在播"，不碰声音内容，不需要麦克风权限。
 * 锁定期间前台只有 Live，所以"有人在录"≈ Live 在听，"有语音在播"≈ 助手在说。**这两条推断还没有真机证据。**
 */
class AudioSignals(context: Context, private val onChange: (recording: Boolean, assistantPlaying: Boolean) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val rec = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) = report()
    }
    private val play = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = report()
    }

    fun start() {
        am?.registerAudioRecordingCallback(rec, handler)
        am?.registerAudioPlaybackCallback(play, handler)
        report()
    }

    fun stop() {
        am?.unregisterAudioRecordingCallback(rec)
        am?.unregisterAudioPlaybackCallback(play)
    }

    private fun report() {
        val a = am ?: return
        val recording = runCatching { a.activeRecordingConfigurations.isNotEmpty() }.getOrDefault(false)
        val playing = runCatching { a.activePlaybackConfigurations.any { it.audioAttributes.usage in VOICE_USAGES } }.getOrDefault(false)
        onChange(recording, playing)
    }

    private companion object {
        val VOICE_USAGES = setOf(
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_MEDIA, // 部分版本的 Live 走媒体流；锁定期间前台只有 Live，误判空间小
        )
    }
}
