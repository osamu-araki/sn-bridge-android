// Version: 1.1.0 | Updated: 2026-03-12
// [2026-03-11] 全設定を1画面にまとめた設定タブ
package jp.salesnow.chromebridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.salesnow.chromebridge.ui.theme.*

data class SettingsState(
    val port: Int = 3000,
    val apiKey: String = "",
    val concurrency: Int = 2,
    val maxTimeout: Int = 60,
    val maxWait: Int = 10
)

@Composable
fun SettingsTab(
    settings: SettingsState,
    onSave: (SettingsState) -> Unit
) {
    var portInput by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var apiKeyInput by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var concurrencyInput by remember(settings.concurrency) { mutableStateOf(settings.concurrency.toFloat()) }
    var maxTimeoutInput by remember(settings.maxTimeout) { mutableStateOf(settings.maxTimeout.toString()) }
    var maxWaitInput by remember(settings.maxWait) { mutableStateOf(settings.maxWait.toString()) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // サーバー基本設定
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "サーバー設定",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key") },
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (apiKeyVisible) "非表示" else "表示"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        // [2026-03-11] 並列処理設定
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "並列処理",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(12.dp))

                    // 並列数スライダー
                    Text(
                        "WebView 並列数: ${Math.round(concurrencyInput)}",
                        fontSize = 14.sp,
                        color = NavyDark
                    )
                    Slider(
                        value = concurrencyInput,
                        onValueChange = { concurrencyInput = it },
                        valueRange = 1f..8f,
                        steps = 6,
                        colors = SliderDefaults.colors(
                            thumbColor = Teal,
                            activeTrackColor = Teal
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1", fontSize = 11.sp, color = GrayLight)
                        Text("8", fontSize = 11.sp, color = GrayLight)
                    }
                    // [2026-03-11] 注意事項
                    if (Math.round(concurrencyInput) >= 6) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "※ 6以上はハイエンド端末推奨（メモリ不足の可能性あり）",
                            fontSize = 11.sp,
                            color = ErrorRed
                        )
                    }

                }
            }
        }

        // [2026-03-11] タイムアウト設定
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "タイムアウト制限",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Text(
                        "クライアントが指定した値がこの上限を超える場合、サーバー側で制限されます",
                        fontSize = 12.sp,
                        color = GrayLight
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = maxTimeoutInput,
                        onValueChange = { maxTimeoutInput = it.filter { c -> c.isDigit() } },
                        label = { Text("タイムアウト上限（10-120秒）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = maxWaitInput,
                        onValueChange = { maxWaitInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Wait 上限（1-30秒）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        // 保存ボタン + 注意
        item {
            Column {
                Text(
                    "※ 設定の反映にはサーバーの再起動が必要です",
                    fontSize = 12.sp,
                    color = Teal
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onSave(SettingsState(
                            port = portInput.toIntOrNull() ?: 3000,
                            apiKey = apiKeyInput,
                            concurrency = Math.round(concurrencyInput),
                            maxTimeout = (maxTimeoutInput.toIntOrNull() ?: 60).coerceIn(10, 120),
                            maxWait = (maxWaitInput.toIntOrNull() ?: 10).coerceIn(1, 30)
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) {
                    Text("設定を保存")
                }
            }
        }
    }
}
