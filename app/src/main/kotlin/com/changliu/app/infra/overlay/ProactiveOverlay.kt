package com.changliu.app.infra.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.changliu.app.R

/**
 * L0 主动搭话：顶部一张小卡（不是拦截页，不抢焦点）。
 *
 * 有悬浮窗权限就用 TYPE_APPLICATION_OVERLAY；没有就退化成一条可点通知。
 * 12 秒自动收起；同一时间只挂一张。
 */
object ProactiveOverlay {
    private const val CHANNEL = "proactive"
    private const val NOTE_ID = 7

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    private val autoHide = Runnable { dismiss() }

    fun show(ctx: Context, text: String, moodLabel: String) {
        main.post {
            dismiss()
            val canDraw = Settings.canDrawOverlays(ctx)
            android.util.Log.i("ProactiveOverlay", "show canDraw=$canDraw")
            if (!canDraw) {
                notifyFallback(ctx, text, moodLabel)
                return@post
            }
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return@post
            val dp = ctx.resources.displayMetrics.density
            fun px(v: Int) = (v * dp).toInt()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = px(18).toFloat()
                    setColor(Color.parseColor("#FF1A1815"))
                    setStroke(px(1), Color.parseColor("#33FFFFFF"))
                }
                setPadding(px(16), px(13), px(16), px(10))
                isClickable = true
            }

            root.addView(textView(ctx, "长流 · $moodLabel", 11f, Color.parseColor("#E08A6A")))
            root.addView(textView(ctx, text, 15f, Color.WHITE).apply {
                setPadding(0, px(4), 0, px(8))
            })

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            row.addView(button(ctx, "知道了") { dismiss() })
            row.addView(button(ctx, "聊两句") {
                dismiss()
                openChat(ctx)
            })
            root.addView(row)

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = px(72)
                horizontalMargin = 0.06f
            }
            runCatching {
                wm.addView(root, lp)
                view = root
                main.removeCallbacks(autoHide)
                main.postDelayed(autoHide, 12_000L)
            }.onFailure { android.util.Log.e("ProactiveOverlay", "addView failed", it) }
        }
    }

    fun dismiss() {
        main.removeCallbacks(autoHide)
        val v = view ?: return
        view = null
        runCatching {
            (v.context.getSystemService(WindowManager::class.java))?.removeView(v)
        }
    }

    private fun textView(ctx: Context, text: String, size: Float, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    private fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13.5f
        setTextColor(Color.parseColor("#F0B49C"))
        setPadding(24, 14, 24, 14)
        setOnClickListener { onClick() }
    }

    private fun openChat(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent().setClassName(ctx.packageName, "com.changliu.app.ui.ConductorActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 没有悬浮窗权限时的退路：一条可点通知，不算强制干预。 */
    private fun notifyFallback(ctx: Context, text: String, moodLabel: String) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "长流搭话", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent().setClassName(ctx.packageName, "com.changliu.app.ui.ConductorActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_driver)
                .setContentTitle("长流 · $moodLabel")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTE_ID, n)
        }
    }
}
