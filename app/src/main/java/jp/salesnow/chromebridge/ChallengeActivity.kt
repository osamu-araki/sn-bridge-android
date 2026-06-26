// Version: 2.2.0 | Updated: 2026-06-25
// [2026-03-13] Cloudflare チャレンジ等の手動認証画面
// [2026-03-13] ロック画面・バックグラウンドからの表示対応
// [2026-06-10] Codex#4: ChallengeManager のキュー対応に追従。head 切替時に WebView を差し替える。
// [2026-06-10] Codex 再レビュー: attachActivity で atomic に callback 登録 + 初期 head 取得し
//   onCreate 中の dismiss(head) を取り逃がさない。onDestroy は detachActivity を呼ぶ。
// [2026-06-25] ドメイン別タップ記憶 + 次回自動タップ。Cookie 同意 / 単純な確認ボタンの自動突破用。
//   Cloudflare Turnstile / reCAPTCHA 等の bot 検知は通らない設計だが、軽い障壁の自動化に効く。
package jp.salesnow.chromebridge

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import jp.salesnow.chromebridge.data.SettingsRepository
import jp.salesnow.chromebridge.fetcher.ChallengeManager

/**
 * WebView を画面に表示して、ユーザーが手動で Cloudflare チャレンジ等を解除できるようにする Activity。
 * ChallengeManager.show() からフルスクリーンインテント経由で起動される。
 * 複数 WebView が同時に challenge になっても、キューの head を1つずつ表示する。
 *
 * [2026-06-25] 1 度ユーザーがタップしたドメインは座標を SharedPreferences に記録し、
 * 次回同じドメインで challenge が出た時に自動タップを試みる。
 */
class ChallengeActivity : ComponentActivity() {

    private var currentWebView: WebView? = null
    private lateinit var webViewContainer: FrameLayout
    private val mainHandler = Handler(Looper.getMainLooper())
    // [2026-06-25] ヘッダー右側のステータステキスト（自動タップ試行中 / 失敗）
    private var subtitleText: TextView? = null

    // [2026-06-25] 現在の WebView 上でユーザーが最後にタップした座標。
    //   dismiss された（= challenge 通過した）タイミングでドメイン別に保存する。
    private var lastTapX: Float? = null
    private var lastTapY: Float? = null
    private var lastTapDomain: String? = null
    // [2026-06-25] Codex 指摘: タイムアウト等で head が消えた時に古い座標を保存しないため、
    //   タップ時刻から SAVE_WINDOW_MS 以内に dismiss された場合だけ保存する。
    private var lastTapAtMs: Long = 0L
    // [2026-06-26] 画像選択など 2 段階目以降のタップで以前のチェックボックス座標を上書きしない
    //   ため、attachWebView 後の「最初の ACTION_DOWN」だけ学習対象として記録する。
    //   reCAPTCHA: 1 回目 = 「私はロボットではありません」 / 2 回目以降 = 画像選択
    private var tapAlreadyCaptured: Boolean = false
    // [2026-06-26] dispatchTouchEvent 自身が OnTouchListener を通って「ユーザータップ」と
    //   誤認されないよう、自動タップ中フラグを立てる（Codex 指摘 #1）。
    private var isAutoTapping: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [2026-03-13] ロック画面上に表示 + 画面をオンにする
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // [2026-06-10] container を先に作る（callback が container を参照するため）
        webViewContainer = FrameLayout(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F5F5.toInt())

