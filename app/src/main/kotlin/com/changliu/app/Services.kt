package com.changliu.app

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.changliu.app.infra.keepalive.KeepAliveService
import com.changliu.core.Verdict

/**
 * 看门狗：无障碍被系统关掉、进程又被杀时，靠它定期把服务写回去。
 * JobScheduler 最短周期 15 分钟；`setPersisted` 让它在重启后还在。
 * 抄自 Curbox 的 `services/ServiceWatchdogJob.kt`，去掉了协程和它的通知文案。
 *
 * 它打不过"强制停止"——那会把这个任务一起清掉，只能等用户下次打开 APP 再写回。
 */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        Thread {
            runCatching {
                // 无障碍医生：写回 / 僵死重启 / 定时重启 / 提醒手动开
                com.changliu.app.infra.a11y.A11yDoctorRuntime.check(ctx)
                // 看门狗也负责把常驻守护拉回来：服务被清掉时，光写回无障碍还不够
                KeepAliveService.sync(ctx)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 7711
        private const val PERIOD_MS = 15 * 60_000L

        fun schedule(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJob::class.java))
                        .setPersisted(true)
                        .setPeriodic(PERIOD_MS)
                        .build(),
                )
            }
        }
    }
}

/** 开机 / 覆盖安装后把无障碍写回来，并把看门狗排上。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            runCatching {
                if (!A11yGuard.isOn(ctx)) A11yGuard.ensure(ctx)
                WatchdogJob.schedule(ctx)
                KeepAliveService.sync(ctx)
            }
            pending.finish()
        }.start()
    }
}

