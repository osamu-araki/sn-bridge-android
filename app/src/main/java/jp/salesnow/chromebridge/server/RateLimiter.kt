// Version: 1.0.0 | Updated: 2026-03-08
// [2026-03-08] server/index.js L21-27 のレート制限を Kotlin で再実装
package jp.salesnow.chromebridge.server

/**
 * 固定ウィンドウ方式のレート制限
 * windowMs ミリ秒あたり maxRequests 件まで許可
 */
class RateLimiter(
    private val windowMs: Long = 60_000L,
    private val maxRequests: Int = 20
) {
    private val ipCounts = mutableMapOf<String, MutableList<Long>>()

    @Synchronized
    fun isAllowed(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = ipCounts.getOrPut(ip) { mutableListOf() }

        // ウィンドウ外のタイムスタンプを除去
        timestamps.removeAll { now - it > windowMs }

        if (timestamps.size >= maxRequests) return false

        timestamps.add(now)
        return true
    }
}
