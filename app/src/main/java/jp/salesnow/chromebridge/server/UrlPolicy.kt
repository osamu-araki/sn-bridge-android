// Version: 2.1.0 | Updated: 2026-06-10
// [2026-06-10] Codex#2: /fetch の URL allow/deny policy。SSRF 対策。
// [2026-06-10] Codex 再レビュー: integer/hex/octal IPv4 表記の正規化、
//   および DNS 解決後の各 IP に対するプライベート判定を追加。
// [2026-06-10] Codex 3 回目:
//   - IPv4-mapped IPv6（非 dotted 表記: ::ffff:7f00:1 等）も dotted に展開して private 判定
//   - **DNS rebinding 完全対策はここでは行わない**（Phase 1 暫定の受入れリスク）。
//     UrlPolicy.check() と WebView.loadUrl() は別々に DNS 解決するため、
//     攻撃者が DNS で public → private を切り替えるとチェックを通過後の
//     接続先が private になる TOCTOU が成立し得る。
//     完全に閉じるには WebView 側で IP pinning か、IP 直接接続 + SNI 偽装が必要で、
//     業務影響が大きい。当面は「攻撃者が DNS を制御できる」ハードルで受け入れる。
//     Phase 2 以降で IP pinning または独自プロキシ経由に切り替えるなら再検討。
package jp.salesnow.chromebridge.server

import java.net.IDN
import java.net.InetAddress
import java.net.URI

object UrlPolicy {

    sealed class Decision {
        object Allow : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /**
     * fetch 対象 URL を検査する。
     * scheme/host の形式チェック → IPv4 代替表記の正規化 → DNS 解決後の IP もチェック。
     * **DNS 解決を伴うためブロッキング**。HTTP リクエストハンドラスレッドで呼ぶ前提。
     */
    fun check(rawUrl: String?): Decision {
        if (rawUrl.isNullOrBlank()) return Decision.Deny("url が空です")

        val uri = try {
            URI(rawUrl.trim())
        } catch (e: Exception) {
            return Decision.Deny("URL の形式が不正です: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Decision.Deny("scheme は http/https のみ許可（指定: $scheme）")
        }

        val rawHost = uri.host ?: return Decision.Deny("host が指定されていません")

        // IDN を ASCII に正規化
        val asciiHost = try {
            IDN.toASCII(rawHost).lowercase()
        } catch (_: Exception) {
            rawHost.lowercase()
        }

        // 1) hostname ベースの判定（localhost / .local / .localhost 等）
        if (isPrivateHostnamePattern(asciiHost)) {
            return Decision.Deny("loopback/プライベートホスト名は禁止: $asciiHost")
        }

        // 2) IPv4 代替表記（整数/16進/8進/dotted-decimal）も正規化して判定
        val canonicalIpv4 = canonicalizeIpv4(asciiHost)
        if (canonicalIpv4 != null && isPrivateIpv4(canonicalIpv4)) {
            return Decision.Deny("loopback/プライベート IPv4 は禁止: $rawHost → $canonicalIpv4")
        }

        // 3) IPv6 リテラル
        val bareIpv6 = if (asciiHost.startsWith("[") && asciiHost.endsWith("]")) {
            asciiHost.substring(1, asciiHost.length - 1)
        } else asciiHost
        if (bareIpv6.contains(":")) {
            if (isPrivateIpv6(bareIpv6)) {
                return Decision.Deny("loopback/プライベート IPv6 は禁止: $rawHost")
            }
        }

        // 4) DNS 解決後の各 IP が private/loopback なら拒否
        //    （nip.io / sslip.io 等で private に解決される hostname を遮断）
        val resolved = try {
            InetAddress.getAllByName(rawHost)
        } catch (_: Exception) {
            // 解決失敗は WebView 側でエラーになるので Allow（ここでブロックする必要なし）
            return Decision.Allow
        }
        for (addr in resolved) {
            val ip = addr.hostAddress ?: continue
            if (addr is java.net.Inet4Address) {
                if (isPrivateIpv4(ip)) {
                    return Decision.Deny("DNS 解決先がプライベート IPv4: $rawHost → $ip")
                }
            } else if (addr is java.net.Inet6Address) {
                if (isPrivateIpv6(ip)) {
                    return Decision.Deny("DNS 解決先がプライベート IPv6: $rawHost → $ip")
                }
            }
        }

        return Decision.Allow
    }

    private fun isPrivateHostnamePattern(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.endsWith(".local")) return true // mDNS
        if (h == "broadcasthost") return true
        return false
    }

