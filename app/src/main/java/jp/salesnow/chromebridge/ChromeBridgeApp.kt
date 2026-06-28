// Version: 1.0.0 | Updated: 2026-05-18
// [2026-05-18] アプリ全体の未捕捉例外ハンドラを設定する Application クラス
package jp.salesnow.chromebridge

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * アプリ起動時にグローバルな UncaughtExceptionHandler を登録する。
 *
 * 予期しない例外を crash.log に記録してから既定ハンドラに委譲し、
 * プロセスを正常に終了させる。Foreground Service は START_STICKY のため、
 * プロセス終了後に OS が自動的に Service を再起動して復帰する。
 *
 * 例外は握りつぶさない（握りつぶすとアプリが破損状態のまま動き続けるため）。
 */
class ChromeBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLog(thread, throwable)
            } catch (_: Throwable) {
                // ログ記録の失敗は無視する（最後の砦なので例外を伝播させない）
            }
            // 例外は既定ハンドラに委譲してプロセスを正常終了させる
            // → Service の START_STICKY により OS が自動再起動する
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
        // [2026-06-28] Cronet (Chromium ネットワークスタック) singleton 初期化
        //   WebView の HTTP リクエストを intercept して TLS handshake を独自実装するため。
        //   遅延初期化でも良いが、最初の fetch までに準備しておきたい。
        try {
            jp.salesnow.chromebridge.fetcher.CronetManager.setLogger { msg ->
                android.util.Log.i("CronetManager", msg)
            }
            jp.salesnow.chromebridge.fetcher.CronetManager.init(this)
        } catch (_: Throwable) {
            // 初期化失敗は無視 (engine = null のまま、intercept は機能しない)
        }
    }

    /**
     * クラッシュ内容を filesDir/logs/crash.log に追記する。
     * ファイルが 256KB を超えたら 1 世代だけ退避してから書き出す（肥大化防止）。
     */
    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val dir = File(filesDir, "logs").also { it.mkdirs() }
        val file = File(dir, "crash.log")
        if (file.exists() && file.length() > 256 * 1024) {
            file.copyTo(File(dir, "crash.1.log"), overwrite = true)
            file.writeText("")
        }
        file.appendText("[$ts] 致命的エラー (thread=${thread.name})\n$sw\n")
    }
}
