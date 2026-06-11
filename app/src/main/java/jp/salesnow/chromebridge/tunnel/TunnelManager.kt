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
    companion object {
        // [2026-06-10] Cloudflare 公式 edge IP（QUIC port 7844）。DNS lookup 不要にするための固定リスト。
        //   将来的に IP 帯が変更された場合は SettingsRepository.cloudflaredEdgeIps で上書き可能。
        const val DEFAULT_CLOUDFLARED_EDGE_IPS =
            "198.41.192.27:7844,198.41.192.47:7844,198.41.192.67:7844," +
                "198.41.200.13:7844,198.41.200.33:7844,198.41.200.43:7844"

        // [2026-06-11] サイレント死回避: 5回連続失敗後、この時間経過したら再試行する
        const val COOLDOWN_AFTER_MAX_FAILURES_MS = 30L * 60L * 1000L  // 30 分
    }

    @Volatile private var process: Process? = null
    private var logThread: Thread? = null
    private var dnsProxy: DnsProxy? = null

    // [2026-03-12] 自動再起動用の状態管理
    // [2026-06-11] @Volatile + restartGeneration: 複数スレッドからの restart スケジュール競合を防ぐ
    @Volatile private var autoRestart = true
    @Volatile private var lastToken: String = ""
    @Volatile private var lastPort: Int = 3000
    @Volatile private var lastDomain: String = ""
    @Volatile private var restartCount = 0
    private val maxRestarts = 5
    @Volatile private var restartThread: Thread? = null
    private var healthCheckThread: Thread? = null

    // [2026-06-11] restart スケジュールごとに世代番号を付与。古い thread は実行直前に
    //   「自分は最新の世代か」を確認して stale なら no-op する。複数経路の重複起動を防止。
    private val restartGeneration = java.util.concurrent.atomic.AtomicInteger(0)
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

    @Synchronized
    fun start(token: String, port: Int, domain: String = "") {
        if (isRunning) {
            onLog("Tunnel は既に稼働中です")
            return
        }

        // [2026-06-11] 既存の pending restart/cooldown thread を確実に stale 化してから手動開始
        //   sleep 復帰後の thread が startInternal を二重実行しないよう世代を進める
        //   @Synchronized で tryStartFromScheduled / scheduleRestartOrCooldown と排他
        restartGeneration.incrementAndGet()
        restartThread?.interrupt()
        restartThread = null
        restartCount = 0

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
                // [2026-06-10] ただし port 53 bind 失敗（EACCES 等）は端末でのトラブルシュート上
                //   重要なので「WRN」フィルタに関係なく必ず onLog に出す。
                dnsProxy = DnsProxy(context) { msg ->
                    val isBindFailure = msg.contains("WRN") &&
                        (msg.contains("Permission denied") || msg.contains("EACCES") ||
                            msg.contains("bind") || msg.contains("Address already in use"))
                    when {
                        isBindFailure -> onLog(msg)
                        msg.contains("WRN") -> if (SettingsRepository(context).verboseLog) onLog(msg)
                        else -> onLog(msg)
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
            // [2026-06-10] DnsProxy が port 53 bind に失敗する OS（OPPO/Android 9 等）でも
            //   cloudflared が起動できるよう、edge discovery (DNS lookup) を skip するため
            //   公式 edge IP を直接指定する。1 個でも生存していれば接続成立する冗長指定。
            //   公式 IP は Cloudflare 側で変更される可能性があるため、必要に応じて
            //   SettingsRepository.cloudflaredEdgeIps から上書き可能にしている。
            //   cloudflared CLI は --edge を「複数回」指定する仕様なので、IP ごとに 1 ペア。
            val edgeIps = SettingsRepository(context).cloudflaredEdgeIps.ifBlank {
                DEFAULT_CLOUDFLARED_EDGE_IPS
            }.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val args = mutableListOf(
                binary,
                "tunnel",
                "--no-autoupdate",
                // [2026-03-12] QUIC: UDP ベースで接続管理が軽量、HTTP/2 より同時接続耐性が高い
                "--protocol", "quic",
                "--proxy-connect-timeout", "120s",
                "--proxy-keepalive-connections", "100",
                "--proxy-keepalive-timeout", "120s",
                "--grace-period", "30s",
            )
            edgeIps.forEach { ip -> args += listOf("--edge", ip) }
            args += listOf(
                "--config", configFile.absolutePath,
                "run",
                "--token", token,
            )
            val pb = ProcessBuilder(args)
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
                    // [2026-06-11] restart スケジュールは scheduleRestartOrCooldown() に集約
                    if (autoRestart) {
                        scheduleRestartOrCooldown()
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
                    // [2026-06-10] Codex#1: 固定値 "Bearer salesnow" だと実 API Key が違う端末で
                    // health check が 401 になり tunnel を flap させる。設定値から動的に取得する。
                    val apiKey = SettingsRepository(context).apiKey
                    if (apiKey.isNotBlank()) {
                        conn.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
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

                // [2026-06-11] 上限到達でも forceRestart→scheduleRestartOrCooldown で cooldown 経路に合流
                if (consecutiveFailures >= healthCheckFailThreshold && autoRestart) {
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
    // [2026-06-11] schedule は process.waitFor() 完了側の logThread に一本化。
    //   ここでは destroy だけ行い、終了検知ループから scheduleRestartOrCooldown() が呼ばれる。
    //   こうしないと healthcheck 起点と process 終了起点で restartCount が二重カウントされる。
    private fun forceRestart() {
        process?.let { p ->
            p.destroyForcibly()
            try { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        }
        // logThread の interrupt はしない（process.waitFor() を抜けて schedule に流す）
    }

    /**
     * [2026-06-11] scheduled thread からの起動エントリ。
     *   manual start() / stop() と同一ロックを取って、check → startInternal を atomic に実行する。
     */
    @Synchronized
    private fun tryStartFromScheduled(gen: Int, isCooldown: Boolean) {
        if (!autoRestart) return
        if (restartGeneration.get() != gen) return  // 後発に上書きされた
        if (isRunning) return                        // manual start() で既に起動済み
        if (isCooldown) {
            restartCount = 0
            onLog("Tunnel cooldown 経過: 再試行を開始します")
        }
        startInternal(lastToken, lastPort, lastDomain, getBinaryPath())
    }

    /**
     * [2026-06-11] restart スケジュールの単一エントリ。
     *   - 残り試行回数があれば 3〜15 秒後の short restart を予約
     *   - 上限到達なら 30 分後の cooldown 後に restartCount=0 にして再試行
     *   - 古い scheduled thread は次回スケジュール時に interrupt + 世代番号で stale 化される
     */
    @Synchronized
    private fun scheduleRestartOrCooldown() {
        if (!autoRestart) return
        // 古い scheduled thread を停止し、世代を進める
        restartThread?.interrupt()
        val myGen = restartGeneration.incrementAndGet()

        val isCooldown = restartCount >= maxRestarts
        if (!isCooldown) restartCount++

        val delayMs = if (isCooldown) {
            val mins = COOLDOWN_AFTER_MAX_FAILURES_MS / 60_000
            onLog("Tunnel 再起動上限 (${maxRestarts}回) に達しました。${mins}分後に自動再試行します")
            COOLDOWN_AFTER_MAX_FAILURES_MS
        } else {
            val delaySec = minOf(restartCount * 3, 15)
            onLog("Tunnel 自動再起動 ($restartCount/$maxRestarts): ${delaySec}秒後...")
            delaySec * 1000L
        }

        restartThread = Thread {
            try {
                Thread.sleep(delayMs)
                // [2026-06-11] 全 check + 実行を同一ロックに入れて TOCTOU を完全に排除
                tryStartFromScheduled(myGen, isCooldown)
            } catch (_: InterruptedException) {
                // stop() / 上書きされた場合
            }
        }.apply {
            isDaemon = true
            name = if (isCooldown) "tunnel-cooldown" else "tunnel-restart"
            start()
        }
    }

    @Synchronized
    fun stop() {
        // [2026-03-12] 自動再起動を無効化してから停止
        autoRestart = false
        restartCount = 0
        // [2026-06-11] 世代を進めて、sleep 復帰後の古い scheduled thread を確実に stale 化
        //   @Synchronized で start / tryStartFromScheduled と排他
        restartGeneration.incrementAndGet()
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
