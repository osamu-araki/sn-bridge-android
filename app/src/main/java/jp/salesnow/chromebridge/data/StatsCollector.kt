// Version: 1.0.0 | Updated: 2026-03-11
// [2026-03-11] リクエストメトリクスの収集・バッファリング・SQLite バッチ書き込み
package jp.salesnow.chromebridge.data

import android.content.ContentValues
import android.os.Handler
import android.os.HandlerThread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1リクエストの記録データ
 */
data class RequestMetrics(
    val url: String,
    val success: Boolean,
    val errorCode: String? = null,
    val durationMs: Long,
    val responseBytes: Long,
    val workerId: Int = 0
)

/**
 * メモリバッファにメトリクスを蓄積し、定期的に SQLite へバッチ書き込みする。
 * リクエスト処理のオーバーヘッドを最小化（+5ms 以内）するため、
 * record() はキューに追加するだけで即座に返る。
 */
class StatsCollector(private val database: StatsDatabase) {

    private val buffer = ConcurrentLinkedQueue<RequestMetrics>()
    // flush() は HandlerThread（単一スレッド）でのみ呼ばれるためスレッド安全
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
    private val currentConcurrency = AtomicInteger(0)
    private val peakConcurrency = AtomicInteger(0)

    // [2026-03-11] バックグラウンドスレッドで DB 書き込みを実行
    private val handlerThread = HandlerThread("stats-writer").apply { start() }
    private val handler = Handler(handlerThread.looper)

    companion object {
        const val FLUSH_INTERVAL_MS = 5000L
        const val FLUSH_THRESHOLD = 50
    }

    init {
        // [2026-03-11] 5秒ごとにフラッシュをスケジュール
        scheduleFlush()
    }

    /**
     * リクエスト処理開始時に呼ぶ。並列実行数を追跡する。
     */
    fun onProcessingStart() {
        val current = currentConcurrency.incrementAndGet()
        peakConcurrency.updateAndGet { peak -> maxOf(peak, current) }
    }

    /**
     * リクエスト処理終了時に呼ぶ。
     */
    fun onProcessingEnd() {
        currentConcurrency.decrementAndGet()
    }

    /**
     * メトリクスを記録する。キューに追加するだけなので高速に返る。
     */
    fun record(metrics: RequestMetrics) {
        buffer.add(metrics)
        // [2026-03-11] バッファが閾値を超えた場合は即座にフラッシュ
        if (buffer.size >= FLUSH_THRESHOLD) {
            handler.post { flush() }
        }
    }

