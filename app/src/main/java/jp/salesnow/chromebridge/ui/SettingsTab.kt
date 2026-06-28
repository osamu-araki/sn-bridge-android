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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    // [2026-06-27] 共通 State (リクエストカスタマイズ / サーキットブレーカー / 自動タップ記憶 / サーキット snapshot)
    val sharedCtx = LocalContext.current
    val sharedRepo = remember { SettingsRepository(sharedCtx) }
    var uaInput by remember { mutableStateOf(sharedRepo.defaultUserAgentOverride) }
    var uaRotationInput by remember { mutableStateOf(sharedRepo.userAgentRotation) }
    var cronetInterceptInput by remember { mutableStateOf(sharedRepo.cronetIntercept) }
    var intervalInput by remember { mutableStateOf(sharedRepo.minRequestIntervalMs.toString()) }
    var thresholdInput by remember { mutableStateOf(sharedRepo.circuitFailureThreshold.toString()) }
    var windowInput by remember { mutableStateOf(sharedRepo.circuitWindowMinutes.toString()) }
    var tripInput by remember { mutableStateOf(sharedRepo.circuitTripMinutes.toString()) }
    var snapshotRefresh by remember { mutableIntStateOf(0) }

    // [2026-06-27] 各入力の「画面表示時の baseline」を var で持ち、保存後に更新する
    //   (Codex 指摘: val 固定だと保存しても dirty が残る)
    var uaBaseline by remember { mutableStateOf(sharedRepo.defaultUserAgentOverride) }
    var uaRotationBaseline by remember { mutableStateOf(sharedRepo.userAgentRotation) }
    var cronetInterceptBaseline by remember { mutableStateOf(sharedRepo.cronetIntercept) }
    var intervalBaseline by remember { mutableStateOf(sharedRepo.minRequestIntervalMs) }
    var thresholdBaseline by remember { mutableIntStateOf(sharedRepo.circuitFailureThreshold) }
    var windowBaseline by remember { mutableIntStateOf(sharedRepo.circuitWindowMinutes) }
    var tripBaseline by remember { mutableIntStateOf(sharedRepo.circuitTripMinutes) }

    // [2026-06-27] props 由来も保存後すぐ dirty 解消できるよう SettingsTab 内で baseline 化
    //   (MainActivity の callback が即時に再 compose を引き起こさないケース対応)
    var portBaseline by remember(settings.port) { mutableIntStateOf(settings.port) }
    var apiKeyBaseline by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var concurrencyBaseline by remember(settings.concurrency) { mutableIntStateOf(settings.concurrency) }
    var maxTimeoutBaseline by remember(settings.maxTimeout) { mutableIntStateOf(settings.maxTimeout) }
    var maxWaitBaseline by remember(settings.maxWait) { mutableIntStateOf(settings.maxWait) }
    var tunnelTokenBaseline by remember(savedTunnelToken) { mutableStateOf(savedTunnelToken) }
    var tunnelDomainBaseline by remember(savedTunnelDomain) { mutableStateOf(savedTunnelDomain) }
    var manifestUrlBaseline by remember(portalManifestUrl) { mutableStateOf(portalManifestUrl) }
    var checkTokenBaseline by remember(portalCheckToken) { mutableStateOf(portalCheckToken) }
    var autoUpdateBaseline by remember(autoUpdateCheck) { mutableStateOf(autoUpdateCheck) }

    // [2026-06-27] dirty 検知 (画面表示時の baseline と現在値を比較)
    //   数値系は実効値変換後で比較し "00" vs "0" 等の false positive を避ける (Codex 指摘 #3)
    val portChanged = (portInput.toIntOrNull() ?: portBaseline) != portBaseline
    val apiKeyChanged = apiKeyInput != apiKeyBaseline
    val concurrencyChanged = Math.round(concurrencyInput) != concurrencyBaseline
    val maxTimeoutChanged = (maxTimeoutInput.toIntOrNull() ?: maxTimeoutBaseline).coerceIn(10, 120) != maxTimeoutBaseline
    val maxWaitChanged = (maxWaitInput.toIntOrNull() ?: maxWaitBaseline).coerceIn(1, 30) != maxWaitBaseline
    val tunnelTokenChanged = tunnelTokenInput != tunnelTokenBaseline
    val tunnelDomainChanged = tunnelDomainInput != tunnelDomainBaseline
    val manifestUrlChanged = manifestUrlInput.trim() != manifestUrlBaseline.trim()
    val checkTokenChanged = checkTokenInput.trim() != checkTokenBaseline.trim()
    val autoUpdateChanged = autoUpdateInput != autoUpdateBaseline
    val uaChanged = uaInput.trim() != uaBaseline.trim()
    val uaRotationChanged = uaRotationInput != uaRotationBaseline
    val cronetInterceptChanged = cronetInterceptInput != cronetInterceptBaseline
    val intervalChanged = (intervalInput.toLongOrNull() ?: intervalBaseline).coerceAtLeast(0L) != intervalBaseline
    val thresholdChanged = (thresholdInput.toIntOrNull() ?: thresholdBaseline).coerceAtLeast(1) != thresholdBaseline
    val windowChanged = (windowInput.toIntOrNull() ?: windowBaseline).coerceAtLeast(1) != windowBaseline
    val tripChanged = (tripInput.toIntOrNull() ?: tripBaseline).coerceAtLeast(1) != tripBaseline

    val hasAnyDirty = portChanged || apiKeyChanged || concurrencyChanged ||
        maxTimeoutChanged || maxWaitChanged ||
        tunnelTokenChanged || tunnelDomainChanged ||
        manifestUrlChanged || checkTokenChanged || autoUpdateChanged ||
        uaChanged || uaRotationChanged || cronetInterceptChanged || intervalChanged ||
        thresholdChanged || windowChanged || tripChanged

    val dirtyCount = listOf(
        portChanged, apiKeyChanged, concurrencyChanged, maxTimeoutChanged, maxWaitChanged,
        tunnelTokenChanged, tunnelDomainChanged,
        manifestUrlChanged, checkTokenChanged, autoUpdateChanged,
        uaChanged, uaRotationChanged, cronetInterceptChanged, intervalChanged,
        thresholdChanged, windowChanged, tripChanged
    ).count { it }

    // 再起動が必要な変更 (Port / API Key / 並列数 / Tunnel Token / Tunnel Domain / UA rotation)
    // [2026-06-28] UA rotation の変更は WebView pool の UA 割り当てを再生成する必要があるため再起動必須
    //   Cronet intercept は shouldInterceptRequest 内で都度設定値を読むため再起動不要
    val needsRestart = serverRunning && (
        portChanged || apiKeyChanged || concurrencyChanged ||
        tunnelTokenChanged || tunnelDomainChanged ||
        uaRotationChanged
    )

    val doSave: () -> Unit = saveAll@{
        if (portChanged || apiKeyChanged || concurrencyChanged || maxTimeoutChanged || maxWaitChanged) {
            val newSettings = SettingsState(
                port = portInput.toIntOrNull() ?: portBaseline,
                apiKey = apiKeyInput,
                concurrency = Math.round(concurrencyInput),
                maxTimeout = (maxTimeoutInput.toIntOrNull() ?: maxTimeoutBaseline).coerceIn(10, 120),
                maxWait = (maxWaitInput.toIntOrNull() ?: maxWaitBaseline).coerceIn(1, 30),
            )
            onSave(newSettings)
            // baseline + input を正規化後の値に揃える
            portInput = newSettings.port.toString()
            portBaseline = newSettings.port
            apiKeyBaseline = newSettings.apiKey
            concurrencyInput = newSettings.concurrency.toFloat()
            concurrencyBaseline = newSettings.concurrency
            maxTimeoutInput = newSettings.maxTimeout.toString()
            maxTimeoutBaseline = newSettings.maxTimeout
            maxWaitInput = newSettings.maxWait.toString()
            maxWaitBaseline = newSettings.maxWait
        }
        if (tunnelTokenChanged || tunnelDomainChanged) {
            onSaveTunnelSettings(tunnelTokenInput, tunnelDomainInput)
            tunnelTokenBaseline = tunnelTokenInput
            tunnelDomainBaseline = tunnelDomainInput
        }
        if (manifestUrlChanged || checkTokenChanged || autoUpdateChanged) {
            val trimmedUrl = manifestUrlInput.trim()
            val trimmedToken = checkTokenInput.trim()
            onSavePortalUpdateSettings(trimmedUrl, trimmedToken, autoUpdateInput)
            manifestUrlInput = trimmedUrl
            checkTokenInput = trimmedToken
            manifestUrlBaseline = trimmedUrl
            checkTokenBaseline = trimmedToken
            autoUpdateBaseline = autoUpdateInput
        }
        if (uaChanged || intervalChanged) {
            sharedRepo.defaultUserAgentOverride = uaInput.trim()
            sharedRepo.minRequestIntervalMs = intervalInput.toLongOrNull() ?: 0L
            uaInput = sharedRepo.defaultUserAgentOverride
            intervalInput = sharedRepo.minRequestIntervalMs.toString()
            uaBaseline = sharedRepo.defaultUserAgentOverride
            intervalBaseline = sharedRepo.minRequestIntervalMs
        }
        if (uaRotationChanged) {
            sharedRepo.userAgentRotation = uaRotationInput
            uaRotationBaseline = sharedRepo.userAgentRotation
        }
        if (cronetInterceptChanged) {
            sharedRepo.cronetIntercept = cronetInterceptInput
            cronetInterceptBaseline = sharedRepo.cronetIntercept
        }
        if (thresholdChanged || windowChanged || tripChanged) {
            sharedRepo.circuitFailureThreshold = thresholdInput.toIntOrNull() ?: thresholdBaseline
            sharedRepo.circuitWindowMinutes = windowInput.toIntOrNull() ?: windowBaseline
            sharedRepo.circuitTripMinutes = tripInput.toIntOrNull() ?: tripBaseline
            thresholdInput = sharedRepo.circuitFailureThreshold.toString()
            windowInput = sharedRepo.circuitWindowMinutes.toString()
            tripInput = sharedRepo.circuitTripMinutes.toString()
            thresholdBaseline = sharedRepo.circuitFailureThreshold
            windowBaseline = sharedRepo.circuitWindowMinutes
            tripBaseline = sharedRepo.circuitTripMinutes
        }
    }

    // [2026-06-27] 全 input を baseline で上書き = 入力前の値に戻す
    val doDiscard: () -> Unit = {
        portInput = portBaseline.toString()
        apiKeyInput = apiKeyBaseline
        concurrencyInput = concurrencyBaseline.toFloat()
        maxTimeoutInput = maxTimeoutBaseline.toString()
        maxWaitInput = maxWaitBaseline.toString()
        tunnelTokenInput = tunnelTokenBaseline
        tunnelDomainInput = tunnelDomainBaseline
        manifestUrlInput = manifestUrlBaseline
        checkTokenInput = checkTokenBaseline
        autoUpdateInput = autoUpdateBaseline
        uaInput = uaBaseline
        uaRotationInput = uaRotationBaseline
        cronetInterceptInput = cronetInterceptBaseline
        intervalInput = intervalBaseline.toString()
        thresholdInput = thresholdBaseline.toString()
        windowInput = windowBaseline.toString()
        tripInput = tripBaseline.toString()
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        // [2026-06-27] カテゴリ 1: 接続・基盤
        item {
            CategoryHeader(
                icon = ServerIcon,
                title = "接続・基盤",
                subtitle = "サーバー / アップデート / 省電力",
            )
        }

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

                    // [2026-06-20] 確認中はボタン disable + 円形スピナー表示
                    OutlinedButton(
                        onClick = { onManualUpdateCheck() },
                        enabled = !updateChecking,
                        modifier = Modifier.fillMaxWidth()
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


        // [2026-06-27] カテゴリ 2: fetch 動作
        item {
            CategoryHeader(
                icon = FetchIcon,
                title = "fetch 動作",
                subtitle = "並列・タイムアウト / リクエストカスタマイズ",
            )
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
        //   [2026-06-27] State 宣言は SettingsTab 上部に共通化（保存バー集約のため）
        item {
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
                        // [2026-06-28] UA ローテーション ON 時はカスタム UA を編集不可 (排他)
                        enabled = !uaRotationInput,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { uaInput = DESKTOP_CHROME_UA },
                            enabled = !uaRotationInput,
                        ) {
                            Text("デスクトップ Chrome", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { uaInput = "" },
                            enabled = !uaRotationInput,
                        ) {
                            Text("既定に戻す", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // [2026-06-28] UA ローテーション (WebView ごとに別 UA を割り当てて bot 検出緩和)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "UA ローテーション",
                            fontSize = 14.sp,
                            color = NavyDark,
                        )
                        Switch(
                            checked = uaRotationInput,
                            onCheckedChange = { uaRotationInput = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Teal)
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    // [2026-06-28] Cronet TLS intercept (WebView の subresource を Cronet 経由 fetch)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Cronet TLS Fingerprint",
                            fontSize = 14.sp,
                            color = NavyDark,
                        )
                        Switch(
                            checked = cronetInterceptInput,
                            onCheckedChange = { cronetInterceptInput = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Teal)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // [2026-06-28] TLS Fingerprint 計測ボタン (Phase 2a PoC: tls.peet.ws で JA3/JA4 取得)
                    val cronetScope = rememberCoroutineScope()
                    var fingerprintResult by remember { mutableStateOf("") }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            fingerprintResult = "計測中..."
                            cronetScope.launch(Dispatchers.IO) {
                                val cronet = jp.salesnow.chromebridge.fetcher.CronetManager.fetchSync(
                                    url = "https://tls.peet.ws/api/all",
                                    timeoutSeconds = 30,
                                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; SM-S928U) AppleWebKit/537.36 Chrome/123.0.0.0 Mobile Safari/537.36"),
                                )
                                fingerprintResult = if (cronet == null) {
                                    "Cronet fetch 失敗 (engine 初期化失敗 or timeout)"
                                } else {
                                    val (status, body) = cronet
                                    val ja3HashMatch = Regex("\"ja3_hash\":\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                                    val ja4Match = Regex("\"ja4\":\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                                    val peetprintHashMatch = Regex("\"peetprint_hash\":\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                                    val akamaiHashMatch = Regex("\"akamai_fingerprint_hash\":\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                                    buildString {
                                        appendLine("status=$status")
                                        appendLine("JA3 hash:")
                                        appendLine("  ${ja3HashMatch ?: "?"}")
                                        appendLine("JA4:")
                                        appendLine("  ${ja4Match ?: "?"}")
                                        appendLine("peetprint hash:")
                                        appendLine("  ${peetprintHashMatch ?: "?"}")
                                        append("akamai (HTTP/2) hash:\n  ${akamaiHashMatch ?: "?"}")
                                    }
                                }
                            }
                        },
                    ) {
                        Text("TLS Fingerprint 計測 (Cronet)", fontSize = 12.sp)
                    }
                    if (fingerprintResult.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            fingerprintResult,
                            fontSize = 11.sp,
                            color = NavyDark,
                            modifier = Modifier.fillMaxWidth()
                        )
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
                }
            }
        }

        // [2026-06-27] カテゴリ 3: チャレンジ・ブロック
        item {
            CategoryHeader(
                icon = ShieldIcon,
                title = "チャレンジ・ブロック",
                subtitle = "画面表示 / 自動タップ記憶 / サーキットブレーカー / オーバーレイ権限",
            )
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
                        "画面表示",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = NavyDark
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Bridge 動作中の WebView 画面を表示するか", fontSize = 13.sp, color = NavyDark, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    val options = listOf(
                        Triple(
                            SettingsRepository.DISPLAY_MODE_MANUAL_ONLY,
                            "手動タップが必要な時だけ表示",
                            "memory ありは画面を出さずに自動突破。memory なしは Slack 通知のみ",
                        ),
                        Triple(
                            SettingsRepository.DISPLAY_MODE_EXCLUDE_HEALTHCHECK,
                            "チャレンジ認証すべてを表示（ヘルスチェック除外）",
                            "通常の challenge は画面表示。ポータル cron 由来は Slack 通知のみ",
                        ),
                        Triple(
                            SettingsRepository.DISPLAY_MODE_ALL,
                            "すべて表示",
                            "challenge を含む全 fetch リクエストの WebView を画面表示（デバッグ・可視化用）",
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
        // [2026-06-26] Google サーキットブレーカー設定
        //   [2026-06-27] State 宣言は SettingsTab 上部に共通化（保存バー集約のため）
        item {
            // 状態スナップショット表示（trip 中の host とその残り秒数）
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

        // [2026-06-27] カテゴリ 4: 通知・ログ
        item {
            CategoryHeader(
                icon = BellIcon,
                title = "通知・ログ",
                subtitle = "Slack 通知は「チャレンジ・ブロック」内 / ログ設定",
            )
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

    }

    // [2026-06-27] sticky 保存バー (画面下に常時固定)
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
        color = if (hasAnyDirty) Color(0xFFFFF8E6) else Color.White,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (hasAnyDirty) "● 未保存の変更が ${dirtyCount} 件" else "変更なし",
                fontSize = 12.sp,
                color = if (hasAnyDirty) Color(0xFFB57E1A) else GrayLight,
                fontWeight = if (hasAnyDirty) FontWeight.Bold else FontWeight.Normal,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { doDiscard() },
                    enabled = hasAnyDirty,
                ) {
                    Text("破棄")
                }
                Button(
                    onClick = {
                        if (needsRestart) {
                            showRestartDialog = true
                        } else {
                            doSave()
                        }
                    },
                    enabled = hasAnyDirty,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal),
                ) {
                    Text("保存")
                }
            }
        }
    }
    }  // end of Box

    // [2026-06-27] 再起動確認ダイアログ (保存前に表示)
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("サーバーの再起動が必要です") },
            text = {
                Text(
                    "Port / API Key / 同時並列数 / Tunnel 設定の変更を反映するにはサーバーの再起動が必要です。" +
                        "再起動中の数秒間は fetch リクエストを受け付けません。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    doSave()
                    onRestartServer()
                }) {
                    Text("保存して再起動", color = Teal)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showRestartDialog = false
                        doSave()
                    }) {
                        Text("保存のみ", color = NavyDark)
                    }
                    TextButton(onClick = { showRestartDialog = false }) {
                        Text("キャンセル", color = GrayLight)
                    }
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
