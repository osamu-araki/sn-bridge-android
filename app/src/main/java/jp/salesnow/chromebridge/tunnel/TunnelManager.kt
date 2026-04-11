// Version: 2.0.0 | Updated: 2026-03-12
// [2026-03-08] cloudflared バイナリを ProcessBuilder で起動し Cloudflare Tunnel を管理
// [2026-03-08] nativeLibraryDir の libcloudflared.so を直接実行（SELinux execute 許可済み領域）
// [2026-03-08] Go DNS リゾルバ対策: /etc/resolv.conf が無い環境用に resolv.conf を生成
// [2026-03-08] --config で ingress ルール付き config.yml を動的生成して起動
// [2026-03-12] 接続タイムアウト・キープアライブ調整、自動再起動機能追加
package jp.salesnow.chromebridge.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import jp.salesnow.chromebridge.data.SettingsRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * cloudflared プロセスを管理する。
 * jniLibs/arm64-v8a/libcloudflared.so として APK に同梱。
 * nativeLibraryDir に展開され、SELinux で execute が許可される。
 */
class TunnelManager(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    private var process: Process? = null
    private var logThread: Thread? = null
    private var dnsProxy: DnsProxy? = null

    // [2026-03-12] 自動再起動用の状態管理
    private var autoRestart = true
    private var lastToken: String = ""
    private var lastPort: Int = 3000
    private var lastDomain: String = ""
    private var restartCount = 0
    private val maxRestarts = 5
    private var restartThread: Thread? = null
    private var healthCheckThread: Thread? = null
    private var consecutiveFailures = 0
    private val healthCheckFailThreshold = 3

    val isRunning: Boolean get() = process?.isAlive == true

    private fun getBinaryPath(): String {
        return File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so").absolutePath
    }

    // [2026-03-08] Go の DNS リゾルバは /etc/resolv.conf を参照するが、Android には存在しない。
    // ConnectivityManager から DNS サーバーを取得し、filesDir に resolv.conf を生成する。
    private fun ensureResolvConf(): File {
        val resolvConf = File(context.filesDir, "resolv.conf")
        val dnsServers = mutableListOf<String>()

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val linkProps: LinkProperties? = if (network != null) cm.getLinkProperties(network) else null
            linkProps?.dnsServers?.forEach { addr ->
                dnsServers.add(addr.hostAddress ?: return@forEach)
            }
        } catch (_: Exception) {}

        if (dnsServers.isEmpty()) {
            dnsServers.addAll(listOf("8.8.8.8", "8.8.4.4"))
        }

        resolvConf.writeText(dnsServers.joinToString("\n") { "nameserver $it" } + "\n")
        return resolvConf
    }

    // [2026-03-08] /etc/resolv.conf が存在しない場合、bind mount で配置を試みる。
    // root 権限がない実デバイスでは失敗するが、多くの実デバイスでは /etc/resolv.conf が
    // 既に存在するか、Go が cgo 経由で Android のシステム DNS を使用する。
    private fun setupSystemResolvConf(resolvConf: File) {
        try {
            if (File("/etc/resolv.conf").exists()) {
                if (SettingsRepository(context).verboseLog) onLog("DNS: /etc/resolv.conf が存在します")
                return
            }
            // bind mount を試みる（エミュレータ等 root 環境向け）
            val rt = Runtime.getRuntime()
            rt.exec(arrayOf("sh", "-c", "mkdir -p /data/local/tmp/etc_overlay")).waitFor()
            rt.exec(arrayOf("sh", "-c", "cp -a /etc/* /data/local/tmp/etc_overlay/ 2>/dev/null")).waitFor()
            rt.exec(arrayOf("sh", "-c", "cp ${resolvConf.absolutePath} /data/local/tmp/etc_overlay/resolv.conf")).waitFor()
            rt.exec(arrayOf("sh", "-c", "mount --bind /data/local/tmp/etc_overlay /etc")).waitFor()
            val v = SettingsRepository(context).verboseLog
            if (File("/etc/resolv.conf").exists()) {
                if (v) onLog("DNS: /etc/resolv.conf を配置しました")
            } else {
                if (v) onLog("DNS: /etc/resolv.conf の配置に失敗（実デバイスでは通常問題なし）")
            }
        } catch (e: Exception) {
            if (SettingsRepository(context).verboseLog) onLog("DNS セットアップ: ${e.message}")
        }
    }

    // [2026-03-08] config.yml を filesDir に生成する。
    // --token で起動する場合、ingress ルールがないと 503 になるため必須。
    private fun generateConfig(domain: String, port: Int): File {
        val configFile = File(context.filesDir, "config.yml")
        val yaml = buildString {
            appendLine("ingress:")
            if (domain.isNotBlank()) {
                appendLine("  - hostname: $domain")
                appendLine("    service: http://localhost:$port")
            }
            appendLine("  - service: http://localhost:$port")
        }
        configFile.writeText(yaml)
        return configFile
    }

    /**
     * Tunnel を開始する。
     * @param token cloudflared tunnel token で取得したトークン
     * @param port ローカルサーバーのポート番号
     * @param domain Tunnel に紐づくドメイン（ingress ルールに使用、空の場合はキャッチオール）
     */
    // [2026-03-12] orphan cloudflared プロセスを kill する
    // APK 再インストール時に前回の cloudflared が残る問題への対策
    fun killOrphanProcesses() {
        try {
            val binary = getBinaryPath()
            val binaryName = File(binary).name // "libcloudflared.so"
            // ps で cloudflared プロセスを探して kill
            val ps = ProcessBuilder("sh", "-c", "ps -ef | grep $binaryName | grep -v grep")
                .redirectErrorStream(true).start()
            val lines = ps.inputStream.bufferedReader().readLines()
            ps.waitFor()
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val pid = parts[1]
                    try {
                        Runtime.getRuntime().exec(arrayOf("kill", pid)).waitFor()
                        if (SettingsRepository(context).verboseLog) onLog("orphan cloudflared プロセスを停止しました (PID: $pid)")
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    fun start(token: String, port: Int, domain: String = "") {
        if (isRunning) {
            onLog("Tunnel は既に稼働中です")
            return
        }

        // [2026-03-12] orphan プロセスを先に kill
        killOrphanProcesses()

        if (token.isBlank()) {
            onLog("Tunnel トークンが未設定です")
            return
        }

        val binary = getBinaryPath()
        if (!File(binary).exists()) {
            onLog("cloudflared バイナリが見つかりません: $binary")
            return
        }

        // [2026-03-12] 再起動用にパラメータを保存
        lastToken = token
        lastPort = port
        lastDomain = domain
        autoRestart = true

        startInternal(token, port, domain, binary)
    }

    private fun startInternal(token: String, port: Int, domain: String, binary: String) {
        try {
            // [2026-03-08] Go DNS 対策: /etc/resolv.conf が無い場合に備え、
            // ローカル DNS プロキシを起動して [::1]:53 / 127.0.0.1:53 で応答する
            if (dnsProxy == null && !File("/etc/resolv.conf").exists()) {
                if (SettingsRepository(context).verboseLog) onLog("DNS: /etc/resolv.conf が存在しないため DNS プロキシを起動します")
                // [2026-03-14] DNS WRN は簡易ログモードでは非表示
                dnsProxy = DnsProxy(context) { msg ->
                    if (msg.contains("WRN")) {
                        if (SettingsRepository(context).verboseLog) onLog(msg)
                    } else {
                        onLog(msg)
                    }
                }
                dnsProxy?.start()
                Thread.sleep(500)
            }

            // [2026-03-08] ingress ルール付き config.yml を生成
            val configFile = generateConfig(domain, port)

            // [2026-03-12] cloudflared 接続チューニング:
            // --proxy-connect-timeout: origin への接続タイムアウト（WebView は処理に数秒かかる）
            // --proxy-keepalive-connections: origin へのキープアライブ接続数
            // --proxy-keepalive-timeout: キープアライブ接続のタイムアウト
            // --grace-period: シャットダウン時の猶予期間
            val pb = ProcessBuilder(
                binary,
                "tunnel",
                "--no-autoupdate",
                // [2026-03-12] QUIC: UDP ベースで接続管理が軽量、HTTP/2 より同時接続耐性が高い
                "--protocol", "quic",
                "--proxy-connect-timeout", "120s",
                "--proxy-keepalive-connections", "100",
                "--proxy-keepalive-timeout", "120s",
                "--grace-period", "30s",
                "--config", configFile.absolutePath,
                "run",
                "--token", token
            )
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = context.filesDir.absolutePath

            process = pb.start()
            onLog("Tunnel 起動中...")

            // [2026-03-08] ログ読み取りスレッド + プロセス終了検知
            // [2026-03-12] 異常終了時の自動再起動機能追加
            logThread = Thread {
                try {
                    process?.inputStream?.bufferedReader()?.useLines { lines ->
                        for (line in lines) {
                            if (Thread.currentThread().isInterrupted) break
                            parseTunnelLog(line)
                        }
                    }
                } catch (_: Exception) {
                    // プロセス終了時
                }
                // プロセスの終了コードを記録
                try {
                    val exitCode = process?.waitFor() ?: -1
                    onLog("Tunnel プロセス終了 (exit code: $exitCode)")
                    process = null

                    // [2026-03-12] autoRestart が有効なら exit code に関わらず再起動
                    // stop() 呼び出し時は autoRestart=false になるので再起動しない
                    if (autoRestart && restartCount < maxRestarts) {
                        restartCount++
                        val delaySec = minOf(restartCount * 3, 15) // 3s, 6s, 9s, 12s, 15s
                        onLog("Tunnel 自動再起動 ($restartCount/$maxRestarts): ${delaySec}秒後...")
                        restartThread = Thread {
                            try {
                                Thread.sleep(delaySec * 1000L)
                                if (autoRestart) {
                                    startInternal(lastToken, lastPort, lastDomain, getBinaryPath())
                                }
                            } catch (_: InterruptedException) {
                                // stop() で中断された場合
                            }
                        }.apply {
                            isDaemon = true
                            name = "tunnel-restart"
                            start()
                        }
                    } else if (!autoRestart) {
                        // stop() による正常終了 — 何もしない
                    } else {
                        onLog("Tunnel 再起動上限 (${maxRestarts}回) に達しました。手動で再接続してください。")
                        restartCount = 0
                    }
                } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                name = "tunnel-log"
                start()
            }
            // [2026-03-12] ヘルスチェック: localhost にリクエストして応答を確認
            // cloudflared が alive でも 502 を返し続ける場合を検知して kill → 再起動
            startHealthCheck(port)

        } catch (e: Exception) {
            onLog("Tunnel 起動エラー: ${e.message}")
        }
    }

    // [2026-03-12] ヘルスチェックスレッド: 20秒間隔で Tunnel 経由の /status を確認
    // localhost ではなく Tunnel ドメイン経由でエンドツーエンド疎通を確認する
    // 連続 healthCheckFailThreshold 回失敗したらプロセスを kill して再起動
    private fun startHealthCheck(port: Int) {
        healthCheckThread?.interrupt()
        consecutiveFailures = 0

        healthCheckThread = Thread {
            // Tunnel 接続確立まで30秒待機
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { return@Thread }

            // Tunnel ドメインが設定されていない場合はヘルスチェック不可
            if (lastDomain.isBlank()) {
                if (SettingsRepository(context).verboseLog) onLog("Tunnel ヘルスチェック: ドメイン未設定のためスキップ")
                return@Thread
            }

            val healthUrl = "https://$lastDomain/status"
            if (SettingsRepository(context).verboseLog) {
                onLog("Tunnel ヘルスチェック開始: $healthUrl (20秒間隔)")
            }

            while (!Thread.currentThread().isInterrupted && autoRestart) {
                try {
                    Thread.sleep(20_000)
                } catch (_: InterruptedException) {
                    break
                }

                // プロセス終了+再起動中も監視を続ける（autoRestart が false なら停止）
                if (!autoRestart) break

                try {
                    val conn = URL(healthUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Authorization", "Bearer salesnow")
                    val code = conn.responseCode
                    conn.disconnect()

                    if (code == 200) {
                        if (consecutiveFailures > 0) {
                            onLog("Tunnel ヘルスチェック回復 (失敗${consecutiveFailures}回後)")
                        }
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        onLog("Tunnel ヘルスチェック失敗 ($consecutiveFailures/$healthCheckFailThreshold): HTTP $code")
                    }
                } catch (e: Exception) {
                    consecutiveFailures++
                    onLog("Tunnel ヘルスチェック失敗 ($consecutiveFailures/$healthCheckFailThreshold): ${e.message}")
                }

                if (consecutiveFailures >= healthCheckFailThreshold && autoRestart && restartCount < maxRestarts) {
                    onLog("Tunnel ヘルスチェック ${healthCheckFailThreshold}回連続失敗: プロセスを再起動します")
                    forceRestart()
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "tunnel-health"
            start()
        }
    }

    // [2026-03-12] プロセスを強制終了して再起動
    private fun forceRestart() {
        process?.let { p ->
            p.destroyForcibly()
            try { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        process = null
        logThread?.interrupt()
        logThread = null

        restartCount++
        val delaySec = minOf(restartCount * 3, 15)
        onLog("Tunnel 強制再起動 ($restartCount/$maxRestarts): ${delaySec}秒後...")

        restartThread = Thread {
            try {
                Thread.sleep(delaySec * 1000L)
                if (autoRestart) {
                    startInternal(lastToken, lastPort, lastDomain, getBinaryPath())
                }
            } catch (_: InterruptedException) {}
        }.apply {
            isDaemon = true
            name = "tunnel-restart"
            start()
        }
    }

    fun stop() {
        // [2026-03-12] 自動再起動を無効化してから停止
        autoRestart = false
        restartCount = 0
        restartThread?.interrupt()
        restartThread = null
        healthCheckThread?.interrupt()
        healthCheckThread = null

        process?.let { p ->
            p.destroy()
            try {
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
                p.destroyForcibly()
            }
        }
        process = null
        logThread?.interrupt()
        logThread = null
        dnsProxy?.stop()
        dnsProxy = null
        onLog("Tunnel 停止")
    }

    // [2026-03-14] 簡易/詳細ログモード対応
    // 簡易: 重要イベントのみ（接続確立、エラー、再接続、クラッシュ）
    // 詳細: cloudflared の全出力を含む
    private fun parseTunnelLog(line: String) {
        val verbose = SettingsRepository(context).verboseLog
        when {
            line.contains("Registered tunnel connection") -> {
                restartCount = 0
                onLog("Tunnel 接続確立")
            }
            line.contains("Initial protocol") ->
                onLog("Tunnel プロトコル確立")
            line.contains("Starting tunnel") ->
                onLog("Tunnel 開始")
            // [2026-03-14] WRN は簡易ログモードでは非表示（ERR より先に判定）
            line.contains("WRN") -> {
                if (verbose) onLog("Tunnel: $line")
            }
            line.contains("ERR") || line.contains("error") || line.contains("fatal") ->
                onLog("Tunnel: $line")
            line.contains("Retrying") ->
                onLog("Tunnel 再接続中...")
            line.contains("signal") || line.contains("panic") || line.contains("SIGILL") ->
                onLog("Tunnel CRASH: $line")
            else -> {
                // [2026-03-14] 詳細モード時のみ全ログを出力
                if (verbose) onLog("cf: $line")
            }
        }
    }
}
