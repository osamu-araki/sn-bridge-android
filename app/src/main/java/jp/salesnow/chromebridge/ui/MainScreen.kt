// Version: 2.5.0 | Updated: 2026-03-14
// [2026-03-08] macOS 版 app/index.html + renderer.js の Compose 移植
// [2026-03-11] 単一画面 → 3タブ構成（サーバー / 統計 / 設定）に全面改修
// [2026-03-12] ログを独立タブ化 → 4タブ構成（サーバー / ログ / 統計 / 設定）
// [2026-03-14] サーバー+統計を統合 → 3タブ構成（ダッシュボード / ログ / 設定）
package jp.salesnow.chromebridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.salesnow.chromebridge.data.DailyStatsData
import jp.salesnow.chromebridge.data.MonthlyStatsData
import jp.salesnow.chromebridge.ui.theme.*

data class ServerState(
    val running: Boolean = false,
    val port: Int = 3000,
    val pendingRequests: Int = 0,
    val uptimeSeconds: Long = 0,
    val tunnelRunning: Boolean = false,
    // [2026-03-11] プール情報
    val poolSize: Int = 0,
    val poolAvailable: Int = 0
)

@Composable
fun MainScreen(
    serverState: ServerState,
    // [2026-03-13] システムログと HTTP ログを分離
    systemLogs: List<String>,
    httpLogs: List<String>,
    settings: SettingsState,
    todayStats: DailyStatsData?,
    currentMonthStats: MonthlyStatsData?,
    dailyStats: List<DailyStatsData>,
    monthlyStats: List<MonthlyStatsData>,
    // [2026-03-14] Tunnel 設定（設定タブに表示）
    savedTunnelToken: String,
    savedTunnelDomain: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSaveSettings: (SettingsState) -> Unit,
    // [2026-03-14] サーバー再起動
    onRestartServer: () -> Unit = {},
    onSaveTunnelSettings: (token: String, domain: String) -> Unit,
    onClearSystemLogs: () -> Unit,
    onClearHttpLogs: () -> Unit,
    // [2026-03-13] ログ保存コールバック
    onSaveLogs: () -> Unit = {},
    // [2026-03-13] チャレンジ認証バックグラウンド通知
    challengeNotify: Boolean = true,
    onChallengeNotifyChange: (Boolean) -> Unit = {},
    // [2026-03-13] Slack Webhook URL
    slackWebhookUrl: String = "",
    onSlackWebhookUrlChange: (String) -> Unit = {},
    // [2026-03-14] 詳細ログモード
    verboseLog: Boolean = false,
    onVerboseLogChange: (Boolean) -> Unit = {},
    // [2026-03-14] ログ自動バックアップ
    logAutoBackup: Boolean = false,
    onLogAutoBackupChange: (Boolean) -> Unit = {},
    logBackupFolderName: String = "",
    onSelectBackupFolder: () -> Unit = {},
    // [2026-06-10] OTA: アップデート設定
    portalManifestUrl: String = "",
    portalCheckToken: String = "",
    autoUpdateCheck: Boolean = true,
    onSavePortalUpdateSettings: (url: String, token: String, auto: Boolean) -> Unit = { _, _, _ -> },
    onManualUpdateCheck: () -> Unit = {},
    currentVersionName: String = "",
    currentVersionCode: Int = 0
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    // [2026-03-14] 3タブ構成
    val tabTitles = listOf("ダッシュボード", "ログ", "設定")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ヘッダー
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Teal
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    "SalesNow Bridge",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Server & Page Fetcher",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
            }
        }

        // タブバー
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = Teal
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) Teal else GrayLight
                        )
                    }
                )
            }
        }

        // タブコンテンツ
        when (selectedTab) {
            // [2026-03-14] ダッシュボード（サーバー状態 + 統計）
            0 -> DashboardTab(
                serverState = serverState,
                tunnelDomain = savedTunnelDomain,
                onStartServer = onStartServer,
                onStopServer = onStopServer,
                todayStats = todayStats,
                currentMonthStats = currentMonthStats,
                dailyStats = dailyStats,
                monthlyStats = monthlyStats
            )
            1 -> LogTab(
                systemLogs = systemLogs,
                httpLogs = httpLogs,
                onClearSystemLogs = onClearSystemLogs,
                onClearHttpLogs = onClearHttpLogs,
                onSaveLogs = onSaveLogs
            )
            2 -> SettingsTab(
                settings = settings,
                onSave = onSaveSettings,
                serverRunning = serverState.running,
                onRestartServer = onRestartServer,
                savedTunnelToken = savedTunnelToken,
                savedTunnelDomain = savedTunnelDomain,
                onSaveTunnelSettings = onSaveTunnelSettings,
                challengeNotify = challengeNotify,
                onChallengeNotifyChange = onChallengeNotifyChange,
                slackWebhookUrl = slackWebhookUrl,
                onSlackWebhookUrlChange = onSlackWebhookUrlChange,
                verboseLog = verboseLog,
                onVerboseLogChange = onVerboseLogChange,
                logAutoBackup = logAutoBackup,
                onLogAutoBackupChange = onLogAutoBackupChange,
                logBackupFolderName = logBackupFolderName,
                onSelectBackupFolder = onSelectBackupFolder,
                portalManifestUrl = portalManifestUrl,
                portalCheckToken = portalCheckToken,
                autoUpdateCheck = autoUpdateCheck,
                onSavePortalUpdateSettings = onSavePortalUpdateSettings,
                onManualUpdateCheck = onManualUpdateCheck,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode
            )
        }

        // フッター
        Text(
            "SalesNow Bridge Android",
            fontSize = 12.sp,
            color = GrayLight,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
        )
    }
}
