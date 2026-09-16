package com.copyparty.zflip5.widget

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.copyparty.zflip5.FileServerService
import com.copyparty.zflip5.MainActivity
import com.copyparty.zflip5.NetworkUtils
import com.copyparty.zflip5.R
import com.copyparty.zflip5.ServerPreferences

/**
 * Flex Window (cover / outer screen) control panel for Galaxy Z Flip5.
 * launchDisplayId: 0 = main screen, 1 = cover.
 */
class CoverServerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateOne(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                if (FileServerService.running) {
                    FileServerService.stop(context)
                } else {
                    val prefs = ServerPreferences(context)
                    if (prefs.treeUri != null) {
                        FileServerService.start(context)
                    } else {
                        // Open main screen to pick share root
                        openMain(context, MAIN_SCREEN_ID)
                    }
                }
                // slight delay then refresh
                android.os.Handler(context.mainLooper).postDelayed({
                    WidgetUpdateHelper.requestUpdate(context)
                }, 500)
            }
            ACTION_REFRESH, AppWidgetManager.ACTION_APPWIDGET_UPDATE -> {
                WidgetUpdateHelper.requestUpdate(context)
            }
            FileServerService.ACTION_STATE_CHANGED -> {
                WidgetUpdateHelper.requestUpdate(context)
            }
            ACTION_OPEN_MAIN -> openMain(context, MAIN_SCREEN_ID)
        }
    }

    private fun updateOne(context: Context, mgr: AppWidgetManager, id: Int) {
        val prefs = ServerPreferences(context)
        val running = FileServerService.running
        val url = if (running) {
            FileServerService.lastUrl ?: NetworkUtils.baseUrl(context, prefs)
        } else {
            NetworkUtils.baseUrl(context, prefs)
        }

        val views = RemoteViews(context.packageName, R.layout.widget_cover_panel)
        views.setTextViewText(
            R.id.widget_status,
            if (running) context.getString(R.string.status_running)
            else context.getString(R.string.status_stopped)
        )
        views.setTextViewText(R.id.widget_url, url)
        views.setTextViewText(
            R.id.widget_port,
            context.getString(R.string.widget_port_fmt, prefs.port)
        )
        views.setTextViewText(
            R.id.widget_toggle,
            if (running) context.getString(R.string.action_stop)
            else context.getString(R.string.action_start)
        )

        views.setOnClickPendingIntent(
            R.id.widget_toggle,
            broadcastPi(context, ACTION_TOGGLE, 10)
        )
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            broadcastPi(context, ACTION_REFRESH, 11)
        )
        views.setOnClickPendingIntent(
            R.id.widget_open_main,
            activityPi(context, MAIN_SCREEN_ID, 12)
        )

        mgr.updateAppWidget(id, views)
    }

    private fun broadcastPi(context: Context, action: String, req: Int): PendingIntent {
        val i = Intent(context, CoverServerWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            req,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun activityPi(context: Context, displayId: Int, req: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val opts = ActivityOptions.makeBasic().apply {
                // launchDisplayId: 0 main, 1 cover
                try {
                    val m = ActivityOptions::class.java.getMethod(
                        "setLaunchDisplayId",
                        Int::class.javaPrimitiveType
                    )
                    m.invoke(this, displayId)
                } catch (_: Exception) {
                }
            }
            PendingIntent.getActivity(
                context,
                req,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                opts.toBundle()
            )
        } else {
            PendingIntent.getActivity(
                context,
                req,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun openMain(context: Context, displayId: Int) {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val opts = ActivityOptions.makeBasic()
                try {
                    val m = ActivityOptions::class.java.getMethod(
                        "setLaunchDisplayId",
                        Int::class.javaPrimitiveType
                    )
                    m.invoke(opts, displayId)
                    context.startActivity(i, opts.toBundle())
                    return
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        context.startActivity(i)
    }

    companion object {
        const val ACTION_TOGGLE = "com.copyparty.zflip5.ACTION_WIDGET_TOGGLE"
        const val ACTION_REFRESH = "com.copyparty.zflip5.ACTION_WIDGET_REFRESH"
        const val ACTION_OPEN_MAIN = "com.copyparty.zflip5.ACTION_OPEN_MAIN"
        const val MAIN_SCREEN_ID = 0
        const val COVER_SCREEN_ID = 1
    }
}
