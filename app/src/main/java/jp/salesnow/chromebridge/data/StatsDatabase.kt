// Version: 1.1.0 | Updated: 2026-04-12
// [2026-03-11] 統計データ永続化用の SQLite データベース
// [2026-04-12] DB v2: チャレンジ統計カラム (challenge_success, challenge_failure) を追加
package jp.salesnow.chromebridge.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class StatsDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "chrome_bridge_stats.db"
        const val DATABASE_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        // [2026-03-11] 個別リクエスト記録
        db.execSQL("""
            CREATE TABLE request_log (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp      INTEGER NOT NULL,
                date_key       TEXT    NOT NULL,
                month_key      TEXT    NOT NULL,
                url            TEXT    NOT NULL,
                success        INTEGER NOT NULL,
                error_code     TEXT,
                duration_ms    INTEGER NOT NULL,
                response_bytes INTEGER NOT NULL,
                worker_id      INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_request_log_date ON request_log(date_key)")
        db.execSQL("CREATE INDEX idx_request_log_month ON request_log(month_key)")

        // [2026-03-11] 日別集約テーブル
        db.execSQL("""
            CREATE TABLE daily_stats (
                date_key         TEXT    PRIMARY KEY,
                total_requests   INTEGER NOT NULL DEFAULT 0,
                success_count    INTEGER NOT NULL DEFAULT 0,
                error_count      INTEGER NOT NULL DEFAULT 0,
                total_bytes      INTEGER NOT NULL DEFAULT 0,
                total_duration   INTEGER NOT NULL DEFAULT 0,
                avg_duration_ms  INTEGER NOT NULL DEFAULT 0,
                max_duration_ms  INTEGER NOT NULL DEFAULT 0,
                peak_concurrency INTEGER NOT NULL DEFAULT 0,
                error_timeout    INTEGER NOT NULL DEFAULT 0,
                error_fetch      INTEGER NOT NULL DEFAULT 0,
                error_queue      INTEGER NOT NULL DEFAULT 0,
                error_auth       INTEGER NOT NULL DEFAULT 0,
                error_rate       INTEGER NOT NULL DEFAULT 0,
                error_parse      INTEGER NOT NULL DEFAULT 0,
                challenge_success INTEGER NOT NULL DEFAULT 0,
                challenge_failure INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        // [2026-03-11] 月別集約テーブル
        db.execSQL("""
            CREATE TABLE monthly_stats (
                month_key        TEXT    PRIMARY KEY,
                total_requests   INTEGER NOT NULL DEFAULT 0,
                success_count    INTEGER NOT NULL DEFAULT 0,
                error_count      INTEGER NOT NULL DEFAULT 0,
                total_bytes      INTEGER NOT NULL DEFAULT 0,
                total_duration   INTEGER NOT NULL DEFAULT 0,
                avg_duration_ms  INTEGER NOT NULL DEFAULT 0,
                max_duration_ms  INTEGER NOT NULL DEFAULT 0,
                challenge_success INTEGER NOT NULL DEFAULT 0,
                challenge_failure INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE daily_stats ADD COLUMN challenge_success INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE daily_stats ADD COLUMN challenge_failure INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE monthly_stats ADD COLUMN challenge_success INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE monthly_stats ADD COLUMN challenge_failure INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * 90日超の request_log を削除、90日超の daily_stats を削除、24ヶ月超の monthly_stats を削除
     */
    fun cleanup() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val ninetyDaysAgo = now - 90L * 24 * 60 * 60 * 1000
        val twentyFourMonthsAgo = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
            .format(java.util.Date(now - 730L * 24 * 60 * 60 * 1000))

        db.execSQL("DELETE FROM request_log WHERE timestamp < ?", arrayOf(ninetyDaysAgo))

        val ninetyDaysAgoDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(ninetyDaysAgo))
        db.execSQL("DELETE FROM daily_stats WHERE date_key < ?", arrayOf(ninetyDaysAgoDate))
        db.execSQL("DELETE FROM monthly_stats WHERE month_key < ?", arrayOf(twentyFourMonthsAgo))
    }
}
