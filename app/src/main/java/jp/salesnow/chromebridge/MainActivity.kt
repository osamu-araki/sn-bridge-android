// Version: 2.3.0 | Updated: 2026-03-14
// [2026-03-08] Chrome Bridge Android メインアクティビティ
// [2026-03-08] Tunnel 操作（開始/停止/設定保存）を追加
// [2026-03-11] 3タブ構成対応、統計データ・設定の受け渡し
package jp.salesnow.chromebridge

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import jp.salesnow.chromebridge.data.DailyStatsData
import jp.salesnow.chromebridge.data.MonthlyStatsData
import jp.salesnow.chromebridge.data.SettingsRepository
import jp.salesnow.chromebridge.service.BridgeForegroundService
import jp.salesnow.chromebridge.ui.MainScreen
import jp.salesnow.chromebridge.ui.ServerState
import jp.salesnow.chromebridge.ui.SettingsState
import jp.salesnow.chromebridge.ui.theme.ChromeBridgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {

    private var serviceBinder: BridgeForegroundService? = null
    private var bound = mutableStateOf(false)
    // [2026-03-13] ログ保存用: SAF で選択された URI にログを書き出す
    private var pendingLogs: List<String> = emptyList()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceBinder = (binder as BridgeForegroundService.LocalBinder).service
            bound.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            bound.value = false
        }
    }

    // [2026-03-13] SAF CREATE_DOCUMENT で Google ドライブ等にログを保存
    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(pendingLogs.joinToString("\n").toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "ログを保存しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存に失敗しました: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // [2026-03-14] SAF OpenDocumentTree でバックアップ先フォルダを選択
    private val backupFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // URI 権限を永続化（アプリ再起動後も有効）
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val settings = SettingsRepository(this)
        settings.logBackupUri = uri.toString()
        Toast.makeText(this, "バックアップ先を設定しました", Toast.LENGTH_SHORT).show()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startBridgeService()
        else Toast.makeText(this, "通知の許可が必要です", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = SettingsRepository(this)

        setContent {
            ChromeBridgeTheme {
                val isBound by bound
                var serverState by remember { mutableStateOf(ServerState()) }
                // [2026-03-13] システムログ・HTTP ログ分離
                var systemLogs by remember { mutableStateOf<List<String>>(emptyList()) }
                var httpLogs by remember { mutableStateOf<List<String>>(emptyList()) }
                // [2026-03-11] 統計データ
                var todayStats by remember { mutableStateOf<DailyStatsData?>(null) }
                var currentMonthStats by remember { mutableStateOf<MonthlyStatsData?>(null) }
                var dailyStats by remember { mutableStateOf<List<DailyStatsData>>(emptyList()) }
                var monthlyStats by remember { mutableStateOf<List<MonthlyStatsData>>(emptyList()) }

                // [2026-03-14] 即時反映が必要な設定を Compose State で管理
                var verboseLogState by remember { mutableStateOf(settings.verboseLog) }
                var challengeNotifyState by remember { mutableStateOf(settings.challengeNotify) }
                var slackWebhookUrlState by remember { mutableStateOf(settings.slackWebhookUrl) }
                var logAutoBackupState by remember { mutableStateOf(settings.logAutoBackup) }

                // 1秒ごとに状態更新
                LaunchedEffect(isBound) {
                    while (isActive && isBound) {
                        serviceBinder?.let { svc ->
                            serverState = ServerState(
                                running = svc.isRunning,
                                port = svc.getPort(),
                                pendingRequests = 0,
                                uptimeSeconds = 0,
                                tunnelRunning = svc.isTunnelRunning,
                                poolSize = svc.getPoolSize(),
                                poolAvailable = svc.getPoolAvailable()
                            )
                            systemLogs = svc.getSystemLogs()
                            httpLogs = svc.getHttpLogs()
                        }
                        delay(1000)
                    }
                }

                // [2026-03-11] 5秒ごとに統計データ更新（重い処理なので頻度を下げる）
                LaunchedEffect(isBound) {
                    while (isActive && isBound) {
                        serviceBinder?.getStatsRepository()?.let { repo ->
                            try {
                                todayStats = repo.getTodayStats()
                                currentMonthStats = repo.getCurrentMonthStats()
                                dailyStats = repo.getDailyStats(30)
                                monthlyStats = repo.getMonthlyStats(12)
                            } catch (_: Exception) {
                                // DB アクセスエラーは無視
                            }
                        }
                        delay(5000)
                    }
                }

                MainScreen(
                    serverState = serverState,
                    systemLogs = systemLogs,
                    httpLogs = httpLogs,
                    settings = SettingsState(
                        port = settings.port,
                        apiKey = settings.apiKey,
                        concurrency = settings.concurrency,
                        maxTimeout = settings.maxTimeout,
                        maxWait = settings.maxWait
                    ),
                    todayStats = todayStats,
                    currentMonthStats = currentMonthStats,
                    dailyStats = dailyStats,
                    monthlyStats = monthlyStats,
                    savedTunnelToken = settings.tunnelToken,
                    savedTunnelDomain = settings.tunnelDomain,
                    onStartServer = { requestStartService() },
                    onStopServer = {
                        serviceBinder?.stopServer()
                        stopService(Intent(this@MainActivity, BridgeForegroundService::class.java))
                    },
                    onSaveSettings = { newSettings ->
                        settings.port = newSettings.port
                        settings.apiKey = newSettings.apiKey
                        settings.concurrency = newSettings.concurrency
                        settings.maxTimeout = newSettings.maxTimeout
                        settings.maxWait = newSettings.maxWait
                        Toast.makeText(this@MainActivity, "設定を保存しました", Toast.LENGTH_SHORT).show()
                    },
                    // [2026-03-14] サーバー再起動（停止→再起動）
                    onRestartServer = {
                        serviceBinder?.stopServer()
                        stopService(Intent(this@MainActivity, BridgeForegroundService::class.java))
                        requestStartService()
                        Toast.makeText(this@MainActivity, "サーバーを再起動しました", Toast.LENGTH_SHORT).show()
                    },
                    onSaveTunnelSettings = { token, domain ->
                        settings.tunnelToken = token
                        settings.tunnelDomain = domain
                        Toast.makeText(this@MainActivity, "Tunnel 設定を保存しました", Toast.LENGTH_SHORT).show()
                    },
                    onClearSystemLogs = { serviceBinder?.clearSystemLogs() },
                    onClearHttpLogs = { serviceBinder?.clearHttpLogs() },
                    // [2026-03-13] チャレンジ認証バックグラウンド通知（即時反映）
                    challengeNotify = challengeNotifyState,
                    onChallengeNotifyChange = { enabled ->
                        challengeNotifyState = enabled
                        settings.challengeNotify = enabled
                    },
                    // [2026-03-13] Slack Webhook URL（即時反映）
                    slackWebhookUrl = slackWebhookUrlState,
                    onSlackWebhookUrlChange = { url ->
                        slackWebhookUrlState = url
                        settings.slackWebhookUrl = url
                    },
                    // [2026-03-14] 詳細ログモード（即時反映）
                    verboseLog = verboseLogState,
                    onVerboseLogChange = { enabled ->
                        verboseLogState = enabled
                        settings.verboseLog = enabled
                    },
                    // [2026-03-14] ログ自動バックアップ（即時反映）
                    logAutoBackup = logAutoBackupState,
                    onLogAutoBackupChange = { enabled ->
                        logAutoBackupState = enabled
                        settings.logAutoBackup = enabled
                    },
                    logBackupFolderName = run {
                        val uri = settings.logBackupUri
                        if (uri.isBlank()) ""
                        else {
                            try {
                                val doc = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(uri))
                                doc?.name ?: "選択済み"
                            } catch (_: Exception) { "選択済み" }
                        }
                    },
                    onSelectBackupFolder = {
                        backupFolderLauncher.launch(null)
                    },
                    // [2026-03-14] ファイルログ全量をエクスポート（LogFileWriter から読み出し）
                    onSaveLogs = {
                        val writer = serviceBinder?.getLogFileWriter()
                        pendingLogs = if (writer != null) {
                            buildList {
                                val sysLog = writer.readAllSystem()
                                if (sysLog.isNotBlank()) {
                                    add("=== システムログ ===")
                                    addAll(sysLog.lines())
                                }
                                val httpLog = writer.readAllHttp()
                                if (httpLog.isNotBlank()) {
                                    add("")
                                    add("=== HTTP ログ ===")
                                    addAll(httpLog.lines())
                                }
                            }
                        } else {
                            // フォールバック: メモリ上のログ
                            buildList {
                                if (systemLogs.isNotEmpty()) {
                                    add("=== システムログ ===")
                                    addAll(systemLogs)
                                }
                                if (httpLogs.isNotEmpty()) {
                                    add("")
                                    add("=== HTTP ログ ===")
                                    addAll(httpLogs)
                                }
                            }
                        }
                        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        saveLogLauncher.launch("chrome-bridge-log_$ts.txt")
                    },
                    // [2026-06-10] OTA: アップデート設定（manifest URL / token / 自動チェック）
                    portalManifestUrl = settings.portalManifestUrl,
                    portalCheckToken = settings.portalCheckToken,
                    autoUpdateCheck = settings.autoUpdateCheck,
                    onSavePortalUpdateSettings = { url, token, auto ->
                        settings.portalManifestUrl = url
                        settings.portalCheckToken = token
                        settings.autoUpdateCheck = auto
                        // [Codex 事後#1] サーバー本体を再起動せず Alarm のみ再登録
                        serviceBinder?.refreshUpdateAlarm()
                        Toast.makeText(this@MainActivity, "アップデート設定を保存しました", Toast.LENGTH_SHORT).show()
                    },
                    onManualUpdateCheck = {
                        val intent = Intent(this@MainActivity, BridgeForegroundService::class.java).apply {
                            action = BridgeForegroundService.ACTION_CHECK_UPDATE
                        }
                        startService(intent)
                        Toast.makeText(this@MainActivity, "更新を確認しています…", Toast.LENGTH_SHORT).show()
                    },
                    currentVersionName = run {
                        try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                        } catch (_: Exception) { "" }
                    },
                    currentVersionCode = run {
                        try {
                            val info = packageManager.getPackageInfo(packageName, 0)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                info.longVersionCode.toInt()
                            } else {
                                @Suppress("DEPRECATION") info.versionCode
                            }
                        } catch (_: Exception) { 0 }
                    }
                )
            }
        }

        // 自動起動設定の場合はサービスを開始
        if (settings.autoStart) {
            requestStartService()
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BridgeForegroundService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound.value) {
            unbindService(connection)
            bound.value = false
        }
    }

    private fun requestStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startBridgeService()
    }

    private fun startBridgeService() {
        val intent = Intent(this, BridgeForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}
