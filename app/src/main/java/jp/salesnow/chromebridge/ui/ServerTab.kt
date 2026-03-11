// Version: 1.0.0 | Updated: 2026-03-11
// [2026-03-11] MainScreen からサーバー状態・Tunnel・ログを分離
package jp.salesnow.chromebridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.salesnow.chromebridge.ui.theme.*

@Composable
fun ServerTab(
    serverState: ServerState,
    logs: List<String>,
    savedTunnelToken: String,
    savedTunnelDomain: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onSaveTunnelSettings: (token: String, domain: String) -> Unit,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onClearLogs: () -> Unit
) {
    var tunnelTokenInput by remember(savedTunnelToken) { mutableStateOf(savedTunnelToken) }
    var tunnelDomainInput by remember(savedTunnelDomain) { mutableStateOf(savedTunnelDomain) }
    var logsExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // サーバー状態カード
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    "Port: ${serverState.port}  |  並列: ${serverState.poolSize}  |  処理中: ${serverState.pendingRequests}",
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

                    // [2026-03-11] 稼働時間とプール状態
                    if (serverState.running) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatusChip("稼働時間", formatUptime(serverState.uptimeSeconds))
                            StatusChip("プール", "${serverState.poolAvailable}/${serverState.poolSize}")
                            StatusChip("キュー上限", "${serverState.maxQueueSize}")
                        }
                    }
                }
            }
        }

        // Tunnel セクション
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
    }
}

@Composable
private fun StatusChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = NavyDark)
        Text(label, fontSize = 11.sp, color = GrayLight)
    }
}

private fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
}
