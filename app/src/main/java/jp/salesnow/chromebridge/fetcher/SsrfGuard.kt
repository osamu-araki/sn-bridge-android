// Version: 1.2.0 | Updated: 2026-07-16
// [2026-07-16] レンダリング中の全リクエストに SSRF ガードを適用（サブリクエスト遮断）。
//   UrlPolicy.check() を host 単位で短 TTL キャッシュするラッパー。
// [2026-07-16] Codex 事後レビュー反映:
//   - 許可/拒否で TTL を非対称化（許可を短くし DNS rebinding 窓を縮める）
//   - network-path reference（//host/... scheme 無しで host あり）を検査対象に含める
//   - UI thread 用の非ブロッキング判定 peekBlocked() を追加（shouldOverrideUrlLoading の ANR 回避）
// [2026-07-16] 実機テストで www.google.com のサブリソースが間欠的に blocked(ssrf) になる
//   false-positive を検出。真の private 解決（キャリア DNS 起因）か分類バグかを判別するため、
//   遮断理由（UrlPolicy.Decision.Deny.reason・実 IP を含む）を Result で伝播しログに出せるようにした。
//   遮断ブール値の判定ロジックは不変（理由文字列を添えるだけ）。
package jp.salesnow.chromebridge.fetcher

import jp.salesnow.chromebridge.server.UrlPolicy
import java.net.IDN
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * レンダリング中の各リクエスト URL を SSRF 観点で検査する。
 * UrlPolicy.check() は DNS 解決を伴い重いため、host 単位で短 TTL キャッシュする。
 * shouldInterceptRequest（worker thread・1ページで数十〜数百回）や
 * Cronet の onRedirectReceived（リダイレクト先検査）から evaluate()/isBlocked() を呼ぶ。
 * UI thread（shouldOverrideUrlLoading）からは DNS を伴わない peekEvaluate()/peekBlocked() を呼ぶ。
 * WebView API は一切触らない（URL 解析 + DNS のみ）ので worker thread から安全に呼べる。
 */
object SsrfGuard {
    private const val ALLOW_TTL_MS = 30 * 1000L        // 許可は短め（DNS rebinding の窓を縮める）
    private const val DENY_TTL_MS = 5 * 60 * 1000L     // 拒否は長め（判定が変わりにくい・再判定も安価）
    private const val MAX_ENTRIES = 4096               // キャッシュの無制限増加を防ぐ上限

    /** 判定結果。blocked=true なら reason に遮断理由（診断用・実 IP を含む場合あり）。 */
    data class Result(val blocked: Boolean, val reason: String?)

    private data class Entry(val blocked: Boolean, val reason: String?, val expiresAt: Long)
    // key=host（IDN ASCII・lowercase・末尾ドット除去）
    private val cache = ConcurrentHashMap<String, Entry>()

    // scheme 判定の三値。
    private sealed class Cls {
        data class Block(val reason: String) : Cls()  // scheme だけで遮断確定（DNS 不要）
        object Allow : Cls()                           // 非ネットワーク/相対で安全
        data class Check(val host: String) : Cls()    // http(s)/network-path → host を DNS 検査
    }

    private fun classify(rawUrl: String?): Cls {
        if (rawUrl.isNullOrBlank()) return Cls.Allow
        val uri = try { URI(rawUrl) } catch (_: Exception) { return Cls.Block("URL 解析失敗") } // 壊れた URL は遮断
        when (uri.scheme?.lowercase()) {
            "http", "https" -> { /* 下で host 検査 */ }
            // インライン・非ネットワークは安全（レンダリングに必須なので通す）
            "data", "blob", "about", "javascript" -> return Cls.Allow
            // ローカルファイル読取・その他不明スキームは遮断
            "file", "content", "ftp", "ws", "wss" -> return Cls.Block("非http(s)スキーム遮断: ${uri.scheme}")
            null, "" -> {
                // 純粋な相対参照/インライン（host 無し）は許可。
                // network-path reference（//host/... で host あり）は host を検査させる
                //   （UrlPolicy は非 http scheme を Deny するため実質遮断側に倒れる）。
                if (uri.host == null) return Cls.Allow
            }
            else -> return Cls.Block("未知スキーム遮断: ${uri.scheme}")
        }
        val rawHost = uri.host ?: return Cls.Block("host 無し")
        // UrlPolicy.check() 内の IDN 正規化とキャッシュキーを揃える（重複エントリ防止）。
        val host = (try {
            IDN.toASCII(rawHost).lowercase()
        } catch (_: Exception) {
            rawHost.lowercase()
        }).trimEnd('.')
        if (host.isEmpty()) return Cls.Block("host 空")
        return Cls.Check(host)
    }

    /** DNS 解決を伴う権威判定（worker thread から呼ぶ）。遮断理由付き。 */
    fun evaluate(rawUrl: String?, nowMs: Long): Result {
        return when (val c = classify(rawUrl)) {
            is Cls.Block -> Result(true, c.reason)
            is Cls.Allow -> Result(false, null)
            is Cls.Check -> {
                val cached = cache[c.host]
                if (cached != null && cached.expiresAt > nowMs) return Result(cached.blocked, cached.reason)
                val decision = UrlPolicy.check(rawUrl)
                val blocked = decision is UrlPolicy.Decision.Deny
                val reason = (decision as? UrlPolicy.Decision.Deny)?.reason
                if (cache.size >= MAX_ENTRIES) evict(nowMs)
                cache[c.host] = Entry(blocked, reason, nowMs + if (blocked) DENY_TTL_MS else ALLOW_TTL_MS)
                Result(blocked, reason)
            }
        }
    }

    /** true=遮断すべき / false=許可。DNS 解決を伴う権威判定（worker thread から呼ぶ）。 */
    fun isBlocked(rawUrl: String?, nowMs: Long): Boolean = evaluate(rawUrl, nowMs).blocked

    /**
     * DNS を伴わない非ブロッキング判定。UI thread（shouldOverrideUrlLoading）用。
     * scheme だけで遮断できるもの（file: 等）は遮断、http(s) host は「キャッシュ済みの拒否」のみ遮断、
     * 未判定は許可（＝権威判定は worker thread の shouldInterceptRequest に委ねる）。
     */
    fun peekEvaluate(rawUrl: String?, nowMs: Long): Result {
        return when (val c = classify(rawUrl)) {
            is Cls.Block -> Result(true, c.reason)
            is Cls.Allow -> Result(false, null)
            is Cls.Check -> {
                val cached = cache[c.host]
                if (cached != null && cached.expiresAt > nowMs) Result(cached.blocked, cached.reason)
                else Result(false, null)
            }
        }
    }

    fun peekBlocked(rawUrl: String?, nowMs: Long): Boolean = peekEvaluate(rawUrl, nowMs).blocked

    /** 期限切れを掃除。それでも上限なら全消し（安全側: 次回そのまま再判定されるだけ）。 */
    private fun evict(nowMs: Long) {
        cache.entries.removeAll { it.value.expiresAt <= nowMs }
        if (cache.size >= MAX_ENTRIES) cache.clear()
    }
}
