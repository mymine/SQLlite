package com.dbstudio.app.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dbstudio.app.MainActivity
import com.dbstudio.app.data.AppPrefs

/**
 * MCP 前台服务：保持内置 HTTP 服务器长期运行，并在通知栏展示监听地址。
 */
class McpService : Service() {

    companion object {
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 0x4D43
        const val ACTION_STOP = "com.dbstudio.app.mcp.STOP"

        @Volatile
        var server: McpServer? = null
            private set

        fun isRunning(): Boolean = server?.isRunning == true

        fun activePort(): Int = server?.boundPort ?: 0

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, McpService::class.java))
            }.onFailure {
                McpPrefs.lastError = it.message ?: "无法启动 MCP 服务"
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, McpService::class.java)) }
        }

        fun restart(context: Context) {
            stop(context)
            start(context)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        McpPrefs.init(applicationContext)
        AppPrefs.init(applicationContext)
        McpTools.init(applicationContext)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            McpPrefs.markStopped()
            McpLog.add("service", "已从通知栏停止 MCP 服务", true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 系统重启服务（intent 为 null）时，如果用户已关闭开关，则不再复活
        if (intent == null && !McpPrefs.enabled) {
            startForegroundCompat(buildNotification("正在停止…"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification("正在启动…"))

        if (server?.isRunning == true) {
            updateNotification("运行中 · ${McpPrefs.bindAddress}:${McpPrefs.runningPort}")
            return START_STICKY
        }

        val instance = McpServer()
        val result = instance.start(McpPrefs.bindAddress, McpPrefs.port)
        if (result.isSuccess) {
            server = instance
            McpPrefs.lastError = null
            McpPrefs.runningPort = instance.boundPort
            updateNotification("运行中 · ${McpPrefs.bindAddress}:${instance.boundPort}")
            McpLog.add("service", "服务已启动 ${McpPrefs.bindAddress}:${instance.boundPort}", true)
        } else {
            val message = result.exceptionOrNull()?.message ?: "未知错误"
            McpPrefs.lastError = message
            McpPrefs.runningPort = 0
            McpLog.add("service", "启动失败：$message", false)
            updateNotification("启动失败：$message")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        McpPrefs.runningPort = 0
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP 服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "SQLlite 内置 MCP 服务器的运行状态"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            1,
            Intent(this, McpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SQLlite MCP")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(NotificationCompat.Action(android.R.drawable.ic_media_pause, "停止服务", stopPending))
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
