// Version: 1.0.0 | Updated: 2026-03-12
// [2026-03-12] ServerTab からログ表示を独立タブ化
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
    logs: List<String>,
    onClearLogs: () -> Unit
) {
    val listState = rememberLazyListState()

    // [2026-03-12] 新しいログが追加されたら自動スクロール
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

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
                "${logs.size}件",
                fontSize = 13.sp,
                color = GrayLight
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClearLogs) {
                Text("クリア", color = Teal, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // ログ一覧
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
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
}
