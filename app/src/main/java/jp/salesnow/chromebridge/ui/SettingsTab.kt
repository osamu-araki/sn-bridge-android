// Version: 1.5.0 | Updated: 2026-03-14
// [2026-03-11] 全設定を1画面にまとめた設定タブ
// [2026-03-13] チャレンジ認証のバックグラウンド通知トグル追加
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
    onSave: (SettingsState) -> Unit,
    // [2026-03-14] 再起動確認ダイアログ用
    serverRunning: Boolean = false,
    onRestartServer: () -> Unit = {},
    // [2026-03-14] Tunnel 設定
    savedTunnelToken: String = "",
    savedTunnelDomain: String = "",
    onSaveTunnelSettings: (token: String, domain: String) -> Unit = { _, _ -> },
    // [2026-03-13] チャレンジ認証のバックグラウンド通知（即時反映）
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
    onSelectBackupFolder: () -> Unit = {}
) {
    var portInput by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var apiKeyInput by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var concurrencyInput by remember(settings.concurrency) { mutableStateOf(settings.concurrency.toFloat()) }
    var maxTimeoutInput by remember(settings.maxTimeout) { mutableStateOf(settings.maxTimeout.toString()) }
    var maxWaitInput by remember(settings.maxWait) { mutableStateOf(settings.maxWait.toString()) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    // [2026-03-14] Tunnel 設定
    var tunnelTokenInput by remember(savedTunnelToken) { mutableStateOf(savedTunnelToken) }
    var tunnelDomainInput by remember(savedTunnelDomain) { mutableStateOf(savedTunnelDomain) }

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

        // [2026-03-14] Tunnel 設定（ServerTab から移動）
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Tunnel 設定",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = tunnelTokenInput,
                        onValueChange = { tunnelTokenInput = it },
                        label = { Text("Tunnel Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = tunnelDomainInput,
                        onValueChange = { tunnelDomainInput = it },
                        label = { Text("Tunnel Domain（表示用）") },
                        placeholder = { Text("例: my-tunnel.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(4.dp))

                    Text(
                        "※ Token は cloudflared tunnel token <name> で取得",
                        fontSize = 12.sp,
                        color = Teal
                    )
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { onSaveTunnelSettings(tunnelTokenInput, tunnelDomainInput) },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal)
                    ) {
                        Text("Tunnel 設定を保存")
                    }
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
                        valueRange = 1f..4f,
                        steps = 2,
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

        // [2026-03-13] チャレンジ認証設定
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "認証チャレンジ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "バックグラウンド通知",
                                fontSize = 14.sp,
                                color = NavyDark
                            )
                            Text(
                                "OFF: フォアグラウンド時のみ表示",
                                fontSize = 11.sp,
                                color = GrayLight
                            )
                        }
                        Switch(
                            checked = challengeNotify,
                            onCheckedChange = onChallengeNotifyChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Teal,
                                checkedTrackColor = Teal.copy(alpha = 0.3f)
                            )
                        )
                    }

                    // [2026-03-13] Slack Webhook URL
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Slack 通知",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = NavyDark
                    )
                    Text(
                        "チャレンジ検知時に Slack へ通知します",
                        fontSize = 11.sp,
                        color = GrayLight
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = slackWebhookUrl,
                        onValueChange = onSlackWebhookUrlChange,
                        label = { Text("Incoming Webhook URL") },
                        placeholder = { Text("https://hooks.slack.com/services/...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        // [2026-03-14] ログ設定（詳細ログモード）
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "ログ設定",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Text(
                        "簡易ログ: 重要なイベントのみ表示\n詳細ログ: Tunnel 内部ログ等すべて表示",
                        fontSize = 11.sp,
                        color = GrayLight
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (verboseLog) "詳細ログモード" else "簡易ログモード",
                            fontSize = 14.sp,
                            color = NavyDark
                        )
                        Switch(
                            checked = verboseLog,
                            onCheckedChange = onVerboseLogChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Teal,
                                checkedTrackColor = Teal.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        }

        // [2026-03-14] ログ自動バックアップ設定
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "ログバックアップ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Text(
                        "ログファイルのローテーション時に自動でバックアップします",
                        fontSize = 11.sp,
                        color = GrayLight
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "自動バックアップ",
                            fontSize = 14.sp,
                            color = NavyDark
                        )
                        Switch(
                            checked = logAutoBackup,
                            onCheckedChange = onLogAutoBackupChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Teal,
                                checkedTrackColor = Teal.copy(alpha = 0.3f)
                            )
                        )
                    }
                    if (logAutoBackup) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onSelectBackupFolder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (logBackupFolderName.isNotBlank()) logBackupFolderName
                                else "バックアップ先を選択...",
                                color = if (logBackupFolderName.isNotBlank()) NavyDark else GrayLight
                            )
                        }
                        if (logBackupFolderName.isBlank()) {
                            Text(
                                "Google ドライブのフォルダも選択できます",
                                fontSize = 11.sp,
                                color = GrayLight
                            )
                        }
                    }
                }
            }
        }

        // 保存ボタン
        item {
            Button(
                onClick = {
                    val newSettings = SettingsState(
                        port = portInput.toIntOrNull() ?: 3000,
                        apiKey = apiKeyInput,
                        concurrency = Math.round(concurrencyInput),
                        maxTimeout = (maxTimeoutInput.toIntOrNull() ?: 60).coerceIn(10, 120),
                        maxWait = (maxWaitInput.toIntOrNull() ?: 10).coerceIn(1, 30)
                    )
                    onSave(newSettings)
                    // サーバー稼働中かつ再起動が必要な設定が変更された場合のみダイアログ表示
                    val needsRestart = serverRunning && (
                        newSettings.port != settings.port ||
                        newSettings.apiKey != settings.apiKey ||
                        newSettings.concurrency != settings.concurrency ||
                        newSettings.maxTimeout != settings.maxTimeout ||
                        newSettings.maxWait != settings.maxWait
                    )
                    if (needsRestart) {
                        showRestartDialog = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                Text("設定を保存")
            }
        }
    }

    // [2026-03-14] サーバー再起動確認ダイアログ
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("サーバーを再起動しますか？") },
            text = { Text("変更した設定を反映するにはサーバーの再起動が必要です。") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    onRestartServer()
                }) {
                    Text("再起動する", color = Teal)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("あとで")
                }
            }
        )
    }
}
