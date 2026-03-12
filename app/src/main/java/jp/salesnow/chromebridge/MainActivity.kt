// Version: 2.0.0 | Updated: 2026-03-11
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
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
                var logs by remember { mutableStateOf<List<String>>(emptyList()) }
                // [2026-03-11] 統計データ
                var todayStats by remember { mutableStateOf<DailyStatsData?>(null) }
                var currentMonthStats by remember { mutableStateOf<MonthlyStatsData?>(null) }
                var dailyStats by remember { mutableStateOf<List<DailyStatsData>>(emptyList()) }
                var monthlyStats by remember { mutableStateOf<List<MonthlyStatsData>>(emptyList()) }

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
                            logs = svc.getLogs()
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
                    logs = logs,
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
                        Toast.makeText(this@MainActivity, "設定を保存しました（再起動で反映）", Toast.LENGTH_SHORT).show()
                    },
                    onSaveTunnelSettings = { token, domain ->
                        settings.tunnelToken = token
                        settings.tunnelDomain = domain
                        Toast.makeText(this@MainActivity, "Tunnel 設定を保存しました", Toast.LENGTH_SHORT).show()
                    },
                    onStartTunnel = { serviceBinder?.startTunnel() },
                    onStopTunnel = { serviceBinder?.stopTunnel() },
                    onClearLogs = { serviceBinder?.clearLogs() }
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