            // ヘッダー: 認証が必要な旨を表示
            addView(LinearLayout(this@ChallengeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF2A9BA1.toInt())
                setPadding(48, 48, 48, 48)

                addView(TextView(this@ChallengeActivity).apply {
                    text = "認証が必要です"
                    textSize = 18f
                    setTextColor(0xFFFFFFFF.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })

                addView(TextView(this@ChallengeActivity).apply {
                    text = "下の画面で認証を完了してください。完了後、自動的に閉じます。"
                    textSize = 13f
                    setTextColor(0xCCFFFFFF.toInt())
                    setPadding(0, 12, 0, 0)
                    subtitleText = this
                })
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            // WebView 表示エリア
            addView(webViewContainer, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }

        setContentView(root)

        // [2026-06-10] atomic な callback 登録 + 初期 head 取得。
        //   registration と head の読み出し間に dismiss(head) が走っても
        //   ChallengeManager 内のロックで整合する。
        // [2026-06-25] head 切替 / 終了の遷移を捕まえて、消えた WebView の domain に
        //   直前のタップを保存する。
        val initial = ChallengeManager.attachActivity(this) { next ->
            runOnUiThread {
                onHeadChanged(next)
            }
        }
        if (initial == null) {
            finish()
            return
        }
        attachWebView(initial)
    }

    /** head が next に切り替わった or 終了 (next=null) になった時の処理。 */
    private fun onHeadChanged(next: WebView?) {
        // 直前の WebView が消えた = challenge 通過した。最後のタップを domain に保存。
        persistLastTapIfAny()
        if (next == null) {
            finish()
        } else if (next !== currentWebView) {
            detachCurrent()
            attachWebView(next)
        }
    }

    private fun persistLastTapIfAny() {
        val x = lastTapX
        val y = lastTapY
        val d = lastTapDomain
        val sinceTap = System.currentTimeMillis() - lastTapAtMs
        val recent = lastTapAtMs > 0 && sinceTap in 0..SAVE_WINDOW_MS
        if (x != null && y != null && !d.isNullOrBlank() && recent &&
            x.isFinite() && y.isFinite() && x >= 0f && y >= 0f
        ) {
            try {
                SettingsRepository(this).saveTapMemory(d, x, y)
            } catch (_: Exception) {
                // 保存失敗は致命ではないので無視
            }
        }
        lastTapX = null
        lastTapY = null
        lastTapDomain = null
        lastTapAtMs = 0L
    }

    private fun attachWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        webViewContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        currentWebView = wv

        // [2026-06-25] このページのドメインを記録 + 保存済みタップを自動発火
        lastTapDomain = extractDomain(wv.url)
        // [2026-06-26] 新しい WebView は「未学習」状態でスタート。1 回目の ACTION_DOWN だけ
        //   座標を捕捉し、2 回目以降（reCAPTCHA の画像選択タップ等）は無視する。
        tapAlreadyCaptured = false
        installTapCaptureListener(wv)
        // 新しい WebView を表示するときは「失敗」状態をクリア
        setSubtitle("下の画面で認証を完了してください。完了後、自動的に閉じます。", warn = false)
        scheduleAutoTap(wv)
    }

