package com.copyparty.zflip5

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.copyparty.zflip5.widget.WidgetUpdateHelper

/**
 * Foreground service that hosts real upstream copyparty (via Chaquopy).
 */
class FileServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServerInternal()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Must call startForeground promptly
                createChannel()
                startForeground(NOTIFICATION_ID, buildNotification(starting = true))
                val ok = startServerInternal()
                if (!ok) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification(starting = false))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServerInternal()
        super.onDestroy()
    }

    private fun startServerInternal(): Boolean {
        if (running && CopypartyController.isRunning(this)) {
            WidgetUpdateHelper.requestUpdate(this)
            return true
        }
        val prefs = ServerPreferences(this)
        val uri = prefs.treeUriOrNull()
        if (uri == null) {
            Log.w(TAG, "No share root selected")
            return false
        }
        val path = UriPathResolver.resolve(this, uri)
        if (path.isNullOrBlank()) {
            Log.e(TAG, "Cannot resolve SAF URI to filesystem path: $uri")
            lastError = "无法将所选目录解析为真实路径。请授予「所有文件访问权限」并选择内部存储中的文件夹。"
            return false
        }
        val bindHost = NetworkUtils.bindHostForCopyparty(prefs)
        val ok = CopypartyController.start(
            this,
            prefs.port,
            path,
            prefs.readOnly,
            prefs.password,
            bindHost
        )
        if (!ok) {
            lastError = CopypartyController.lastError(this)
                ?: "copyparty 启动失败"
            running = false
            lastUrl = null
            return false
        }
        running = true
        lastError = null
        lastPort = prefs.port
        lastUrl = NetworkUtils.baseUrl(this, prefs)
        lastSharePath = path
        WidgetUpdateHelper.requestUpdate(this)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        return true
    }

    private fun stopServerInternal() {
        try {
            CopypartyController.stop(this)
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
        }
        running = false
        lastUrl = null
        WidgetUpdateHelper.requestUpdate(this)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = getString(R.string.notification_channel_desc)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(starting: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, FileServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val url = lastUrl ?: NetworkUtils.baseUrl(this, ServerPreferences(this))
        val text = if (starting) getString(R.string.notification_starting) else url
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "FileServerService"
        private const val CHANNEL_ID = "file_server"
        private const val NOTIFICATION_ID = 3923

        const val ACTION_STOP = "com.copyparty.zflip5.STOP_SERVER"
        const val ACTION_STATE_CHANGED = "com.copyparty.zflip5.SERVER_STATE"

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var lastUrl: String? = null
            private set

        @Volatile
        var lastPort: Int = ServerPreferences.DEFAULT_PORT
            private set

        @Volatile
        var lastSharePath: String? = null
            private set

        @Volatile
        var lastError: String? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, FileServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            val i = Intent(context, FileServerService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
