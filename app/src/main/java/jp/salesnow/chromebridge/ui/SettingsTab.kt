// Version: 1.7.0 | Updated: 2026-05-20
// [2026-03-11] 全設定を1画面にまとめた設定タブ
// [2026-03-13] チャレンジ認証のバックグラウンド通知トグル追加
// [2026-04-11] オーバーレイ権限（タップ不要起動）の状態表示と付与ボタン追加
// [2026-05-20] 省電力対策セクション（バッテリー最適化除外 + OEM 自動起動誘導）を追加
package jp.salesnow.chromebridge.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import jp.salesnow.chromebridge.data.SettingsRepository
import jp.salesnow.chromebridge.fetcher.GoogleSearchCircuitBreaker
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    onSelectBackupFolder: () -> Unit = {},
    // [2026-06-10] OTA: アップデート設定
    portalManifestUrl: String = "",
    portalCheckToken: String = "",
    autoUpdateCheck: Boolean = true,
    onSavePortalUpdateSettings: (url: String, token: String, auto: Boolean) -> Unit = { _, _, _ -> },
    onManualUpdateCheck: () -> Unit = {},
    currentVersionName: String = "",
    currentVersionCode: Int = 0,
    // [2026-06-20] 手動 update check の状態（true なら実行中、ボタン disable + スピナー表示）
    updateChecking: Boolean = false,
    // [2026-06-20] 直近の update check 結果メッセージ（null なら未実行）
    updateResultMessage: String? = null,
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
    // [2026-06-10] OTA: アップデート設定
    var manifestUrlInput by remember(portalManifestUrl) { mutableStateOf(portalManifestUrl) }
    var checkTokenInput by remember(portalCheckToken) { mutableStateOf(portalCheckToken) }
    var autoUpdateInput by remember(autoUpdateCheck) { mutableStateOf(autoUpdateCheck) }
    var checkTokenVisible by remember { mutableStateOf(false) }

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

        // [2026-06-10] OTA アップデート設定
        item {
            val ctx = LocalContext.current
            val lcOwner = LocalLifecycleOwner.current
            var installAllowed by remember {
                mutableStateOf(ctx.packageManager.canRequestPackageInstalls())
            }
            DisposableEffect(lcOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        installAllowed = ctx.packageManager.canRequestPackageInstalls()
                    }
                }
                lcOwner.lifecycle.addObserver(observer)
                onDispose { lcOwner.lifecycle.removeObserver(observer) }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "アップデート",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "現在のバージョン: $currentVersionName (code $currentVersionCode)",
                        fontSize = 12.sp,
                        color = GrayLight
                    )
                    Spacer(Modifier.height(12.dp))

                    // [Codex#1] 不明な提供元のインストール許可状態
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("不明な提供元のインストール許可", fontSize = 14.sp, color = NavyDark)
                            Text(
                                if (installAllowed) "許可済み（OTA 可能）"
                                else "未許可（OTA はユーザー操作が必要になります）",
                                fontSize = 11.sp,
                                color = if (installAllowed) Teal else GrayLight
                            )
                        }
                        if (!installAllowed) {
                            OutlinedButton(onClick = { openUnknownAppSourcesSettings(ctx) }) {
                                Text("許可設定")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = manifestUrlInput,
                        onValueChange = { manifestUrlInput = it },
                        label = { Text("Portal Manifest URL") },
                        placeholder = { Text("https://portal.example.com/api/bridge-app/manifest") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = checkTokenInput,
                        onValueChange = { checkTokenInput = it },
                        label = { Text("Check Token (Bearer)") },
                        visualTransformation = if (checkTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { checkTokenVisible = !checkTokenVisible }) {
                                Icon(
                                    imageVector = if (checkTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (checkTokenVisible) "非表示" else "表示"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("自動チェック（1時間ごと）", fontSize = 14.sp, color = NavyDark)
                        Switch(
                            checked = autoUpdateInput,
                            onCheckedChange = { autoUpdateInput = it }
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // [2026-06-20] 確認中はボタン disable + 円形スピナー表示
                        OutlinedButton(
                            onClick = { onManualUpdateCheck() },
                            enabled = !updateChecking,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (updateChecking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Teal,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("確認中…")
                            } else {
                                Text("今すぐ確認")
                            }
                        }
                        Button(
                            onClick = {
                                onSavePortalUpdateSettings(
                                    manifestUrlInput.trim(),
                                    checkTokenInput.trim(),
                                    autoUpdateInput,
                                )
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Teal)
                        ) {
                            Text("設定を保存")
                        }
                    }
                    // [2026-06-20] 直近の update check 結果を表示
                    if (updateResultMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            updateResultMessage,
                            fontSize = 12.sp,
                            color = NavyDark,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF1F5F7), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // [2026-05-20] 省電力対策（OS / OEM の省電力でアプリが停止される問題への対策）
        item {
            val ctx = LocalContext.current
            val lcOwner = LocalLifecycleOwner.current
            var batteryOptIgnored by remember { mutableStateOf(isBatteryOptimizationIgnored(ctx)) }
            DisposableEffect(lcOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        batteryOptIgnored = isBatteryOptimizationIgnored(ctx)
                    }
                }
                lcOwner.lifecycle.addObserver(observer)
                onDispose { lcOwner.lifecycle.removeObserver(observer) }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "省電力対策",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Text(
                        "長時間稼働させるため、OS とメーカー独自の省電力機構を解除します",
                        fontSize = 11.sp,
                        color = GrayLight
                    )
                    Spacer(Modifier.height(12.dp))

                    // バッテリー最適化除外（Android 標準）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("バッテリー最適化除外", fontSize = 14.sp, color = NavyDark)
                            Text(
                                if (batteryOptIgnored) "有効（除外済み）"
                                else "未設定（OS に停止されやすい状態）",
                                fontSize = 11.sp,
                                color = if (batteryOptIgnored) Teal else GrayLight
                            )
                        }
                        if (!batteryOptIgnored) {
                            OutlinedButton(onClick = { requestBatteryOptimizationExemption(ctx) }) {
                                Text("除外設定")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))

                    // OEM 別の自動起動許可（OPPO/Xiaomi/Huawei 等は標準だけでは不十分）
                    Text("メーカー独自の自動起動許可", fontSize = 14.sp, color = NavyDark)
                    Text(
                        "OPPO / Xiaomi / Huawei / Vivo 等は標準のバッテリー最適化除外だけでは不十分です。" +
                            "各メーカーの「自動起動管理」を必ず許可してください。",
                        fontSize = 11.sp,
                        color = GrayLight
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { openOemAutoStartSettings(ctx) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("自動起動設定を開く（${android.os.Build.MANUFACTURER}）")
                    }
                }
            }
        }

        // [2026-06-25] チャレンジ自動タップ記憶（ドメイン別の学習一覧 + 削除）
        item {
            val ctx = LocalContext.current
            val repo = remember { SettingsRepository(ctx) }
            // refreshTrigger を Int でカウントアップして強制 re-compose
            var refreshTrigger by remember { mutableIntStateOf(0) }
            val entries = remember(refreshTrigger) {
                runCatching { repo.listTapMemoryEntries() }.getOrDefault(emptyList())
            }
            // [2026-06-26] チャレンジ画面の表示モード（3 択）
            var displayMode by remember { mutableIntStateOf(repo.challengeDisplayMode) }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "チャレンジ自動タップ記憶",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("チャレンジ画面の表示", fontSize = 13.sp, color = NavyDark, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    val options = listOf(
                        Triple(
                            SettingsRepository.DISPLAY_MODE_MANUAL_ONLY,
                            "手動タップが必要な時だけ表示",
                            "memory ありは画面を出さずに自動突破。memory なしは Slack 通知のみ",
                        ),
                        Triple(
                            SettingsRepository.DISPLAY_MODE_EXCLUDE_HEALTHCHECK,
                            "すべて表示（ヘルスチェック除外）",
                            "通常の challenge は画面表示。ポータル cron 由来は Slack 通知のみ",
                        ),
                        Triple(
                            SettingsRepository.DISPLAY_MODE_ALL,
                            "すべて表示",
                            "全 challenge で画面を開く（従来挙動）",
                        ),
                    )
                    for ((mode, title, desc) in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (mode == displayMode),
                                    onClick = {
                                        displayMode = mode
                                        repo.challengeDisplayMode = mode
                                    },
                                    role = Role.RadioButton,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = (mode == displayMode),
                                onClick = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, fontSize = 13.sp, color = NavyDark)
                                Text(desc, fontSize = 11.sp, color = GrayLight)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (entries.isEmpty()) {
                        Text("学習データはありません", fontSize = 12.sp, color = GrayLight)
                    } else {
                        for ((domain, coord) in entries) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(domain, fontSize = 13.sp, color = NavyDark)
                                    Text(
                                        "(${coord.x.toInt()}, ${coord.y.toInt()}) · 学習: " +
                                            java.text.SimpleDateFormat(
                                                "yyyy/MM/dd HH:mm:ss",
                                                java.util.Locale.JAPAN
                                            ).apply {
                                                timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
                                            }.format(java.util.Date(coord.ts)),
                                        fontSize = 11.sp,
                                        color = GrayLight,
                                    )
                                }
                                TextButton(onClick = {
                                    repo.clearTapMemory(domain)
                                    refreshTrigger++
                                }) {
                                    Text("削除", color = Teal, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                repo.clearTapMemory()
                                refreshTrigger++
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("すべての学習データを削除")
                        }
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

        // [2026-06-26] リクエストカスタマイズ（デフォルト UA + 同一ホスト最小間隔）
        //   Google 403 等のクライアント単位ブロックの予防策。
        item {
            val ctx = LocalContext.current
            val repo = remember { SettingsRepository(ctx) }
            var uaInput by remember { mutableStateOf(repo.defaultUserAgentOverride) }
            var intervalInput by remember { mutableStateOf(repo.minRequestIntervalMs.toString()) }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "リクエストカスタマイズ",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uaInput,
                        onValueChange = { uaInput = it },
                        label = { Text("デフォルト User-Agent (空欄で WebView 既定)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { uaInput = DESKTOP_CHROME_UA },
                        ) {
                            Text("デスクトップ Chrome", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { uaInput = "" },
                        ) {
                            Text("既定に戻す", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = intervalInput,
                        onValueChange = { intervalInput = it.filter { c -> c.isDigit() } },
                        label = { Text("同一ホスト最小間隔（ミリ秒、0で無効）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            repo.defaultUserAgentOverride = uaInput.trim()
                            repo.minRequestIntervalMs = intervalInput.toLongOrNull() ?: 0L
                        },
                    ) {
                        Text("保存")
                    }
                }
            }
        }

        // [2026-06-26] Google サーキットブレーカー設定
        item {
            val ctx = LocalContext.current
            val repo = remember { SettingsRepository(ctx) }
            var thresholdInput by remember { mutableStateOf(repo.circuitFailureThreshold.toString()) }
            var windowInput by remember { mutableStateOf(repo.circuitWindowMinutes.toString()) }
            var tripInput by remember { mutableStateOf(repo.circuitTripMinutes.toString()) }
            // 状態スナップショット表示（trip 中の host とその残り秒数）
            var snapshotRefresh by remember { mutableIntStateOf(0) }
            val snapshot = remember(snapshotRefresh) {
                runCatching { GoogleSearchCircuitBreaker.snapshot() }.getOrDefault(emptyMap())
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Google サーキットブレーカー",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Text(
                        "Google 検索の極小レスポンス（403 等）を検知したら一定時間 fetch を 499 で拒否",
                        fontSize = 11.sp,
                        color = GrayLight,
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = thresholdInput,
                        onValueChange = { thresholdInput = it.filter { c -> c.isDigit() } },
                        label = { Text("失敗閾値（回）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = windowInput,
                        onValueChange = { windowInput = it.filter { c -> c.isDigit() } },
                        label = { Text("集計 window（分）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tripInput,
                        onValueChange = { tripInput = it.filter { c -> c.isDigit() } },
                        label = { Text("停止時間（分）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            repo.circuitFailureThreshold = thresholdInput.toIntOrNull() ?: 3
                            repo.circuitWindowMinutes = windowInput.toIntOrNull() ?: 5
                            repo.circuitTripMinutes = tripInput.toIntOrNull() ?: 60
                            // [2026-06-26] Codex 指摘: 空欄 / 0 入力は repo 側で 1 に丸められるので
                            //   保存後に input を再同期して UI 表示と実値のズレを防ぐ
                            thresholdInput = repo.circuitFailureThreshold.toString()
                            windowInput = repo.circuitWindowMinutes.toString()
                            tripInput = repo.circuitTripMinutes.toString()
                        },
                    ) {
                        Text("保存")
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("現在の状態", fontSize = 13.sp, color = NavyDark, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    if (snapshot.isEmpty()) {
                        Text("停止中のホストはありません", fontSize = 12.sp, color = GrayLight)
                    } else {
                        for ((host, remainingMs) in snapshot) {
                            val remainSec = ((remainingMs + 999) / 1000)
                            val remainMin = remainSec / 60
                            val displaySec = remainSec % 60
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(host, fontSize = 13.sp, color = NavyDark)
                                    Text(
                                        "残り ${remainMin}分${displaySec}秒",
                                        fontSize = 11.sp,
                                        color = GrayLight,
                                    )
                                }
                                TextButton(onClick = {
                                    GoogleSearchCircuitBreaker.reset(host)
                                    snapshotRefresh++
                                }) {
                                    Text("即時解除", color = Teal, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { snapshotRefresh++ },
                        ) {
                            Text("再読込", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                GoogleSearchCircuitBreaker.reset()
                                snapshotRefresh++
                            },
                        ) {
                            Text("すべて解除", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // [2026-03-13] チャレンジ認証設定
        item {
            // [2026-04-11] オーバーレイ権限の状態を lifecycle で追従
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            var canDrawOverlays by remember {
                mutableStateOf(android.provider.Settings.canDrawOverlays(context))
            }
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        canDrawOverlays = android.provider.Settings.canDrawOverlays(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

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

                    // [2026-04-11] タップ不要起動（オーバーレイ権限）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "タップ不要で直接起動",
                                fontSize = 14.sp,
                                color = NavyDark
                            )
                            Text(
                                if (canDrawOverlays) "有効（オーバーレイ権限あり）"
                                else "通知タップが必要な状態です",
                                fontSize = 11.sp,
                                color = if (canDrawOverlays) Teal else GrayLight
                            )
                        }
                        Switch(
                            checked = canDrawOverlays,
                            onCheckedChange = {
                                // ON/OFF どちらもシステム設定画面へ誘導
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Teal,
                                checkedTrackColor = Teal.copy(alpha = 0.3f)
                            )
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(12.dp))

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
                                "オーバーレイ権限が無い場合のフォールバック",
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

// [2026-05-20] バッテリー最適化除外状態の取得
private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

// [2026-06-10] OTA: 「不明な提供元のインストール」許可設定画面を開く（Codex#1）
private fun openUnknownAppSourcesSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // フォールバック: アプリ詳細設定
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}

// [2026-05-20] バッテリー最適化除外を要求するシステムダイアログを開く
private fun requestBatteryOptimizationExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // フォールバック: 全アプリの最適化リスト
        try {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        } catch (_: Exception) {}
    }
}

/**
 * [2026-05-20] メーカー独自の自動起動管理画面を開く。
 * OPPO / Xiaomi / Huawei / Vivo 等は標準のバッテリー最適化除外だけでは不十分で、
 * 各メーカー専用の「自動起動許可」画面で追加許可が必要。
 * 候補 Activity を順に試し、いずれも失敗したらアプリ情報画面にフォールバックする。
 */
private fun openOemAutoStartSettings(context: Context) {
    val manufacturer = android.os.Build.MANUFACTURER.lowercase()
    val attempts: List<ComponentName> = when (manufacturer) {
        "xiaomi", "redmi", "poco" -> listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        "oppo", "realme" -> listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            ComponentName(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            )
        )
        "huawei", "honor" -> listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )
        "vivo", "iqoo" -> listOf(
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            ),
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        )
        "samsung" -> listOf(
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )
        else -> emptyList()
    }

    for (cn in attempts) {
        val intent = Intent().apply {
            component = cn
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) { /* 次の候補を試行 */ }
    }

    // フォールバック: アプリ情報画面（ユーザーが自力でナビゲートできる起点）
    try {
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
    } catch (_: Exception) {}
}

// [2026-06-26] ワンタップで設定できるデスクトップ Chrome UA。
//   Google が WebView UA (`; wv`) を flag するケースの回避策として、デフォルトで提示する。
//   2025 後半時点の安定版 Chrome バージョンを使用。
private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
