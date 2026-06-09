// Version: 2.1.0 | Updated: 2026-06-10
// [2026-03-13] Cloudflare チャレンジ等の手動認証画面
// [2026-03-13] ロック画面・バックグラウンドからの表示対応
// [2026-06-10] Codex#4: ChallengeManager のキュー対応に追従。head 切替時に WebView を差し替える。
// [2026-06-10] Codex 再レビュー: attachActivity で atomic に callback 登録 + 初期 head 取得し
//   onCreate 中の dismiss(head) を取り逃がさない。onDestroy は detachActivity を呼ぶ。
package jp.salesnow.chromebridge

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import jp.salesnow.chromebridge.fetcher.ChallengeManager

/**
 * WebView を画面に表示して、ユーザーが手動で Cloudflare チャレンジ等を解除できるようにする Activity。
 * ChallengeManager.show() からフルスクリーンインテント経由で起動される。
 * 複数 WebView が同時に challenge になっても、キューの head を1つずつ表示する。
 */
class ChallengeActivity : ComponentActivity() {

    private var currentWebView: WebView? = null
    private lateinit var webViewContainer: FrameLayout

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
        val initial = ChallengeManager.attachActivity(this) { next ->
            runOnUiThread {
                if (next == null) {
                    finish()
                } else if (next !== currentWebView) {
                    detachCurrent()
                    attachWebView(next)
                }
            }
        }
        if (initial == null) {
            finish()
            return
        }
        attachWebView(initial)
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
    }

    private fun detachCurrent() {
        currentWebView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        currentWebView = null
    }

    override fun onDestroy() {
        // Activity 破棄前に WebView を親から切り離す（WebView の二重破棄防止）
        detachCurrent()
        ChallengeManager.detachActivity(this)
        super.onDestroy()
    }
}
