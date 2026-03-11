// Version: 2.0.0 | Updated: 2026-03-11
// [2026-03-08] macOS 版 app/index.html + renderer.js の Compose 移植
// [2026-03-11] 単一画面 → 3タブ構成（サーバー / 統計 / 設定）に全面改修
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
    val poolAvailable: Int = 0,
    val maxQueueSize: Int = 20
)

@Composable
fun MainScreen(
    serverState: ServerState,
    logs: List<String>,
    settings: SettingsState,
    todayStats: DailyStatsData?,
    currentMonthStats: MonthlyStatsData?,
    dailyStats: List<DailyStatsData>,
    monthlyStats: List<MonthlyStatsData>,
    savedTunnelToken: String,
    savedTunnelDomain: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSaveSettings: (SettingsState) -> Unit,
    onSaveTunnelSettings: (token: String, domain: String) -> Unit,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onClearLogs: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("サーバー", "統計", "設定")

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
                    "Chrome Bridge",
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

        // [2026-03-11] タブバー
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
            0 -> ServerTab(
                serverState = serverState,
                logs = logs,
                savedTunnelToken = savedTunnelToken,
                savedTunnelDomain = savedTunnelDomain,
                onStartServer = onStartServer,
                onStopServer = onStopServer,
                onSaveTunnelSettings = onSaveTunnelSettings,
                onStartTunnel = onStartTunnel,
                onStopTunnel = onStopTunnel,
                onClearLogs = onClearLogs
            )
            1 -> StatsTab(
                todayStats = todayStats,
                currentMonthStats = currentMonthStats,
                dailyStats = dailyStats,
                monthlyStats = monthlyStats
            )
            2 -> SettingsTab(
                settings = settings,
                onSave = onSaveSettings
            )
        }

        // フッター
        Text(
            "Chrome Bridge Android v2.0.0",
            fontSize = 12.sp,
            color = GrayLight,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .wrapContentWidth(Alignment.CenterHorizontally)
        )
    }
}
