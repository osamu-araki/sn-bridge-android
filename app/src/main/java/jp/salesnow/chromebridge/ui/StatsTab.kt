// Version: 1.0.0 | Updated: 2026-03-11
// [2026-03-11] 日別・月別統計の表示タブ
package jp.salesnow.chromebridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.salesnow.chromebridge.data.DailyStatsData
import jp.salesnow.chromebridge.data.MonthlyStatsData
import jp.salesnow.chromebridge.ui.theme.*

@Composable
fun StatsTab(
    todayStats: DailyStatsData?,
    currentMonthStats: MonthlyStatsData?,
    dailyStats: List<DailyStatsData>,
    monthlyStats: List<MonthlyStatsData>
) {
    var showMonthly by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // サマリーカード（今日 / 今月）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 今日のサマリー
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    title = "今日",
                    totalRequests = todayStats?.totalRequests ?: 0,
                    successRate = todayStats?.successRate ?: 0.0,
                    avgDuration = todayStats?.avgDurationMs ?: 0,
                    totalBytes = todayStats?.totalBytes ?: 0
                )
                // 今月のサマリー
                SummaryCard(
                    modifier = Modifier.weight(1f),
                    title = "今月",
                    totalRequests = currentMonthStats?.totalRequests ?: 0,
                    successRate = currentMonthStats?.successRate ?: 0.0,
                    avgDuration = currentMonthStats?.avgDurationMs ?: 0,
                    totalBytes = currentMonthStats?.totalBytes ?: 0
                )
            }
        }

        // [2026-03-11] 日別 / 月別切り替えタブ
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FilterChip(
                    selected = !showMonthly,
                    onClick = { showMonthly = false },
                    label = { Text("日別") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Teal,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                    )
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = showMonthly,
                    onClick = { showMonthly = true },
                    label = { Text("月別") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Teal,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                    )
                )
            }
        }

        // データ無し表示
        if (!showMonthly && dailyStats.isEmpty() || showMonthly && monthlyStats.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "統計データがありません",
                            color = GrayLight,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // 日別一覧
        if (!showMonthly) {
            items(dailyStats) { stats ->
                DailyStatsRow(stats)
            }
        }

        // 月別一覧
        if (showMonthly) {
            items(monthlyStats) { stats ->
                MonthlyStatsRow(stats)
            }
        }
    }
}

@Composable
private fun SummaryCard(
    modifier: Modifier = Modifier,
    title: String,
    totalRequests: Long,
    successRate: Double,
    avgDuration: Long,
    totalBytes: Long
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Teal)
            Spacer(Modifier.height(8.dp))

            Text(
                "$totalRequests",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = NavyDark
            )
            Text("リクエスト", fontSize = 11.sp, color = GrayLight)

            Spacer(Modifier.height(8.dp))
            Divider(color = GrayLight.copy(alpha = 0.3f))
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${(successRate * 100).toInt()}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (successRate >= 0.95) Teal else if (successRate >= 0.8) NavyDark else ErrorRed
                    )
                    Text("成功率", fontSize = 10.sp, color = GrayLight)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${avgDuration}ms",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = NavyDark
                    )
                    Text("平均", fontSize = 10.sp, color = GrayLight)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                formatBytes(totalBytes),
                fontSize = 11.sp,
                color = GrayLight
            )
        }
    }
}

@Composable
private fun DailyStatsRow(stats: DailyStatsData) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ヘッダー行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stats.dateKey,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = NavyDark,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${stats.totalRequests} 件",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Teal
                )
            }

            Spacer(Modifier.height(8.dp))

            // メトリクス行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem("成功率", "${(stats.successRate * 100).toInt()}%",
                    if (stats.successRate >= 0.95) Teal else if (stats.successRate >= 0.8) NavyDark else ErrorRed)
                MetricItem("平均", "${stats.avgDurationMs}ms", NavyDark)
                MetricItem("最大", "${stats.maxDurationMs}ms", NavyDark)
                MetricItem("通信量", formatBytes(stats.totalBytes), NavyDark)
            }

            // エラー内訳（エラーがある場合のみ）
            if (stats.errorCount > 0) {
                Spacer(Modifier.height(8.dp))
                Divider(color = GrayLight.copy(alpha = 0.3f))
                Spacer(Modifier.height(6.dp))

                Text("エラー内訳", fontSize = 11.sp, color = GrayLight)
                Spacer(Modifier.height(4.dp))

                val errors = buildList {
                    if (stats.errorTimeout > 0) add("timeout: ${stats.errorTimeout}")
                    if (stats.errorFetch > 0) add("fetch: ${stats.errorFetch}")
                    if (stats.errorQueue > 0) add("queue: ${stats.errorQueue}")
                    if (stats.errorAuth > 0) add("auth: ${stats.errorAuth}")
                    if (stats.errorRate > 0) add("rate: ${stats.errorRate}")
                    if (stats.errorParse > 0) add("parse: ${stats.errorParse}")
                }
                Text(
                    errors.joinToString("  |  "),
                    fontSize = 11.sp,
                    color = ErrorRed
                )
            }
        }
    }
}

@Composable
private fun MonthlyStatsRow(stats: MonthlyStatsData) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stats.monthKey,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = NavyDark,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${stats.totalRequests} 件",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Teal
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem("成功率", "${(stats.successRate * 100).toInt()}%",
                    if (stats.successRate >= 0.95) Teal else if (stats.successRate >= 0.8) NavyDark else ErrorRed)
                MetricItem("平均", "${stats.avgDurationMs}ms", NavyDark)
                MetricItem("最大", "${stats.maxDurationMs}ms", NavyDark)
                MetricItem("通信量", formatBytes(stats.totalBytes), NavyDark)
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = color)
        Text(label, fontSize = 10.sp, color = GrayLight)
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }
}