    /**
     * IPv4 の代替表記を dotted-decimal に正規化する。
     * - "127.0.0.1"          → "127.0.0.1"
     * - "0x7f000001"          → "127.0.0.1"
     * - "0177.0.0.1"          → "127.0.0.1"
     * - "2130706433"          → "127.0.0.1"
     * - 解析不能なら null
     */
    private fun canonicalizeIpv4(host: String): String? {
        val h = host.trim()
        if (h.isEmpty()) return null

        // 1) 4 セグメントの dotted 表記（各セグメントは 10/8/16 進数を許す）
        val parts = h.split(".")
        if (parts.size in 1..4) {
            val nums = LongArray(parts.size)
            for (i in parts.indices) {
                val n = parseIntFlex(parts[i]) ?: return null
                if (n < 0) return null
                nums[i] = n
            }
            // 4 セグメント
            if (parts.size == 4) {
                if (nums.any { it > 255L }) return null
                return "${nums[0]}.${nums[1]}.${nums[2]}.${nums[3]}"
            }
            // 1 セグメント（整数）: 32bit
            if (parts.size == 1) {
                val n = nums[0]
                if (n > 0xFFFFFFFFL) return null
                return longToDotted(n)
            }
            // 2/3 セグメントは曖昧（B/C class 略記）。Java InetAddress も受け付けるが
            // SSRF 攻撃面として代表値ではないため null（不正扱い）にする。
            return null
        }
        return null
    }

    /** 10 進・"0x" 16 進・"0" 始まり 8 進を受け付ける整数パース */
    private fun parseIntFlex(text: String): Long? {
        if (text.isEmpty()) return null
        val t = text.lowercase()
        return try {
            when {
                t.startsWith("0x") -> t.substring(2).toLong(16)
                t.length > 1 && t.startsWith("0") -> t.substring(1).toLong(8)
                else -> t.toLong(10)
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun longToDotted(n: Long): String {
        val a = ((n shr 24) and 0xFF).toInt()
        val b = ((n shr 16) and 0xFF).toInt()
        val c = ((n shr 8) and 0xFF).toInt()
        val d = (n and 0xFF).toInt()
        return "$a.$b.$c.$d"
    }

    /** dotted-decimal IPv4 (正規化済み) のプライベート判定。 */
    fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (a == 10) return true
        if (a == 127) return true            // loopback
        if (a == 0) return true              // 0.0.0.0/8
        if (a == 169 && b == 254) return true // link-local
        if (a == 172 && b in 16..31) return true
        if (a == 192 && b == 168) return true
        if (a == 100 && b in 64..127) return true // CGNAT
        if (a in 224..239) return true       // multicast
        if (a >= 240) return true            // reserved / broadcast
        return false
    }

    fun isPrivateIpv6(ip: String): Boolean {
        val v6 = ip.lowercase()
        if (v6 == "::1" || v6 == "::") return true
        if (v6.startsWith("::ffff:")) {
            val mapped = v6.substringAfter("::ffff:")
            if (mapped.contains(".")) {
                // dotted 表記 "::ffff:127.0.0.1"
                return isPrivateIpv4(mapped)
            }
            // [2026-06-10] Codex 3 回目 Minor: 非 dotted 表記の IPv4-mapped IPv6
            //   "::ffff:7f00:1" → "127.0.0.1" に変換して private 判定
            val parts = mapped.split(":")
            if (parts.size == 2) {
                val hi = parts[0].toIntOrNull(16) ?: return false
                val lo = parts[1].toIntOrNull(16) ?: return false
                if (hi in 0..0xFFFF && lo in 0..0xFFFF) {
                    val dotted = "${(hi shr 8) and 0xFF}.${hi and 0xFF}.${(lo shr 8) and 0xFF}.${lo and 0xFF}"
                    return isPrivateIpv4(dotted)
                }
            }
        }
        // fe80::/10 (link-local)
        if (v6.startsWith("fe8") || v6.startsWith("fe9") ||
            v6.startsWith("fea") || v6.startsWith("feb")) return true
        // fc00::/7 (ULA)
        if (v6.startsWith("fc") || v6.startsWith("fd")) return true
        // ff00::/8 (multicast)
        if (v6.startsWith("ff")) return true
        return false
    }

    /** 後方互換: 旧シグネチャ。プライベートホスト名パターンと IPv4/IPv6 リテラルだけを判定。 */
    fun isPrivateOrLoopbackHost(host: String): Boolean {
        if (isPrivateHostnamePattern(host)) return true
        val canonical = canonicalizeIpv4(host)
        if (canonical != null) return isPrivateIpv4(canonical)
        if (host.contains(":")) return isPrivateIpv6(host)
        return false
    }
}
