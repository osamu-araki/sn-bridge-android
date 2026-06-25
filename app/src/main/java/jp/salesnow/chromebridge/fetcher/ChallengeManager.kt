// Version: 2.0.0 | Updated: 2026-06-10
// [2026-03-13] Cloudflare チャレンジ検知時に WebView を画面表示するための管理シングルトン
// [2026-03-13] フルスクリーンインテント対応: バックグラウンドでも通知経由で確実に画面表示
// [2026-03-13] Slack Incoming Webhook 通知対応
// [2026-04-11] SYSTEM_ALERT_WINDOW 権限があれば通知をスキップして直接 Activity 起動
// [2026-06-10] Codex#4: 複数 WebView 同時 challenge に対応するためキュー化
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
 * フロー（複数 WebView 同時 challenge に対応）:
 * 1. WebViewPool.doFetch でチャレンジ検知 → show(context, wv) でキューに追加
 * 2. キューが空だった場合は ChallengeActivity を起動。それ以外は順番待ち。
 * 3. ChallengeActivity は queue.head を表示。ユーザーが解除 → doFetch が dismiss(wv) を呼ぶ
 * 4. dismiss(wv) はキューから wv を除去し、wv が head だった場合は Activity を次の head に切替
 * 5. キューが空になったら Activity を閉じる
 */
object ChallengeManager {

    private const val CHANNEL_ID = "chrome_bridge_challenge"
    private const val NOTIFICATION_ID = 100

    private val lock = Any()
    private val queue = ArrayDeque<WebView>()

    // 直近の Activity 起動用 context。Activity が死亡しても queue が残っていれば再起動できるようにする
    @Volatile
    private var lastContext: Context? = null

    // ChallengeActivity から見える参照（attach/detach は atomically 行う）
    @Volatile
    private var attachedActivity: android.app.Activity? = null

    @Volatile
    private var onCurrentChanged: ((WebView?) -> Unit)? = null

    // [2026-06-10] Codex 3 回目 Major 2: Activity 起動中フラグ。
    //   show() の race で複数 Activity instance が立ち上がるのを防ぐ。
    //   attachActivity で false に戻すので、Intent 失敗時のリセットは launchActivity 側で行う。
    @Volatile
    private var launchInFlight: Boolean = false

    /** 後方互換: 一部のレガシーコード／ロックスクリーン処理で参照する Activity */
    val activity: android.app.Activity?
        get() = attachedActivity

    /** Activity が表示中の WebView（キュー先頭）。後方互換のため公開。 */
    val pendingWebView: WebView?
        get() = synchronized(lock) { queue.firstOrNull() }

    /** キュー長（テスト・ログ用） */
    val pendingCount: Int
        get() = synchronized(lock) { queue.size }

    /**
     * Activity が callback 登録と現在 head 取得を **atomic に** 行う API。
     * Activity.onCreate で呼ぶ。返値が null なら finish() すべし。
     */
    fun attachActivity(
        activity: android.app.Activity,
        callback: (WebView?) -> Unit,
    ): WebView? {
        synchronized(lock) {
            attachedActivity = activity
            onCurrentChanged = callback
            // [2026-06-10] Activity が attach されたので「起動中」フラグを解除
            launchInFlight = false
            return queue.firstOrNull()
        }
    }

    /**
     * Activity 破棄時に呼ぶ。同一インスタンスでない場合は無視。
     * queue に残っている WebView は次の show() 呼び出しで再起動される。
     */
    fun detachActivity(activity: android.app.Activity) {
        synchronized(lock) {
            if (attachedActivity === activity) {
                attachedActivity = null
                onCurrentChanged = null
            }
        }
    }

    // [2026-03-13] チャレンジページのタイトル判定
    // [2026-06-25] Google 検索の sorry / unusual traffic も検知対象に
    private val challengeTitles = listOf(
        // Cloudflare 系
        "security check",
        "しばらくお待ちください",
        "just a moment",
        "attention required",
        "checking your browser",
        "please wait",
        "un instant",
        // [2026-06-25] Google 検索ブロック・sorry ページ系
        "sorry...",
        "before you continue",
        "unusual traffic",
        "automated queries",
    )

