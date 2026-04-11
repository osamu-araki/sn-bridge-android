// Version: 1.0.0 | Updated: 2026-03-08
// [2026-03-08] Go バイナリ (cloudflared) 用の DNS プロキシ
// Android には /etc/resolv.conf が無いため Go の DNS リゾルバが [::1]:53 にフォールバックする。
// このプロキシが [::1]:53 / 127.0.0.1:53 でリッスンし、実 DNS サーバーに転送する。
package jp.salesnow.chromebridge.tunnel

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * UDP DNS プロキシ。ローカルの port 53 でリッスンし、
 * Android の実 DNS サーバーへクエリを転送する。
 */
class DnsProxy(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    private var socket4: DatagramSocket? = null
    private var socket6: DatagramSocket? = null
    private var thread4: Thread? = null
    private var thread6: Thread? = null
    @Volatile
    private var running = false

    private fun getUpstreamDns(): InetAddress {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val linkProps = if (network != null) cm.getLinkProperties(network) else null
            val dns = linkProps?.dnsServers?.firstOrNull()
            if (dns != null) return dns
        } catch (_: Exception) {}
        return InetAddress.getByName("8.8.8.8")
    }

    fun start() {
        if (running) return
        running = true

        // IPv4 127.0.0.1:53
        thread4 = Thread {
            try {
                socket4 = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 53))
                }
                onLog("DNS プロキシ起動: 127.0.0.1:53")
                proxyLoop(socket4!!)
            } catch (e: Exception) {
                onLog("DNS プロキシ (IPv4) WRN: ${e.message}")
            }
        }.apply {
            isDaemon = true
            name = "dns-proxy-v4"
            start()
        }

        // IPv6 [::1]:53
        thread6 = Thread {
            try {
                socket6 = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("::1"), 53))
                }
                onLog("DNS プロキシ起動: [::1]:53")
                proxyLoop(socket6!!)
            } catch (e: Exception) {
                onLog("DNS プロキシ (IPv6) WRN: ${e.message}")
            }
        }.apply {
            isDaemon = true
            name = "dns-proxy-v6"
            start()
        }
    }

    private fun proxyLoop(listenSocket: DatagramSocket) {
        val buf = ByteArray(4096)
        while (running && !listenSocket.isClosed) {
            try {
                val request = DatagramPacket(buf, buf.size)
                listenSocket.receive(request)

                val upstream = getUpstreamDns()
                val upstreamSocket = DatagramSocket()
                upstreamSocket.soTimeout = 5000

                val query = DatagramPacket(
                    request.data, request.offset, request.length,
                    upstream, 53
                )
                upstreamSocket.send(query)

                val responseBuf = ByteArray(4096)
                val response = DatagramPacket(responseBuf, responseBuf.size)
                upstreamSocket.receive(response)
                upstreamSocket.close()

                val reply = DatagramPacket(
                    response.data, response.offset, response.length,
                    request.address, request.port
                )
                listenSocket.send(reply)
            } catch (_: Exception) {
                // タイムアウトや一時エラーは無視して続行
            }
        }
    }

    fun stop() {
        running = false
        socket4?.close()
        socket6?.close()
        thread4?.interrupt()
        thread6?.interrupt()
        socket4 = null
        socket6 = null
        thread4 = null
        thread6 = null
        onLog("DNS プロキシ停止")
    }
}
