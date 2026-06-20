// Version: 2.2.0 | Updated: 2026-06-10
// [2026-03-08] バックグラウンドでサーバーを維持するための Foreground Service
// [2026-03-08] Tunnel 管理（cloudflared）を統合
// [2026-03-11] StatsDatabase / StatsCollector の初期化・ライフサイクル管理を追加
// [2026-03-11] WebViewPool による並列処理対応、設定値の反映
// [2026-03-11] onTrimMemory でメモリ逼迫時に WebViewPool を縮小
// [2026-03-12] BindException 対策: 起動時に既存サーバー停止＋リトライ、onTaskRemoved でクリーンアップ
// [2026-03-13] メインスレッドデッドロック修正: Thread.sleep をメインスレッドで呼ばない
// [2026-06-10] OTA: AlarmManager で 1 時間ごとに UpdateChecker を起動、ACTION_CHECK_UPDATE で手動 trigger
package jp.salesnow.chromebridge.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import jp.salesnow.chromebridge.MainActivity
import jp.salesnow.chromebridge.R
import jp.salesnow.chromebridge.data.SettingsRepository
import jp.salesnow.chromebridge.data.LogFileWriter
import jp.salesnow.chromebridge.data.StatsCollector
import jp.salesnow.chromebridge.data.StatsDatabase
import jp.salesnow.chromebridge.data.StatsRepository
import jp.salesnow.chromebridge.fetcher.WebViewPool
import jp.salesnow.chromebridge.server.AuthMiddleware
import jp.salesnow.chromebridge.server.BridgeHttpServer
import jp.salesnow.chromebridge.server.RateLimiter
import jp.salesnow.chromebridge.tunnel.TunnelManager
import jp.salesnow.chromebridge.update.UpdateChecker
import jp.salesnow.chromebridge.update.UpdateCheckResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class BridgeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "chrome_bridge_server"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "jp.salesnow.chromebridge.STOP"
        // [2026-03-08] adb 等から Intent でトンネルを制御するためのアクション
        const val ACTION_START_TUNNEL = "jp.salesnow.chromebridge.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "jp.salesnow.chromebridge.STOP_TUNNEL"
        // [2026-06-10] OTA: AlarmManager で周期実行されるアクション + UI/HTTP からの手動 trigger
        const val ACTION_CHECK_UPDATE = "jp.salesnow.chromebridge.CHECK_UPDATE"
        const val UPDATE_CHECK_INTERVAL_MS = 60L * 60L * 1000L  // 1 時間
    }

    private var server: BridgeHttpServer? = null
    // [2026-03-11] 単一 WebView → WebViewPool に置き換え
    private var webViewPool: WebViewPool? = null
    private var tunnelManager: TunnelManager? = null
    // [2026-03-11] 統計データ基盤
    private var statsDatabase: StatsDatabase? = null
    private var statsCollector: StatsCollector? = null
    private var statsRepository: StatsRepository? = null
    // [2026-03-14] ログ自動ファイル保存
    private var logFileWriter: LogFileWriter? = null
    // [2026-03-13] システムログと HTTP ログを分離
    private val systemLogs = CopyOnWriteArrayList<String>()
    private val httpLogs = CopyOnWriteArrayList<String>()
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
            ACTION_CHECK_UPDATE -> {
                runUpdateCheck("alarm/intent")
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("起動中..."))
        startServer()

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

        // [2026-03-14] ログファイル自動保存の初期化
        logFileWriter = LogFileWriter(this)

        // [2026-03-11] WebViewPool 初期化（設定された並列数で）
        val concurrency = settings.concurrency
        // [2026-03-14] チャレンジ関連ログを HTTP ログに送出
        // [2026-04-12] チャレンジ成否を StatsCollector に記録
        webViewPool = WebViewPool(
            context = this,
            poolSize = concurrency,
            onLog = { msg -> addHttpLog(msg) },
            onChallengeResult = { success -> statsCollector?.recordChallenge(success) }
        ).also { it.init() }
        addLog("WebViewPool 初期化: ${concurrency}並列")

        val auth = AuthMiddleware { settings.apiKey }

        // [2026-03-12] レートリミッタ・キュー上限削除: Semaphore + TCP バックログで制御
        // [2026-03-13] HTTP サーバーのログは HTTP ログに分離
        val newServer = BridgeHttpServer(
            port = port,
            pool = webViewPool!!,
            auth = auth,
            maxTimeout = settings.maxTimeout,
            maxWait = settings.maxWait,
            statsCollector = statsCollector,
            statsRepository = statsRepository,
            // [2026-06-10] OTA: HTTP /update-check 経由のトリガを Service の runUpdateCheck に橋渡し
            onUpdateCheck = { runUpdateCheck("http") }
        ) { msg ->
            addHttpLog(msg)
        }

        // [2026-03-13] BindException をキャッチしてリトライ（メインスレッドをブロックしない）
        try {
            newServer.start()
        } catch (e: java.io.IOException) {
            addLog("ポート $port がまだ使用中です。1秒後にリトライします...")
            val retryServer = newServer
            val retryPool = webViewPool
            val retryCollector = statsCollector
            val retryDb = statsDatabase
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                try {
                    retryServer.start()
                    server = retryServer
                    addLog("サーバー起動（リトライ成功）: http://localhost:$port")
                    updateNotification("稼働中 — Port: $port")
                } catch (e2: java.io.IOException) {
                    addLog("サーバー起動失敗: ポート $port を確保できません (${e2.message})")
                    retryPool?.destroy()
                    webViewPool = null
                    retryCollector?.destroy()
                    statsCollector = null
                    retryDb?.close()
                    statsDatabase = null
                    statsRepository = null
                    updateNotification("起動失敗 — Port: $port が使用中")
                }
            }, 1000)
            return
        }
        server = newServer

        addLog("サーバー起動: http://localhost:$port (並列=${concurrency}, timeout上限=${settings.maxTimeout}s, wait上限=${settings.maxWait}s)")
        updateNotification("稼働中 — Port: $port")

        // [2026-03-14] トークン設定済みなら Tunnel も自動起動
        if (settings.tunnelToken.isNotBlank()) {
            startTunnel()
        }

        // [2026-06-10] OTA: 自動チェック ON なら周期 Alarm を登録
        scheduleUpdateAlarm()
    }

    // [2026-06-10] OTA: 自動チェック Alarm（1 時間ごと）。設定が OFF なら解除する。
    private fun scheduleUpdateAlarm() {
        val mgr = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = updatePendingIntent()
        val settings = SettingsRepository(this)
        if (!settings.autoUpdateCheck) {
            mgr.cancel(pi)
            return
        }
        val firstTrigger = SystemClock.elapsedRealtime() + UPDATE_CHECK_INTERVAL_MS
        mgr.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME,
            firstTrigger,
            UPDATE_CHECK_INTERVAL_MS,
            pi,
        )
        addLog("OTA: 自動チェック登録（${UPDATE_CHECK_INTERVAL_MS / 60000} 分間隔）")
    }

    /** UI（Binder 経由）から呼ばれる: 自動チェック設定変更時に Alarm を再登録する。
     *  サーバー本体は再起動しない（Codex 事後#1）。
     */
    fun refreshUpdateAlarm() {
        scheduleUpdateAlarm()
    }

    private fun updatePendingIntent(): PendingIntent {
        val intent = Intent(this, BridgeForegroundService::class.java).apply {
            action = ACTION_CHECK_UPDATE
        }
        return PendingIntent.getService(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // [2026-06-10] OTA: 多重実行防止（Codex#2）。Alarm/手動/HTTP の同時 trigger を 1 件に絞る。
    private val updateInProgress = AtomicBoolean(false)

    /** 現在 OTA 更新チェックが走っているか（UI のスピナー表示に使う） */
    val isUpdateChecking: Boolean get() = updateInProgress.get()

    // [2026-06-20] 直近の OTA 結果を Service が保持。UI は polling で読み取り、Activity 回転後も状態を復元できる。
    @Volatile private var lastUpdateResult: UpdateCheckResult? = null

    /** 直近の OTA チェック結果。未実行なら null。 */
    fun getLastUpdateResult(): UpdateCheckResult? = lastUpdateResult

    /** 結果表示後に UI 側でクリア可能にする（次回 polling で再表示されないように） */
    fun consumeLastUpdateResult(): UpdateCheckResult? {
        val v = lastUpdateResult
        lastUpdateResult = null
        return v
    }

    /** 別スレッドで manifest → DL → install を回す。手動 trigger / Alarm 共通。
     *  既に実行中なら skip し、戻り値で呼び出し側に伝える。
     *  [2026-06-20] 結果を main thread に post する callback を受け取れる。UI の Toast / 状態表示用。
     *    callback は applicationContext 配下で使うことを推奨（Activity 破棄後のリークを避ける）。
     */
    fun runUpdateCheck(origin: String, onResult: ((UpdateCheckResult) -> Unit)? = null): Boolean {
        if (!updateInProgress.compareAndSet(false, true)) {
            addLog("OTA: 既に実行中のためスキップ ($origin)")
            return false
        }
        addLog("OTA: 更新チェック開始 ($origin)")
        Thread {
            val result = try {
                val checker = UpdateChecker(applicationContext) { msg -> addLog(msg) }
                checker.checkAndInstall()
            } catch (e: Exception) {
                addLog("OTA: 例外 ${e.message}")
                UpdateCheckResult.InstallError(e.message ?: e::class.java.simpleName)
            } finally {
                lastUpdateResult = null  // 一度 null にして、UI ループが「同じ結果の再描画」と認識しないようにする
            }
            lastUpdateResult = result
            updateInProgress.set(false)
            if (onResult != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
            }
        }.start()
        return true
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
        // [2026-06-11] 既存 manager の cooldown/restart スレッドが残らないよう必ず stop() してから作り直す
        tunnelManager?.stop()
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
        // [2026-06-10] OTA: 周期 Alarm を解除（残ると停止後も自己再起動を試みてしまう）
        try {
            val mgr = getSystemService(ALARM_SERVICE) as AlarmManager
            mgr.cancel(updatePendingIntent())
        } catch (_: Exception) {}
        try { server?.stopServer() } catch (_: Exception) {}
        server = null
        try { webViewPool?.destroy() } catch (_: Exception) {}
        webViewPool = null
        try { statsCollector?.destroy() } catch (_: Exception) {}
        statsCollector = null
        try { statsDatabase?.close() } catch (_: Exception) {}
        statsDatabase = null
        statsRepository = null
        // [2026-03-14] ログファイルの最終フラッシュ
        try { logFileWriter?.destroy() } catch (_: Exception) {}
        logFileWriter = null
    }

    val isRunning: Boolean get() = server != null

    fun getPort(): Int = SettingsRepository(this).port

    // [2026-03-11] 統計データへのアクセス（UI/API から利用）
    fun getStatsRepository(): StatsRepository? = statsRepository

    // [2026-03-14] ログファイルへのアクセス（エクスポート用）
    fun getLogFileWriter(): LogFileWriter? = logFileWriter

    // [2026-03-11] WebViewPool 状態へのアクセス（UI から利用）
    fun getPoolSize(): Int = webViewPool?.currentSize ?: 0
    fun getPoolAvailable(): Int = webViewPool?.availableCount ?: 0

    // [2026-03-13] システムログ・HTTP ログ個別取得
    fun getSystemLogs(): List<String> = systemLogs.toList()
    fun getHttpLogs(): List<String> = httpLogs.toList()
    fun getLogs(): List<String> = (systemLogs + httpLogs).sortedBy { it.substringBefore("]") }

    fun clearSystemLogs() = systemLogs.clear()
    fun clearHttpLogs() = httpLogs.clear()
    fun clearLogs() { systemLogs.clear(); httpLogs.clear() }

    // [2026-03-13] システムログ（サーバー起動・停止、Tunnel、プール、メモリ等）
    private fun addLog(text: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
            .format(java.util.Date())
        val formatted = "[$ts] $text"
        systemLogs.add(formatted)
        if (systemLogs.size > maxLogs) {
            systemLogs.removeAt(0)
        }
        logFileWriter?.appendSystem(formatted)
        android.util.Log.d("ChromeBridge", text)
    }

    // [2026-03-13] HTTP ログ（リクエスト受信・成功・エラー・チャレンジ認証）
    private fun addHttpLog(text: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
            .format(java.util.Date())
        val formatted = "[$ts] $text"
        httpLogs.add(formatted)
        if (httpLogs.size > maxLogs) {
            httpLogs.removeAt(0)
        }
        logFileWriter?.appendHttp(formatted)
        android.util.Log.d("ChromeBridge.HTTP", text)
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
            .setContentTitle("SalesNow Bridge")
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