    // [2026-06-25] URL でも判定（Google sorry へのリダイレクト等）
    //   host を Google 系に限定して、任意サイトの /sorry/ ページを誤検知しないようにする
    private val challengeUrlPatterns = listOf(
        "google.com/sorry",
        "google.co.jp/sorry",
    )

    fun isChallengeTitle(title: String): Boolean {
        val lower = title.lowercase().trim()
        return challengeTitles.any { lower.contains(it) }
    }

    /**
     * [2026-06-25] タイトル / URL / DOM ヒントを合成してチャレンジか判定する。
     *   - title:               document.title
     *   - url:                 location.href
     *   - hasRecaptchaIframe:  ページ内に reCAPTCHA iframe が埋め込まれているか
     *   - visibleBodyLen:      document.body.innerText の trim 後文字数（負値なら不明）
     *
     * reCAPTCHA iframe は invisible reCAPTCHA や問い合わせフォーム等にも埋まっているので
     * 単独では false positive リスクが高い。本文が極端に少ない（チャレンジ専用ページ相当）
     * 場合に限って challenge 扱いする。
     */
    fun isChallenge(
        title: String,
        url: String,
        hasRecaptchaIframe: Boolean,
        visibleBodyLen: Int,
    ): Boolean {
        if (isChallengeTitle(title)) return true
        val lowerUrl = url.lowercase()
        if (challengeUrlPatterns.any { lowerUrl.contains(it) }) return true
        // reCAPTCHA 単独判定: body 文字数で「チャレンジ専用ページ」に限定
        if (hasRecaptchaIframe && visibleBodyLen in 0..RECAPTCHA_BODY_LEN_THRESHOLD) return true
        return false
    }

    /** reCAPTCHA iframe 単独判定の本文長閾値。これ以下なら「reCAPTCHA だけのページ」と判断 */
    private const val RECAPTCHA_BODY_LEN_THRESHOLD = 200

    /**
     * チャレンジ画面を表示する（または既に表示中なら順番待ちキューに追加）。
     * Activity が居なくなっている場合は queue が空でなくても再起動する。
     */
    fun show(context: Context, webView: WebView) {
        // [2026-06-10] Codex 3 回目:
        //   - 起動可否を「Activity 不在 AND launchInFlight=false AND queue 非空」で判定
        //   - 同じ WebView の re-show でも Activity 不在なら起動できるよう、alreadyQueued でも launch を走らせる
        //   - 複数 show() の race で同時に launchActivity が呼ばれないよう launchInFlight をロック内で立てる
        val (shouldLaunch, isNewToQueue, queueSize) = synchronized(lock) {
            val already = queue.any { it === webView }
            if (!already) queue.addLast(webView)
            lastContext = context.applicationContext
            val needsLaunch = attachedActivity == null && !launchInFlight && queue.isNotEmpty()
            if (needsLaunch) launchInFlight = true
            Triple(needsLaunch, !already, queue.size)
        }

        if (isNewToQueue) {
            // [2026-06-10] Minor: 重複登録の場合は Slack 通知しないよう順序を入替え済み
            notifySlackIfNeeded(context)
        } else {
            android.util.Log.d(
                "ChromeBridge",
                "ChallengeManager: 同じ WebView の show() 呼び出しを無視（既にキュー登録済み、queue=$queueSize）"
            )
        }

        if (!shouldLaunch) {
            android.util.Log.d(
                "ChromeBridge",
                "ChallengeManager: Activity 既存または起動中（queue=$queueSize）"
            )
            return
        }

        val launched = try {
            launchActivity(context)
        } catch (e: Exception) {
            // Intent 起動が例外で失敗した場合
            android.util.Log.w("ChromeBridge", "launchActivity 例外: ${e.message}")
            false
        }
        if (!launched) {
            // [2026-06-10] Codex 4 回目 Major: 直接起動・通知のいずれも失敗 → 次の show() を救う
            synchronized(lock) { launchInFlight = false }
        }
    }

