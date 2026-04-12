// Version: 1.3.0 | Updated: 2026-04-11
// [2026-03-13] Cloudflare チャレンジ検知時に WebView を画面表示するための管理シングルトン
// [2026-03-13] フルスクリーンインテント対応: バックグラウンドでも通知経由で確実に画面表示
// [2026-03-13] Slack Incoming Webhook 通知対応
// [2026-04-11] SYSTEM_ALERT_WINDOW 権限があれば通知をスキップして直接 Activity 起動
package jp.salesnow.chromebridge.fetcher

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import jp.salesnow.chromebridge.data.SettingsRepository
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloudflare / reCAPTCHA 等のチャレンジページを検知した際に、
 * WebView をユーザーに表示して手動認証を可能にするマネージャー。
 *
 * フロー:
 * 1. WebViewPool.doFetch でチャレンジ検知 → show() 呼び出し
 * 2. フルスクリーンインテント通知 → ChallengeActivity が起動し WebView を画面表示
 * 3. ユーザーが手動で認証を通す
 * 4. ページリロード → WebViewClient.onPageFinished で非チャレンジページを検知
 * 5. dismiss() で ChallengeActivity を閉じ、WebView をプールに戻す
 */
object ChallengeManager {

    private const val CHANNEL_ID = "chrome_bridge_challenge"
    private const val NOTIFICATION_ID = 100

    @Volatile
    var pendingWebView: WebView? = null
        private set

    @Volatile
    var activity: android.app.Activity? = null

    // [2026-03-13] チャレンジページのタイトル判定
    private val challengeTitles = listOf(
        "security check",
        "しばらくお待ちください",
        "just a moment",
        "attention required",
        "checking your browser",
        "please wait",
        "un instant"
    )

    fun isChallengeTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return challengeTitles.any { lower.contains(it) }
    }

    /**
     * チャレンジ画面を表示する。Service コンテキストから呼ばれる。
     * challengeNotify 設定が ON の場合: フルスクリーンインテント通知でバックグラウンドでも表示
     * challengeNotify 設定が OFF の場合: フォアグラウンド時のみ Activity 直接起動
     */
    fun show(context: Context, webView: WebView) {
        pendingWebView = webView

        // [2026-03-13] Slack Webhook 通知（設定されていれば fire-and-forget で送信）
        val webhookUrl = SettingsRepository(context).slackWebhookUrl
        if (webhookUrl.isNotBlank()) {
            Thread {
                try {
                    val payload = JSONObject().apply {
                        put("text", ":warning: *Chrome Bridge: 認証チャレンジ検知*\n手動認証が必要です。端末を確認してください。")
                    }
                    val conn = URL(webhookUrl).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                    conn.responseCode // 送信実行
                    conn.disconnect()
                } catch (e: Exception) {
                    android.util.Log.w("ChromeBridge", "Slack 通知に失敗: ${e.message}")
                }
            }.start()
        }

        // [2026-04-11] SYSTEM_ALERT_WINDOW 権限があれば通知をスキップして直接起動
        // オーバーレイ権限は Android のバックグラウンド Activity 起動制限を公式に
        // 回避できる唯一の方法（フォアグラウンドサービス単体では Android 12+ で不可）
        if (android.provider.Settings.canDrawOverlays(context)) {
            val intent = Intent(context, Class.forName("jp.salesnow.chromebridge.ChallengeActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                android.util.Log.w("ChromeBridge", "オーバーレイ経由の直接起動に失敗、通知にフォールバック: ${e.message}")
                // fall through
            }
        }

        val useNotify = SettingsRepository(context).challengeNotify

        if (!useNotify) {
            // [2026-03-13] 通知なし: Activity 直接起動（フォアグラウンド時のみ動作）
            val intent = Intent(context, Class.forName("jp.salesnow.chromebridge.ChallengeActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.w("ChromeBridge", "チャレンジ画面の起動に失敗（バックグラウンド通知OFF）: ${e.message}")
            }
            return
        }

        ensureNotificationChannel(context)

        val intent = Intent(context, Class.forName("jp.salesnow.chromebridge.ChallengeActivity"))
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        )

        val fullScreenIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // [2026-03-13] フルスクリーンインテント通知（着信画面と同じ仕組み）
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("認証が必要です")
            .setContentText("タップして認証画面を開いてください")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    /**
     * チャレンジ完了後に Activity を閉じ、WebView を親から切り離す。
     */
    fun dismiss() {
        val wv = pendingWebView
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
        // 通知をキャンセル
        activity?.let { ctx ->
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
        activity?.finish()
        activity = null
        pendingWebView = null
    }

    fun cleanup() {
        activity = null
        pendingWebView = null
    }

    // [2026-03-13] チャレンジ用通知チャンネル（重要度: HIGH でヘッドアップ表示）
    private fun ensureNotificationChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "認証リクエスト",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cloudflare 等の認証が必要な場合に通知します"
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }
}
