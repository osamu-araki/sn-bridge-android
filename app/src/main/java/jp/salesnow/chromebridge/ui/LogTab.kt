// Version: 1.2.0 | Updated: 2026-03-13
// [2026-03-12] ServerTab からログ表示を独立タブ化
// [2026-03-13] Google ドライブへのログ保存機能を追加（SAF 経由）
// [2026-03-13] システムログと HTTP ログをサブタブで分離表示
package jp.salesnow.chromebridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.salesnow.chromebridge.ui.theme.*

@Composable
fun LogTab(
    systemLogs: List<String>,
    httpLogs: List<String>,
    onClearSystemLogs: () -> Unit,
    onClearHttpLogs: () -> Unit,
    onSaveLogs: () -> Unit = {}
) {
    // [2026-03-13] システム / HTTP のサブタブ
    var selectedSubTab by remember { mutableIntStateOf(0) }
    val subTabs = listOf("HTTP", "システム")
    val currentLogs = if (selectedSubTab == 0) httpLogs else systemLogs
    val currentClear = if (selectedSubTab == 0) onClearHttpLogs else onClearSystemLogs

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        // ヘッダー
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "ログ",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = NavyDark
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${currentLogs.size}件",
                fontSize = 13.sp,
                color = GrayLight
            )
            Spacer(Modifier.weight(1f))
            // [2026-03-13] Google ドライブ保存ボタン（両ログをまとめて保存）
            TextButton(
                onClick = onSaveLogs,
                enabled = systemLogs.isNotEmpty() || httpLogs.isNotEmpty()
            ) {
                Text(
                    "保存",
                    color = if (systemLogs.isNotEmpty() || httpLogs.isNotEmpty()) Teal else GrayLight,
                    fontSize = 13.sp
                )
            }
            TextButton(onClick = currentClear) {
                Text("クリア", color = Teal, fontSize = 13.sp)
            }
        }

        // [2026-03-13] サブタブ（システム / HTTP）
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            subTabs.forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedSubTab == index,
                    onClick = { selectedSubTab = index },
                    label = {
                        Text(title, fontSize = 12.sp)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Teal.copy(alpha = 0.15f),
                        selectedLabelColor = Teal
                    )
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // ログ一覧
        LogList(logs = currentLogs)
    }
}

@Composable
private fun LogList(logs: List<String>) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "ログはありません",
                    fontSize = 13.sp,
                    color = GrayLight
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logs) { log ->
                    Text(
                        log,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = NavyDark,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
