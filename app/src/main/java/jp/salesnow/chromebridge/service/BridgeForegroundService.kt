// Version: 1.8.0 | Updated: 2026-03-12
// [2026-03-08] バックグラウンドでサーバーを維持するための Foreground Service
// [2026-03-08] Tunnel 管理（cloudflared）を統合
// [2026-03-11] StatsDatabase / StatsCollector の初期化・ライフサイクル管理を追加
// [2026-03-11] WebViewPool による並列処理対応、設定値の反映
// [2026-03-11] onTrimMemory でメモリ逼迫時に WebViewPool を縮小
// [2026-03-12] BindException 対策: 起動時に既存サーバー停止＋リトライ、onTaskRemoved でクリーンアップ
package jp.salesnow.chromebridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import jp.salesnow.chromebridge.MainActivity
import jp.salesnow.chromebridge.R
import jp.salesnow.chromebridge.data.SettingsRepository
import jp.salesnow.chromebridge.data.StatsCollector
import jp.salesnow.chromebridge.data.StatsDatabase
import jp.salesnow.chromebridge.data.StatsRepository
import jp.salesnow.chromebridge.fetcher.WebViewPool
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
    // [2026-03-11] 単一 WebView → WebViewPool に置き換え
    private var webViewPool: WebViewPool? = null
    private var tunnelManager: TunnelManager? = null
    // [2026-03-11] 統計データ基盤
    private var statsDatabase: StatsDatabase? = null
    private var statsCollector: StatsCollector? = null
    private var statsRepository: StatsRepository? = null
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

        // [2026-03-12] サービス再起動時に Tunnel も自動復旧
        // START_STICKY で OS に再起動された場合、startTunnel() が呼ばれないため
        val settings = SettingsRepository(this)
        if (settings.tunnelToken.isNotBlank()) {
            addLog("Tunnel 自動復旧: トークン設定済みのため Tunnel を起動します")
            startTunnel()
        }

        return START_STICKY
    }

    private fun startServer() {
        // [2026-03-12] 既存のサーバーが残っていれば先に停止（BindException 防止）
        if (server != null) {
            addLog("既存サーバーを停止して再起動します")
            stopServerInternal()
        }

        val settings = SettingsRepository(this)
        val port = settings.port

        // [2026-03-11] 統計データベースとコレクターの初期化
        statsDatabase = StatsDatabase(this)
        statsCollector = StatsCollector(statsDatabase!!)
        statsRepository = StatsRepository(statsDatabase!!)

        // [2026-03-11] 起動時にデータクリーンアップ（古いデータを削除）
        try {
            statsDatabase?.cleanup()
        } catch (e: Exception) {
            addLog("統計データクリーンアップ失敗: ${e.message}")
        }

        // [2026-03-11] WebViewPool 初期化（設定された並列数で）
        val concurrency = settings.concurrency
        webViewPool = WebViewPool(this, concurrency).also { it.init() }
        addLog("WebViewPool 初期化: ${concurrency}並列")

        val auth = AuthMiddleware { settings.apiKey }

        // [2026-03-12] レートリミッタ・キュー上限削除: Semaphore + TCP バックログで制御
        val newServer = BridgeHttpServer(
            port = port,
            pool = webViewPool!!,
            auth = auth,
            maxTimeout = settings.maxTimeout,
            maxWait = settings.maxWait,
            statsCollector = statsCollector,
            statsRepository = statsRepository
        ) { msg ->
            addLog(msg)
        }

        // [2026-03-12] BindException をキャッチしてリトライ（ポートが TIME_WAIT の場合に対応）
        try {
            newServer.start()
        } catch (e: java.io.IOException) {
            addLog("ポート $port がまだ使用中です。1秒後にリトライします...")
            try {
                Thread.sleep(1000)
                newServer.start()
            } catch (e2: java.io.IOException) {
                addLog("サーバー起動失敗: ポート $port を確保できません (${e2.message})")
                webViewPool?.destroy()
                webViewPool = null
                statsCollector?.destroy()
                statsCollector = null
                statsDatabase?.close()
                statsDatabase = null
                statsRepository = null
                updateNotification("起動失敗 — Port: $port が使用中")
                return
            }
        }
        server = newServer

        addLog("サーバー起動: http://localhost:$port (並列=${concurrency}, timeout上限=${settings.maxTimeout}s, wait上限=${settings.maxWait}s)")
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
        stopServerInternal()
        addLog("サーバー停止")
    }

    // [2026-03-12] サーバー・プール・統計のクリーンアップ（startServer からも呼ばれる）
    private fun stopServerInternal() {
        try { server?.stopServer() } catch (_: Exception) {}
        server = null
        try { webViewPool?.destroy() } catch (_: Exception) {}
        webViewPool = null
        try { statsCollector?.destroy() } catch (_: Exception) {}
        statsCollector = null
        try { statsDatabase?.close() } catch (_: Exception) {}
        statsDatabase = null
        statsRepository = null
    }

    val isRunning: Boolean get() = server != null

    fun getPort(): Int = SettingsRepository(this).port

    // [2026-03-11] 統計データへのアクセス（UI/API から利用）
    fun getStatsRepository(): StatsRepository? = statsRepository

    // [2026-03-11] WebViewPool 状態へのアクセス（UI から利用）
    fun getPoolSize(): Int = webViewPool?.currentSize ?: 0
    fun getPoolAvailable(): Int = webViewPool?.availableCount ?: 0

    fun getLogs(): List<String> = logs.toList()

    fun clearLogs() = logs.clear()

    private fun addLog(text: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
            .format(java.util.Date())
        logs.add("[$ts] $text")
        if (logs.size > maxLogs) {
            logs.removeAt(0)
        }
        // [2026-03-12] adb logcat でリモートデバッグ可能にする
        android.util.Log.d("ChromeBridge", text)
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

    // [2026-03-11] メモリ逼迫時に WebViewPool を縮小してメモリを確保
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val before = webViewPool?.currentSize ?: 0
            webViewPool?.onLowMemory()
            val after = webViewPool?.currentSize ?: 0
            if (before != after) {
                addLog("メモリ逼迫: WebViewPool 縮小 ${before} → ${after}")
            }
        }
    }

    // [2026-03-12] アプリがタスク一覧からスワイプ終了された時のクリーンアップ
    override fun onTaskRemoved(rootIntent: Intent?) {
        addLog("タスク削除検知: サーバーを停止します")
        stopServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