    /**
     * バッファの内容を SQLite に書き込む
     */
    private fun flush() {
        val items = mutableListOf<RequestMetrics>()
        while (true) {
            val item = buffer.poll() ?: break
            items.add(item)
        }
        if (items.isEmpty()) return

        val db = database.writableDatabase
        val now = System.currentTimeMillis()

        db.beginTransaction()
        try {
            for (m in items) {
                val dateKey = dateFormat.format(Date(now))
                val monthKey = monthFormat.format(Date(now))

                // [2026-03-11] request_log に INSERT
                val logValues = ContentValues().apply {
                    put("timestamp", now)
                    put("date_key", dateKey)
                    put("month_key", monthKey)
                    put("url", m.url)
                    put("success", if (m.success) 1 else 0)
                    put("error_code", m.errorCode)
                    put("duration_ms", m.durationMs)
                    put("response_bytes", m.responseBytes)
                    put("worker_id", m.workerId)
                }
                db.insert("request_log", null, logValues)

                // [2026-03-11] daily_stats をインクリメンタル更新
                upsertDailyStats(db, dateKey, m)

                // [2026-03-11] monthly_stats をインクリメンタル更新
                upsertMonthlyStats(db, monthKey, m)
            }

            // [2026-03-11] ピーク並列数を daily_stats に反映
            val todayKey = dateFormat.format(Date(now))
            val peak = peakConcurrency.get()
            db.execSQL(
                "UPDATE daily_stats SET peak_concurrency = MAX(peak_concurrency, ?) WHERE date_key = ?",
                arrayOf(peak, todayKey)
            )

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertDailyStats(db: android.database.sqlite.SQLiteDatabase, dateKey: String, m: RequestMetrics) {
        val errorColumn = when (m.errorCode) {
            "timeout" -> "error_timeout"
            "fetch_failed" -> "error_fetch"
            "queue_full" -> "error_queue"
            "unauthorized", "forbidden" -> "error_auth"
            "rate_limit" -> "error_rate"
            "parse_failed", "extract_failed" -> "error_parse"
            else -> null
        }

        // [2026-03-11] UPSERT: 存在しなければ INSERT、存在すれば UPDATE
        db.execSQL("""
            INSERT INTO daily_stats (date_key, total_requests, success_count, error_count, total_bytes, total_duration, max_duration_ms)
            VALUES (?, 1, ?, ?, ?, ?, ?)
            ON CONFLICT(date_key) DO UPDATE SET
                total_requests = total_requests + 1,
                success_count = success_count + ?,
                error_count = error_count + ?,
                total_bytes = total_bytes + ?,
                total_duration = total_duration + ?,
                max_duration_ms = MAX(max_duration_ms, ?)
        """.trimIndent(), arrayOf<Any>(
            dateKey,
            if (m.success) 1 else 0,
            if (m.success) 0 else 1,
            m.responseBytes,
            m.durationMs,
            m.durationMs,
            // UPDATE 部分
            if (m.success) 1 else 0,
            if (m.success) 0 else 1,
            m.responseBytes,
            m.durationMs,
            m.durationMs
        ))

        // [2026-03-11] avg_duration_ms を再計算
        db.execSQL(
            "UPDATE daily_stats SET avg_duration_ms = total_duration / MAX(total_requests, 1) WHERE date_key = ?",
            arrayOf(dateKey)
        )

        // [2026-03-11] エラー種別カウントをインクリメント
        if (errorColumn != null) {
            db.execSQL(
                "UPDATE daily_stats SET $errorColumn = $errorColumn + 1 WHERE date_key = ?",
                arrayOf(dateKey)
            )
        }
    }

    private fun upsertMonthlyStats(db: android.database.sqlite.SQLiteDatabase, monthKey: String, m: RequestMetrics) {
        db.execSQL("""
            INSERT INTO monthly_stats (month_key, total_requests, success_count, error_count, total_bytes, total_duration, max_duration_ms)
            VALUES (?, 1, ?, ?, ?, ?, ?)
            ON CONFLICT(month_key) DO UPDATE SET
                total_requests = total_requests + 1,
                success_count = success_count + ?,
                error_count = error_count + ?,
                total_bytes = total_bytes + ?,
                total_duration = total_duration + ?,
                max_duration_ms = MAX(max_duration_ms, ?)
        """.trimIndent(), arrayOf<Any>(
            monthKey,
            if (m.success) 1 else 0,
            if (m.success) 0 else 1,
            m.responseBytes,
            m.durationMs,
            m.durationMs,
            // UPDATE 部分
            if (m.success) 1 else 0,
            if (m.success) 0 else 1,
            m.responseBytes,
            m.durationMs,
            m.durationMs
        ))

        db.execSQL(
            "UPDATE monthly_stats SET avg_duration_ms = total_duration / MAX(total_requests, 1) WHERE month_key = ?",
            arrayOf(monthKey)
        )
    }

    private fun scheduleFlush() {
        handler.postDelayed({
            flush()
            scheduleFlush()
        }, FLUSH_INTERVAL_MS)
    }

    /**
     * 即座にバッファをフラッシュする（サーバー停止時に呼ぶ）
     */
    fun flushNow() {
        handler.post { flush() }
    }

    /**
     * リソース解放
     */
    fun destroy() {
        flushNow()
        handlerThread.quitSafely()
    }
}