    /** WebView の URL から host (lowercase) を取り出す。host が無ければ null。 */
    private fun extractDomain(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) { null }
    }

    /**
     * WebView へのタッチを横取りせず passthrough しつつ、最後の ACTION_DOWN 座標を記録する。
     * setOnTouchListener は false を返してデフォルト処理に委ねる。
     * Codex 指摘: ACTION_DOWN のたびに domain を再取得し、ページ内遷移にも追従する。
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installTapCaptureListener(wv: WebView) {
        wv.setOnTouchListener { _, event ->
            // [2026-06-26] 最初の ACTION_DOWN のみ学習対象。それ以降のタップ（reCAPTCHA の
            //   画像選択や、画像チャレンジを通過するための「確認」ボタン等）は無視して、
            //   以前のチェックボックス座標がそのまま残るようにする。
            // [2026-06-26] dispatchTouchEvent 経由の自動タップは isAutoTapping で除外。
            if (event.action == MotionEvent.ACTION_DOWN && !tapAlreadyCaptured && !isAutoTapping) {
                lastTapX = event.x
                lastTapY = event.y
                lastTapAtMs = System.currentTimeMillis()
                // domain はタップ時の最新 URL から取り直す（リダイレクト追従）
                extractDomain(wv.url)?.let { lastTapDomain = it }
                tapAlreadyCaptured = true
            }
            false
        }
    }

    /**
     * このドメインの保存済み座標があれば 2 秒後に dispatchTouchEvent を発火。
     * 効くのは Cookie 同意 / 単純な確認ボタン等。bot 検知のあるチャレンジは通らない。
     * 発火後 [AUTO_TAP_FAIL_FEEDBACK_MS] 経過しても画面が閉じていなければ、ヘッダーを
     * 「手動でタップしてください」に切り替えて従来通り人間に処理を委ねる。
     */
    private fun scheduleAutoTap(wv: WebView) {
        val domain = lastTapDomain ?: return
        val coord = try {
            SettingsRepository(this).getTapMemory(domain)
        } catch (_: Exception) { null } ?: return
        // Codex 指摘: 壊れた値 (NaN / 負数) は捨てる。WebView 外座標も発火しても無害だが省く。
        if (!coord.x.isFinite() || !coord.y.isFinite() || coord.x < 0f || coord.y < 0f) return

        // 試行中であることを UI で示す
        setSubtitle("保存済みの座標で自動タップを試行中…", warn = false)

        mainHandler.postDelayed(Runnable {
            // 別 WebView に差し替わっていたら何もしない
            if (currentWebView !== wv) return@Runnable
            // WebView 描画後の bounds を超えていたら捨てる（端末変更・回転対策）
            val w = wv.width
            val h = wv.height
            if (w > 0 && h > 0 && (coord.x > w.toFloat() || coord.y > h.toFloat())) {
                // Codex 指摘: bounds NG で early return すると「試行中…」のまま固定されるので
                //   ここで失敗フィードバックに切り替えてから return する。
                setSubtitle("保存済み座標が画面外のため自動タップできません。手動でタップしてください。", warn = true)
                return@Runnable
            }
            // [2026-06-26] 自動タップが OnTouchListener に「ユーザータップ」と誤認されないよう
            //   isAutoTapping を立てる。UP 完了後（および例外時）に必ず false に戻す。
            isAutoTapping = true
            try {
                val downTime = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, coord.x, coord.y, 0
                )
                wv.dispatchTouchEvent(down)
                down.recycle()
                mainHandler.postDelayed(Runnable {
                    try {
                        if (currentWebView !== wv) return@Runnable
                        val upTime = SystemClock.uptimeMillis()
                        val up = MotionEvent.obtain(
                            downTime, upTime, MotionEvent.ACTION_UP, coord.x, coord.y, 0
                        )
                        wv.dispatchTouchEvent(up)
                        up.recycle()
                    } catch (_: Exception) {
                    } finally {
                        isAutoTapping = false
                    }
                }, AUTO_TAP_UP_DELAY_MS)
            } catch (_: Exception) {
                // 自動タップ失敗は致命ではない（ユーザーが手動で操作可）
                isAutoTapping = false
            }
            // 一定時間経過しても画面が閉じていなければ「失敗」フィードバックに切り替える
            mainHandler.postDelayed(Runnable {
                if (currentWebView !== wv) return@Runnable
                setSubtitle("自動タップでは通過できませんでした。手動でタップしてください。", warn = true)
            }, AUTO_TAP_FAIL_FEEDBACK_MS)
        }, AUTO_TAP_DOWN_DELAY_MS)
    }

    /** ヘッダー下のステータステキストを更新する。warn=true なら橙系で強調表示。 */
    private fun setSubtitle(text: String, warn: Boolean) {
        subtitleText?.let {
            it.text = text
            it.setTextColor(if (warn) 0xFFFFD08A.toInt() else 0xCCFFFFFF.toInt())
        }
    }

    private fun detachCurrent() {
        currentWebView?.setOnTouchListener(null)
        currentWebView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentWebView = null
    }

    override fun onDestroy() {
        // [2026-06-25] back キーや手動 finish() でも、直前タップがあれば保存しておく
        persistLastTapIfAny()
        mainHandler.removeCallbacksAndMessages(null)
        // Activity 破棄前に WebView を親から切り離す（WebView の二重破棄防止）
        detachCurrent()
        ChallengeManager.detachActivity(this)
        super.onDestroy()
    }

    companion object {
        // ページレンダリングを待つ時間（reCAPTCHA / Cloudflare は通らないが、Cookie 同意系は OK）
        private const val AUTO_TAP_DOWN_DELAY_MS = 2_000L
        // ACTION_DOWN と ACTION_UP の間隔（人間のタップに近い 80–100ms 想定）
        private const val AUTO_TAP_UP_DELAY_MS = 80L
        // [2026-06-25] 最後のタップから X ms 以内に dismiss された場合のみ「成功タップ」として保存。
        //   タイムアウトでの head 消失時に古い座標を記憶しないため。
        private const val SAVE_WINDOW_MS = 10_000L
        // [2026-06-25] 自動タップ発火後、この時間経過しても画面が閉じていなければ
        //   ユーザーに「手動操作してください」とフィードバックする。
        private const val AUTO_TAP_FAIL_FEEDBACK_MS = 5_000L
    }
}
