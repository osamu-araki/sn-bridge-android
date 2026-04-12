// Version: 1.1.0 | Updated: 2026-04-12
// [2026-03-11] UI・API から統計データを読み取るためのリポジトリ
// [2026-04-12] チャレンジ統計 (challengeSuccess, challengeFailure) を追加
package jp.salesnow.chromebridge.data

/**
 * 日別統計データ
 */
data class DailyStatsData(
    val dateKey: String,
    val totalRequests: Long,
    val successCount: Long,
    val errorCount: Long,
    val totalBytes: Long,
    val avgDurationMs: Long,
    val maxDurationMs: Long,
    val peakConcurrency: Int,
    val errorTimeout: Int,
    val errorFetch: Int,
    val errorQueue: Int,
    val errorAuth: Int,
    val errorRate: Int,
    val errorParse: Int,
    val challengeSuccess: Int = 0,
    val challengeFailure: Int = 0
) {
    val successRate: Double
        get() = if (totalRequests > 0) successCount.toDouble() / totalRequests else 0.0
}

/**
 * 月別統計データ
 */
data class MonthlyStatsData(
    val monthKey: String,
    val totalRequests: Long,
    val successCount: Long,
    val errorCount: Long,
    val totalBytes: Long,
    val avgDurationMs: Long,
    val maxDurationMs: Long,
    val challengeSuccess: Int = 0,
    val challengeFailure: Int = 0
) {
    val successRate: Double
        get() = if (totalRequests > 0) successCount.toDouble() / totalRequests else 0.0
}

/**
 * 統計データの読み取り専用リポジトリ
 */
class StatsRepository(private val database: StatsDatabase) {

    /**
     * 日別統計を取得（降順、最新が先頭）
     */
    fun getDailyStats(limit: Int = 30): List<DailyStatsData> {
        val db = database.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM daily_stats ORDER BY date_key DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val result = mutableListOf<DailyStatsData>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(DailyStatsData(
                    dateKey = it.getString(it.getColumnIndexOrThrow("date_key")),
                    totalRequests = it.getLong(it.getColumnIndexOrThrow("total_requests")),
                    successCount = it.getLong(it.getColumnIndexOrThrow("success_count")),
                    errorCount = it.getLong(it.getColumnIndexOrThrow("error_count")),
                    totalBytes = it.getLong(it.getColumnIndexOrThrow("total_bytes")),
                    avgDurationMs = it.getLong(it.getColumnIndexOrThrow("avg_duration_ms")),
                    maxDurationMs = it.getLong(it.getColumnIndexOrThrow("max_duration_ms")),
                    peakConcurrency = it.getInt(it.getColumnIndexOrThrow("peak_concurrency")),
                    errorTimeout = it.getInt(it.getColumnIndexOrThrow("error_timeout")),
                    errorFetch = it.getInt(it.getColumnIndexOrThrow("error_fetch")),
                    errorQueue = it.getInt(it.getColumnIndexOrThrow("error_queue")),
                    errorAuth = it.getInt(it.getColumnIndexOrThrow("error_auth")),
                    errorRate = it.getInt(it.getColumnIndexOrThrow("error_rate")),
                    errorParse = it.getInt(it.getColumnIndexOrThrow("error_parse")),
                    challengeSuccess = it.getInt(it.getColumnIndexOrThrow("challenge_success")),
                    challengeFailure = it.getInt(it.getColumnIndexOrThrow("challenge_failure"))
                ))
            }
        }
        return result
    }

    /**
     * 月別統計を取得（降順、最新が先頭）
     */
    fun getMonthlyStats(limit: Int = 12): List<MonthlyStatsData> {
        val db = database.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM monthly_stats ORDER BY month_key DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        val result = mutableListOf<MonthlyStatsData>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(MonthlyStatsData(
                    monthKey = it.getString(it.getColumnIndexOrThrow("month_key")),
                    totalRequests = it.getLong(it.getColumnIndexOrThrow("total_requests")),
                    successCount = it.getLong(it.getColumnIndexOrThrow("success_count")),
                    errorCount = it.getLong(it.getColumnIndexOrThrow("error_count")),
                    totalBytes = it.getLong(it.getColumnIndexOrThrow("total_bytes")),
                    avgDurationMs = it.getLong(it.getColumnIndexOrThrow("avg_duration_ms")),
                    maxDurationMs = it.getLong(it.getColumnIndexOrThrow("max_duration_ms")),
                    challengeSuccess = it.getInt(it.getColumnIndexOrThrow("challenge_success")),
                    challengeFailure = it.getInt(it.getColumnIndexOrThrow("challenge_failure"))
                ))
            }
        }
        return result
    }

    /**
     * 今日の統計を取得（サマリーカード用）
     */
    fun getTodayStats(): DailyStatsData? {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val db = database.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM daily_stats WHERE date_key = ?",
            arrayOf(today)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return DailyStatsData(
                    dateKey = it.getString(it.getColumnIndexOrThrow("date_key")),
                    totalRequests = it.getLong(it.getColumnIndexOrThrow("total_requests")),
                    successCount = it.getLong(it.getColumnIndexOrThrow("success_count")),
                    errorCount = it.getLong(it.getColumnIndexOrThrow("error_count")),
                    totalBytes = it.getLong(it.getColumnIndexOrThrow("total_bytes")),
                    avgDurationMs = it.getLong(it.getColumnIndexOrThrow("avg_duration_ms")),
                    maxDurationMs = it.getLong(it.getColumnIndexOrThrow("max_duration_ms")),
                    peakConcurrency = it.getInt(it.getColumnIndexOrThrow("peak_concurrency")),
                    errorTimeout = it.getInt(it.getColumnIndexOrThrow("error_timeout")),
                    errorFetch = it.getInt(it.getColumnIndexOrThrow("error_fetch")),
                    errorQueue = it.getInt(it.getColumnIndexOrThrow("error_queue")),
                    errorAuth = it.getInt(it.getColumnIndexOrThrow("error_auth")),
                    errorRate = it.getInt(it.getColumnIndexOrThrow("error_rate")),
                    errorParse = it.getInt(it.getColumnIndexOrThrow("error_parse")),
                    challengeSuccess = it.getInt(it.getColumnIndexOrThrow("challenge_success")),
                    challengeFailure = it.getInt(it.getColumnIndexOrThrow("challenge_failure"))
                )
            }
        }
        return null
    }

    /**
     * 今月の統計を取得（サマリーカード用）
     */
    fun getCurrentMonthStats(): MonthlyStatsData? {
        val thisMonth = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
            .format(java.util.Date())
        val db = database.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM monthly_stats WHERE month_key = ?",
            arrayOf(thisMonth)
        )
        cursor.use {
            if (it.moveToFirst()) {
                return MonthlyStatsData(
                    monthKey = it.getString(it.getColumnIndexOrThrow("month_key")),
                    totalRequests = it.getLong(it.getColumnIndexOrThrow("total_requests")),
                    successCount = it.getLong(it.getColumnIndexOrThrow("success_count")),
                    errorCount = it.getLong(it.getColumnIndexOrThrow("error_count")),
                    totalBytes = it.getLong(it.getColumnIndexOrThrow("total_bytes")),
                    avgDurationMs = it.getLong(it.getColumnIndexOrThrow("avg_duration_ms")),
                    maxDurationMs = it.getLong(it.getColumnIndexOrThrow("max_duration_ms")),
                    challengeSuccess = it.getInt(it.getColumnIndexOrThrow("challenge_success")),
                    challengeFailure = it.getInt(it.getColumnIndexOrThrow("challenge_failure"))
                )
            }
        }
        return null
    }
}
