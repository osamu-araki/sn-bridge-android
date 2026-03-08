// Version: 1.6.0 | Updated: 2026-03-08
// [2026-03-08] cloudflared バイナリを ProcessBuilder で起動し Cloudflare Tunnel を管理
// [2026-03-08] nativeLibraryDir の libcloudflared.so を直接実行（SELinux execute 許可済み領域）
// [2026-03-08] Go DNS リゾルバ対策: /etc/resolv.conf が無い環境用に resolv.conf を生成
// [2026-03-08] --config で ingress ルール付き config.yml を動的生成して起動
package jp.salesnow.chromebridge.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.io.File

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
                onLog("DNS: /etc/resolv.conf が存在します")
                return
            }
            // bind mount を試みる（エミュレータ等 root 環境向け）
            val rt = Runtime.getRuntime()
            rt.exec(arrayOf("sh", "-c", "mkdir -p /data/local/tmp/etc_overlay")).waitFor()
            rt.exec(arrayOf("sh", "-c", "cp -a /etc/* /data/local/tmp/etc_overlay/ 2>/dev/null")).waitFor()
            rt.exec(arrayOf("sh", "-c", "cp ${resolvConf.absolutePath} /data/local/tmp/etc_overlay/resolv.conf")).waitFor()
            rt.exec(arrayOf("sh", "-c", "mount --bind /data/local/tmp/etc_overlay /etc")).waitFor()
            if (File("/etc/resolv.conf").exists()) {
                onLog("DNS: /etc/resolv.conf を配置しました")
            } else {
                onLog("DNS: /etc/resolv.conf の配置に失敗（実デバイスでは通常問題なし）")
            }
        } catch (e: Exception) {
            onLog("DNS セットアップ: ${e.message}")
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
    fun start(token: String, port: Int, domain: String = "") {
        if (isRunning) {
            onLog("Tunnel は既に稼働中です")
            return
        }

        if (token.isBlank()) {
            onLog("Tunnel トークンが未設定です")
            return
        }

        val binary = getBinaryPath()
        if (!File(binary).exists()) {
            onLog("cloudflared バイナリが見つかりません: $binary")
            return
        }

        try {
            // [2026-03-08] Go DNS 対策: /etc/resolv.conf が無い場合に備え、
            // ローカル DNS プロキシを起動して [::1]:53 / 127.0.0.1:53 で応答する
            if (!File("/etc/resolv.conf").exists()) {
                onLog("DNS: /etc/resolv.conf が存在しないため DNS プロキシを起動します")
                dnsProxy = DnsProxy(context) { msg -> onLog(msg) }
                dnsProxy?.start()
                // プロキシがバインドするまで少し待機
                Thread.sleep(500)
            } else {
                onLog("DNS: /etc/resolv.conf が存在します")
            }

            // [2026-03-08] ingress ルール付き config.yml を生成
            val configFile = generateConfig(domain, port)
            onLog("Config: ${configFile.readText().trim()}")

            // [2026-03-08] --protocol http2 で QUIC/ICMP proxy を回避（SELinux netlink_route_socket 制限対策）
            // [2026-03-08] --config で ingress ルールを指定（token のみだと ingress が空で 503 になる）
            val pb = ProcessBuilder(
                binary,
                "tunnel",
                "--no-autoupdate",
                "--protocol", "http2",
                "--config", configFile.absolutePath,
                "run",
                "--token", token
            )
            pb.redirectErrorStream(true)
            pb.environment()["HOME"] = context.filesDir.absolutePath

            process = pb.start()
            onLog("Tunnel 起動中...")

            // [2026-03-08] ログ読み取りスレッド + プロセス終了検知
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
                    if (exitCode != 0) {
                        onLog("Tunnel が異常終了しました。ログを確認してください。")
                    }
                } catch (_: Exception) {}
            }.apply {
                isDaemon = true
                name = "tunnel-log"
                start()
            }
        } catch (e: Exception) {
            onLog("Tunnel 起動エラー: ${e.message}")
        }
    }

    fun stop() {
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

    // [2026-03-08] 実機デバッグ用に全ログを出力するモードを追加
    private fun parseTunnelLog(line: String) {
        when {
            line.contains("Registered tunnel connection") ->
                onLog("Tunnel 接続確立")
            line.contains("Initial protocol") ->
                onLog("Tunnel プロトコル確立")
            line.contains("Starting tunnel") ->
                onLog("Tunnel 開始")
            line.contains("ERR") || line.contains("error") || line.contains("fatal") ->
                onLog("Tunnel: $line")
            line.contains("Retrying") ->
                onLog("Tunnel 再接続中...")
            line.contains("WRN") ->
                onLog("Tunnel: $line")
            line.contains("signal") || line.contains("panic") || line.contains("SIGILL") ->
                onLog("Tunnel CRASH: $line")
            else ->
                // 実機デバッグ中は全ログを出力
                onLog("cf: $line")
        }
    }
}
