// Version: 1.2.0 | Updated: 2026-03-08
// [2026-03-08] macOS 版 app/index.html + renderer.js の Compose 移植
// [2026-03-08] Tunnel 設定セクション追加
// [2026-03-08] Tunnel Domain 入力欄削除、API Key 表示/非表示トグル追加
package jp.salesnow.chromebridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.salesnow.chromebridge.ui.theme.*

data class ServerState(
    val running: Boolean = false,
    val port: Int = 3000,
    val pendingRequests: Int = 0,
    val uptimeSeconds: Long = 0,
    val tunnelRunning: Boolean = false
)

@Composable
fun MainScreen(
    serverState: ServerState,
    logs: List<String>,
    savedPort: Int,
    savedApiKey: String,
    savedTunnelToken: String,
    savedTunnelDomain: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSaveSettings: (port: Int, apiKey: String) -> Unit,
    onSaveTunnelSettings: (token: String, domain: String) -> Unit,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onClearLogs: () -> Unit
) {
    var portInput by remember(savedPort) { mutableStateOf(savedPort.toString()) }
    var apiKeyInput by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var tunnelTokenInput by remember(savedTunnelToken) { mutableStateOf(savedTunnelToken) }
    var tunnelDomainInput by remember(savedTunnelDomain) { mutableStateOf(savedTunnelDomain) }
    var logsExpanded by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ヘッダー
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Teal
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
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
        }

        // サーバー状態カード
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ステータスインジケーター
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (serverState.running) Teal else GrayLight)
                    )
                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "サーバー",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = NavyDark
                        )
                        if (serverState.running) {
                            Text(
                                "Port: ${serverState.port}  処理中: ${serverState.pendingRequests}",
                                fontSize = 13.sp,
                                color = GrayLight
                            )
                        } else {
                            Text("停止中", fontSize = 13.sp, color = GrayLight)
                        }
                    }

                    OutlinedButton(
                        onClick = { if (serverState.running) onStopServer() else onStartServer() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (serverState.running) GrayLight else Teal
                        )
                    ) {
                        Text(if (serverState.running) "停止" else "起動")
                    }
                }
            }
        }

        // 設定カード
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "設定",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(12.dp))

                    // Port
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    // [2026-03-08] API Key（目アイコンで表示/非表示切替）
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
                    Spacer(Modifier.height(4.dp))

                    Text(
                        "※ 反映にはサーバーの再起動が必要です",
                        fontSize = 12.sp,
                        color = Teal
                    )
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val port = portInput.toIntOrNull() ?: 3000
                            onSaveSettings(port, apiKeyInput)
                        },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal)
                    ) {
                        Text("設定を保存")
                    }
                }
            }
        }

        // [2026-03-08] Tunnel セクション
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Tunnel 状態ヘッダー
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (serverState.tunnelRunning) Teal else GrayLight)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Tunnel",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = NavyDark
                            )
                            Text(
                                if (serverState.tunnelRunning) {
                                    if (tunnelDomainInput.isNotBlank()) tunnelDomainInput else "接続中"
                                } else "停止中",
                                fontSize = 13.sp,
                                color = GrayLight
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (serverState.tunnelRunning) onStopTunnel()
                                else {
                                    onSaveTunnelSettings(tunnelTokenInput, tunnelDomainInput)
                                    onStartTunnel()
                                }
                            },
                            enabled = serverState.running,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (serverState.tunnelRunning) GrayLight else Teal
                            )
                        ) {
                            Text(if (serverState.tunnelRunning) "停止" else "接続")
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Tunnel Token
                    OutlinedTextField(
                        value = tunnelTokenInput,
                        onValueChange = { tunnelTokenInput = it },
                        label = { Text("Tunnel Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    // Tunnel Domain（表示用、任意入力）
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

        // ログカード
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { logsExpanded = !logsExpanded }) {
                            Text(
                                "${if (logsExpanded) "▼" else "▶"} LOGS",
                                color = Teal,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (logsExpanded) {
                            TextButton(onClick = onClearLogs) {
                                Text("Clear", color = GrayLight, fontSize = 12.sp)
                            }
                        }
                    }

                    AnimatedVisibility(visible = logsExpanded) {
                        Column {
                            if (logs.isEmpty()) {
                                Text(
                                    "ログはありません",
                                    fontSize = 12.sp,
                                    color = GrayLight,
                                    modifier = Modifier.padding(8.dp)
                                )
                            } else {
                                logs.takeLast(20).forEach { log ->
                                    Text(
                                        log,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = NavyDark,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // フッター
        item {
            Text(
                "Chrome Bridge Android v1.1.0",
                fontSize = 12.sp,
                color = GrayLight,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .wrapContentWidth(Alignment.CenterHorizontally)
            )
        }
    }
}
