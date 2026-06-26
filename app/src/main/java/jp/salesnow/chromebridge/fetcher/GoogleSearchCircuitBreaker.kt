// Version: 1.1.0 | Updated: 2026-06-26
// [2026-06-26] 閾値・window・停止時間を SettingsRepository から動的に読むよう変更
// [2026-06-26] Google が IP/UA/フィンガープリント単位でクライアントを 403 永続ブロックする
//   ケースに対するサーキットブレーカー。同じ host への失敗を window 内で閾値超え検知したら
//   一定時間 fetch を即拒否し、Google 側の自動 unblock を待ちつつ Bridge から無駄に叩かない。
package jp.salesnow.chromebridge.fetcher

import android.content.Context
import jp.salesnow.chromebridge.data.SettingsRepository
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Google 検索向け簡易サーキットブレーカー（host 単位）。
 *
 * Closed: 通常運用。`recordFailure(context, host)` を呼ぶたびに `recentFailures[host]` に
 *   タイムスタンプを積む。window 外の古い記録は呼び出し時に掃除する。
 * Tripped: 同一 window 内で `FAILURE_THRESHOLD` 件溜まったら `tripUntil[host]` を
 *   未来時刻に設定。以降 `isOpen(host)` が true を返し、fetch 層が即拒否する。
 * Reset: `tripUntil[host]` を過ぎたら自動 close（次の `isOpen()` 評価時に掃除）。
 *
 * 同期化はメソッド単位で synchronized(lock)。ホットパスではないので雑な mutex で十分。
 *
 * [2026-06-26] パラメータは SettingsRepository から動的に読む（運用に応じて UI から変更可）。
 */
object GoogleSearchCircuitBreaker {

    /** 失敗とみなす連続検知件数のデフォルト（設定未保存時の fallback） */
    const val DEFAULT_FAILURE_THRESHOLD = 3

    /** 失敗カウントの集計 window のデフォルト（分） */
    const val DEFAULT_WINDOW_MINUTES = 5

    /** Trip 後に拒否を続ける時間のデフォルト（分） */
    const val DEFAULT_TRIP_MINUTES = 60

    private val lock = Any()
    private val recentFailures = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val tripUntil = ConcurrentHashMap<String, Long>()

    /** 現在 trip しているか。time-out 後の自動 close もここで処理。 */
    fun isOpen(host: String): Boolean {
        val key = host.lowercase()
        val now = System.currentTimeMillis()
        val until = tripUntil[key] ?: return false
        if (now >= until) {
            // 時間経過 → close + 失敗履歴も掃除
            synchronized(lock) {
                if ((tripUntil[key] ?: 0L) <= now) {
                    tripUntil.remove(key)
                    recentFailures.remove(key)
                }
            }
            return false
        }
        return true
    }

    /**
     * 失敗を記録。window 内で閾値に達したら trip する。
     * 既に trip 中なら追加記録は不要（早期 return）。
     *
     * [2026-06-26] 閾値 / window / 停止時間は SettingsRepository から動的に読む。
     * 取得失敗時はデフォルト値を使う（fail-safe）。
     */
    fun recordFailure(context: Context, host: String) {
        val key = host.lowercase()
        val now = System.currentTimeMillis()
        val threshold: Int
        val windowMs: Long
        val tripMs: Long
        try {
            val repo = SettingsRepository(context)
            threshold = repo.circuitFailureThreshold
            windowMs = repo.circuitWindowMinutes * 60_000L
            tripMs = repo.circuitTripMinutes * 60_000L
        } catch (_: Exception) {
            // fail-safe: 設定読めなければデフォルト
            return recordFailureWith(
                key, now,
                DEFAULT_FAILURE_THRESHOLD,
                DEFAULT_WINDOW_MINUTES * 60_000L,
                DEFAULT_TRIP_MINUTES * 60_000L,
            )
        }
        recordFailureWith(key, now, threshold, windowMs, tripMs)
    }

    private fun recordFailureWith(
        key: String,
        now: Long,
        threshold: Int,
        windowMs: Long,
        tripMs: Long,
    ) {
        synchronized(lock) {
            val existingTrip = tripUntil[key]
            if (existingTrip != null && now < existingTrip) return
            val deque = recentFailures.computeIfAbsent(key) { ArrayDeque() }
            // window 外を掃除
            while (deque.isNotEmpty() && deque.first() < now - windowMs) {
                deque.removeFirst()
            }
            deque.addLast(now)
            if (deque.size >= threshold) {
                tripUntil[key] = now + tripMs
                deque.clear()
            }
        }
    }

    /** 残り trip 時間 (ms)。trip していなければ 0。 */
    fun remainingTripMs(host: String): Long {
        val key = host.lowercase()
        val until = tripUntil[key] ?: return 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 現在の状態を読み取り専用で返す（UI / デバッグ用）。 */
    fun snapshot(): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val now = System.currentTimeMillis()
        for ((k, v) in tripUntil) {
            val remaining = v - now
            if (remaining > 0) out[k] = remaining
        }
        return out
    }

    /** 手動リセット（運用 UI から呼ぶ想定）。 */
    fun reset(host: String? = null) {
        synchronized(lock) {
            if (host == null) {
                tripUntil.clear()
                recentFailures.clear()
            } else {
                val key = host.lowercase()
                tripUntil.remove(key)
                recentFailures.remove(key)
            }
        }
    }
}