    private fun notifySlackIfNeeded(context: Context) {
        val webhookUrl = SettingsRepository(context).slackWebhookUrl
        if (webhookUrl.isBlank()) return
        Thread {
            try {
                val payload = JSONObject().apply {
                    put("text", ":warning: *SalesNow Bridge: 認証チャレンジ検知*\n手動認証が必要です。端末を確認してください。")
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

    /**
     * Activity 起動を試みる。**起動できたかどうか** を返す。
     * [2026-06-10] Codex 4 回目 Major: false が返ったら show() 側で launchInFlight をリセット。
     */
    private fun launchActivity(context: Context): Boolean {
        // [2026-04-11] SYSTEM_ALERT_WINDOW 権限があれば通知をスキップして直接起動
        if (android.provider.Settings.canDrawOverlays(context)) {
            val intent = Intent(context, Class.forName("jp.salesnow.chromebridge.ChallengeActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                android.util.Log.w("ChromeBridge", "オーバーレイ経由の直接起動に失敗、通知にフォールバック: ${e.message}")
                // fall through
            }
        }

        val useNotify = SettingsRepository(context).challengeNotify

        if (!useNotify) {
            val intent = Intent(context, Class.forName("jp.salesnow.chromebridge.ChallengeActivity"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return try {
                context.startActivity(intent)
                true
            } catch (e: Exception) {
                android.util.Log.w("ChromeBridge", "チャレンジ画面の起動に失敗（バックグラウンド通知OFF）: ${e.message}")
                false
            }
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

        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            android.util.Log.w("ChromeBridge", "通知投稿に失敗: ${e.message}")
            false
        }
    }

    /**
     * 指定 WebView の challenge を終了する。
     * - キューに居れば除去
     * - その WebView を親から detach
     * - 表示中（head）だったら次の head に Activity を切り替え
     * - キューが空になったら Activity と通知を閉じる
     */
    fun dismiss(webView: WebView) {
        val (wasHead, nextHead, becameEmpty, act, callback) = synchronized(lock) {
            val wasFirst = queue.firstOrNull() === webView
            val iter = queue.iterator()
            var removed = false
            while (iter.hasNext()) {
                if (iter.next() === webView) { iter.remove(); removed = true; break }
            }
            val next = queue.firstOrNull()
            val empty = queue.isEmpty()
            // [2026-06-10] Codex 3 回目: queue が空になったら launchInFlight を確実にリセット。
            //   Activity が起動失敗・未到達のまま fetch がタイムアウトして dismiss されるケースを救う。
            if (empty) launchInFlight = false
            Quintet(wasFirst && removed, next, empty, attachedActivity, onCurrentChanged)
        }
        (webView.parent as? ViewGroup)?.removeView(webView)

        if (becameEmpty) {
            act?.let { ctx ->
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
            }
            callback?.invoke(null)
            act?.finish()
        } else if (wasHead) {
            callback?.invoke(nextHead)
        }
    }

    /**
     * 後方互換: 引数なし dismiss は現在の head を閉じる。
     * 新規コードは dismiss(webView) を使うこと。
     */
    fun dismiss() {
        val head = synchronized(lock) { queue.firstOrNull() } ?: run {
            // キューが空でも Activity が残っていたら閉じる
            val act = attachedActivity
            val cb = onCurrentChanged
            cb?.invoke(null)
            act?.let { ctx ->
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
            }
            act?.finish()
            return
        }
        dismiss(head)
    }

    fun cleanup() {
        synchronized(lock) {
            queue.clear()
            attachedActivity = null
            onCurrentChanged = null
            lastContext = null
            launchInFlight = false
        }
    }

    // ローカル 5-tuple（Kotlin の標準 Tuple は Triple まで）
    private data class Quintet<A, B, C, D, E>(
        val first: A, val second: B, val third: C, val fourth: D, val fifth: E,
    )

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
