// Version: 1.2.0 | Updated: 2026-03-08
// [2026-03-08] バックグラウンドでサーバーを維持するための Foreground Service
// [2026-03-08] Tunnel 管理（cloudflared）を統合
package jp.salesnow.chromebridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import jp.salesnow.chromebridge.MainActivity
import jp.salesnow.chromebridge.R
import jp.salesnow.chromebridge.data.SettingsRepository
import jp.salesnow.chromebridge.fetcher.HeadlessWebViewFetcher
import jp.salesnow.chromebridge.server.AuthMiddleware
import jp.salesnow.chromebridge.server.BridgeHttpServer
import jp.salesnow.chromebridge.server.RateLimiter
import jp.salesnow.chromebridge.tunnel.TunnelManager
import java.util.concurrent.CopyOnWriteArrayList

class BridgeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "chrome_bridge_server"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "jp.salesnow.chromebridge.STOP"
        // [2026-03-08] adb 等から Intent でトンネルを制御するためのアクション
        const val ACTION_START_TUNNEL = "jp.salesnow.chromebridge.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "jp.salesnow.chromebridge.STOP_TUNNEL"
    }

    private var server: BridgeHttpServer? = null
    private var fetcher: HeadlessWebViewFetcher? = null
    private var tunnelManager: TunnelManager? = null
    private val logs = CopyOnWriteArrayList<String>()
    private val maxLogs = 100

    inner class LocalBinder : Binder() {
        val service: BridgeForegroundService get() = this@BridgeForegroundService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_TUNNEL -> {
                startTunnel()
                return START_STICKY
            }
            ACTION_STOP_TUNNEL -> {
                stopTunnel()
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("起動中..."))
        startServer()

        return START_STICKY
    }

    private fun startServer() {
        val settings = SettingsRepository(this)
        val port = settings.port

        // WebView 初期化
        fetcher = HeadlessWebViewFetcher(this).also { it.init() }

        val auth = AuthMiddleware { settings.apiKey }
        val rateLimiter = RateLimiter()

        server = BridgeHttpServer(port, fetcher!!, auth, rateLimiter) { msg ->
            addLog(msg)
        }.also {
            it.start()
        }

        addLog("サーバー起動: http://localhost:$port")
        updateNotification("稼働中 — Port: $port")
    }

    // [2026-03-08] Tunnel の開始・停止
    fun startTunnel() {
        val settings = SettingsRepository(this)
        val token = settings.tunnelToken
        val port = settings.port

        if (token.isBlank()) {
            addLog("Tunnel トークンが未設定です（設定画面で入力してください）")
            return
        }

        val domain = settings.tunnelDomain
        tunnelManager = TunnelManager(this) { msg -> addLog(msg) }
        tunnelManager?.start(token, port, domain)

        if (domain.isNotBlank()) {
            updateNotification("稼働中 — Port: $port | Tunnel: $domain")
        } else {
            updateNotification("稼働中 — Port: $port | Tunnel 接続中...")
        }
    }

    fun stopTunnel() {
        tunnelManager?.stop()
        tunnelManager = null
        val port = SettingsRepository(this).port
        if (server != null) {
            updateNotification("稼働中 — Port: $port")
        }
    }

    val isTunnelRunning: Boolean get() = tunnelManager?.isRunning == true

    fun stopServer() {
        tunnelManager?.stop()
        tunnelManager = null
        server?.stopServer()
        server = null
        fetcher?.destroy()
        fetcher = null
        addLog("サーバー停止")
    }

    val isRunning: Boolean get() = server != null

    fun getPort(): Int = SettingsRepository(this).port

    fun getLogs(): List<String> = logs.toList()

    fun clearLogs() = logs.clear()

    private fun addLog(text: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
            .format(java.util.Date())
        logs.add("[$ts] $text")
        if (logs.size > maxLogs) {
            logs.removeAt(0)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chrome Bridge")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_media_pause, "停止", pendingStop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
