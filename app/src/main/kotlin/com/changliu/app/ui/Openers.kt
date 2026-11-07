package com.changliu.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri

/** 从统一壳进入三套业务页面。业务页仍然保留自己的完整界面。 */
object Openers {

    fun driver(ctx: Context) = launch(ctx, "com.changliu.app.MainActivity")

    fun yishou(ctx: Context, route: String? = null) = launch(ctx, "com.yishou.app.MainActivity", route)

    fun zk(ctx: Context, route: String? = null) = launch(ctx, "com.zongkong.app.ui.MainActivity", route)

    fun a11ySettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun overlaySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun batterySettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun usageSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun appNotificationSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun launch(ctx: Context, className: String, route: String? = null) {
        runCatching {
            val i = Intent().setClassName(ctx.packageName, className)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!route.isNullOrBlank()) i.putExtra("route", route)
            ctx.startActivity(i)
        }
    }
}
