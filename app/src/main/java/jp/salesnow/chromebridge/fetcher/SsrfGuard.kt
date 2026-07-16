// Version: 1.1.0 | Updated: 2026-07-16
// [2026-07-16] レンダリング中の全リクエストに SSRF ガードを適用（サブリクエスト遮断）。
//   UrlPolicy.check() を host 単位で短 TTL キャッシュするラッパー。
// [2026-07-16] Codex 事後レビュー反映:
//   - 許可/拒否で TTL を非対称化（許可を短くし DNS rebinding 窓を縮める）
//   - network-path reference（//host/... scheme 無しで host あり）を検査対象に含める
//   - UI thread 用の非ブロッキング判定 peekBlocked() を追加（shouldOverrideUrlLoading の ANR 回避）
package jp.salesnow.chromebridge.fetcher

import jp.salesnow.chromebridge.server.UrlPolicy
import java.net.IDN
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * レンダリング中の各リクエスト URL を SSRF 観点で検査する。
 * UrlPolicy.check() は DNS 解決を伴い重いため、host 単位で短 TTL キャッシュする。
 * shouldInterceptRequest（worker thread・1ページで数十〜数百回）や
 * Cronet の onRedirectReceived（リダイレクト先検査）から isBlocked() を呼ぶ。
 * UI thread（shouldOverrideUrlLoading）からは DNS を伴わない peekBlocked() を呼ぶ。
 * WebView API は一切触らない（URL 解析 + DNS のみ）ので worker thread から安全に呼べる。
 */
object SsrfGuard {
    private const val ALLOW_TTL_MS = 30 * 1000L        // 許可は短め（DNS rebinding の窓を縮める）
    private const val DENY_TTL_MS = 5 * 60 * 1000L     // 拒否は長め（判定が変わりにくい・再判定も安価）
    private const val MAX_ENTRIES = 4096               // キャッシュの無制限増加を防ぐ上限
    // key=host（IDN ASCII・lowercase・末尾ドット除去）, value=(blocked, expiresAt)
    private val cache = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    // scheme 判定の三値。
    private sealed class Cls {
        object Block : Cls()                          // scheme だけで遮断確定（DNS 不要）
        object Allow : Cls()                          // 非ネットワーク/相対で安全
        data class Check(val host: String) : Cls()   // http(s)/network-path → host を DNS 検査
    }

    private fun classify(rawUrl: String?): Cls {
        if (rawUrl.isNullOrBlank()) return Cls.Allow
        val uri = try { URI(rawUrl) } catch (_: Exception) { return Cls.Block } // 壊れた URL は遮断
        when (uri.scheme?.lowercase()) {
            "http", "https" -> { /* 下で host 検査 */ }
            // インライン・非ネットワークは安全（レンダリングに必須なので通す）
            "data", "blob", "about", "javascript" -> return Cls.Allow
            // ローカルファイル読取・その他不明スキームは遮断
            "file", "content", "ftp", "ws", "wss" -> return Cls.Block
            null, "" -> {
                // 純粋な相対参照/インライン（host 無し）は許可。
                // network-path reference（//host/... で host あり）は host を検査させる
                //   （UrlPolicy は非 http scheme を Deny するため実質遮断側に倒れる）。
                if (uri.host == null) return Cls.Allow
            }
            else -> return Cls.Block
        }
        val rawHost = uri.host ?: return Cls.Block
        // UrlPolicy.check() 内の IDN 正規化とキャッシュキーを揃える（重複エントリ防止）。
        val host = (try {
            IDN.toASCII(rawHost).lowercase()
        } catch (_: Exception) {
            rawHost.lowercase()
        }).trimEnd('.')
        if (host.isEmpty()) return Cls.Block
        return Cls.Check(host)
    }

    /** true=遮断すべき / false=許可。DNS 解決を伴う権威判定（worker thread から呼ぶ）。 */
    fun isBlocked(rawUrl: String?, nowMs: Long): Boolean {
        return when (val c = classify(rawUrl)) {
            is Cls.Block -> true
            is Cls.Allow -> false
            is Cls.Check -> {
                val cached = cache[c.host]
                if (cached != null && cached.second > nowMs) return cached.first
                val blocked = UrlPolicy.check(rawUrl) is UrlPolicy.Decision.Deny
                if (cache.size >= MAX_ENTRIES) evict(nowMs)
                cache[c.host] = blocked to (nowMs + if (blocked) DENY_TTL_MS else ALLOW_TTL_MS)
                blocked
            }
        }
    }

    /**
     * DNS を伴わない非ブロッキング判定。UI thread（shouldOverrideUrlLoading）用。
     * scheme だけで遮断できるもの（file: 等）は true、http(s) host は「キャッシュ済みの拒否」のみ true、
     * 未判定は false（＝許可し、権威判定は worker thread の shouldInterceptRequest に委ねる）。
     */
    fun peekBlocked(rawUrl: String?, nowMs: Long): Boolean {
        return when (val c = classify(rawUrl)) {
            is Cls.Block -> true
            is Cls.Allow -> false
            is Cls.Check -> {
                val cached = cache[c.host]
                if (cached != null && cached.second > nowMs) cached.first else false
            }
        }
    }

    /** 期限切れを掃除。それでも上限なら全消し（安全側: 次回そのまま再判定されるだけ）。 */
    private fun evict(nowMs: Long) {
        cache.entries.removeAll { it.value.second <= nowMs }
        if (cache.size >= MAX_ENTRIES) cache.clear()
    }
}
