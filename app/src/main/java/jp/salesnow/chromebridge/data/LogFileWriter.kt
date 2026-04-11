// Version: 1.1.0 | Updated: 2026-03-14
// [2026-03-14] ログの自動ファイル保存（バッファリング + 定期フラッシュ + ローテーション）
// [2026-03-14] ローテーション時に SAF フォルダへ自動バックアップ
package jp.salesnow.chromebridge.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * ログを内部ストレージに自動保存するライター。
 *
 * - バッファ: ConcurrentLinkedQueue でスレッドセーフに蓄積
 * - フラッシュ: 10秒ごとにファイルへ追記
 * - ローテーション: 1ファイル 5MB 上限、最大3世代保持
 * - 保存先: context.filesDir/logs/
 * - バックアップ: ローテーション時に SAF フォルダへコピー（設定時のみ）
 */
class LogFileWriter(private val context: Context) {

    private val logDir = File(context.filesDir, "logs").also { it.mkdirs() }
    private val maxFileSize = 5L * 1024 * 1024  // 5MB
    private val maxGenerations = 3

    private val httpBuffer = ConcurrentLinkedQueue<String>()
    private val systemBuffer = ConcurrentLinkedQueue<String>()

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "LogFileWriter").apply { isDaemon = true }
    }

    init {
        executor.scheduleWithFixedDelay(::flush, 10, 10, TimeUnit.SECONDS)
    }

    fun appendHttp(line: String) {
        httpBuffer.add(line)
    }

    fun appendSystem(line: String) {
        systemBuffer.add(line)
    }

    /**
     * バッファの内容をファイルに書き出す。
     * ファイルサイズが上限を超えたらローテーションする。
     */
    fun flush() {
        try {
            flushBuffer(httpBuffer, "http.log")
            flushBuffer(systemBuffer, "system.log")
        } catch (e: Exception) {
            android.util.Log.w("ChromeBridge", "ログファイル書き出し失敗: ${e.message}")
        }
    }

    private fun flushBuffer(buffer: ConcurrentLinkedQueue<String>, fileName: String) {
        if (buffer.isEmpty()) return

        val lines = buildString {
            var line = buffer.poll()
            while (line != null) {
                appendLine(line)
                line = buffer.poll()
            }
        }

        val file = File(logDir, fileName)
        file.appendText(lines)

        // ローテーションチェック
        if (file.length() > maxFileSize) {
            rotate(fileName)
        }
    }

    /**
     * ファイルをローテーションする。
     * http.log → http.1.log → http.2.log（最古は削除）
     * バックアップが有効な場合、ローテーション前のファイルを SAF フォルダにコピー。
     */
    private fun rotate(fileName: String) {
        val current = File(logDir, fileName)

        // [2026-03-14] バックアップ: ローテーション前にSAFフォルダへコピー
        backupToSaf(current, fileName)

        val baseName = fileName.removeSuffix(".log")
        // 最古の世代を削除
        File(logDir, "$baseName.${maxGenerations - 1}.log").delete()
        // 世代をシフト
        for (i in (maxGenerations - 2) downTo 1) {
            val from = File(logDir, "$baseName.$i.log")
            val to = File(logDir, "$baseName.${i + 1}.log")
            if (from.exists()) from.renameTo(to)
        }
        // 現在のファイルを .1 にリネーム
        val first = File(logDir, "$baseName.1.log")
        current.renameTo(first)
    }

    /**
     * ローテーション対象のファイルを SAF フォルダにバックアップする。
     * 設定が無効 or URI 未設定の場合は何もしない。
     */
    private fun backupToSaf(file: File, fileName: String) {
        try {
            val settings = SettingsRepository(context)
            if (!settings.logAutoBackup) return
            val uriStr = settings.logBackupUri
            if (uriStr.isBlank()) return

            val treeUri = Uri.parse(uriStr)
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return

            // タイムスタンプ付きファイル名で保存
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.JAPAN)
                .format(java.util.Date())
            val baseName = fileName.removeSuffix(".log")
            val backupName = "chrome-bridge-${baseName}_$ts.log"

            val newFile = dir.createFile("text/plain", backupName) ?: return
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                file.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            android.util.Log.d("ChromeBridge", "ログバックアップ完了: $backupName")
        } catch (e: Exception) {
            android.util.Log.w("ChromeBridge", "ログバックアップ失敗: ${e.message}")
        }
    }

    /**
     * 全世代のログを結合して返す（エクスポート用）。
     * 古い世代 → 新しい世代の順で結合。
     */
    fun readAllHttp(): String = readAll("http.log")

    fun readAllSystem(): String = readAll("system.log")

    private fun readAll(fileName: String): String {
        // まずバッファをフラッシュして最新の状態にする
        flush()

        val baseName = fileName.removeSuffix(".log")
        val parts = mutableListOf<String>()

        // 古い世代から読む
        for (i in (maxGenerations - 1) downTo 1) {
            val file = File(logDir, "$baseName.$i.log")
            if (file.exists()) parts.add(file.readText())
        }
        // 現在のファイル
        val current = File(logDir, fileName)
        if (current.exists()) parts.add(current.readText())

        return parts.joinToString("")
    }

    /**
     * 最終フラッシュを実行し executor をシャットダウンする。
     */
    fun destroy() {
        try {
            executor.shutdown()
            flush() // 最終フラッシュ（同期的に実行）
            executor.awaitTermination(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            android.util.Log.w("ChromeBridge", "LogFileWriter 破棄エラー: ${e.message}")
        }
    }
}
