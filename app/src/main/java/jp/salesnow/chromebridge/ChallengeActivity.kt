// Version: 1.1.0 | Updated: 2026-03-13
// [2026-03-13] Cloudflare チャレンジ等の手動認証画面
// [2026-03-13] ロック画面・バックグラウンドからの表示対応
package jp.salesnow.chromebridge

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import jp.salesnow.chromebridge.fetcher.ChallengeManager

/**
 * WebView を画面に表示して、ユーザーが手動で Cloudflare チャレンジ等を解除できるようにする Activity。
 * ChallengeManager.show() からフルスクリーンインテント経由で起動される。
 * 認証完了後に自動で閉じられる。
 */
class ChallengeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ChallengeManager.activity = this

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

        val webView = ChallengeManager.pendingWebView
        if (webView == null) {
            finish()
            return
        }

        // WebView が他の親に属していれば切り離す
        (webView.parent as? ViewGroup)?.removeView(webView)

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
            addView(FrameLayout(this@ChallengeActivity).apply {
                addView(webView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }

        setContentView(root)
    }

    override fun onDestroy() {
        // Activity 破棄前に WebView を親から切り離す（WebView の二重破棄防止）
        val webView = ChallengeManager.pendingWebView
        if (webView != null) {
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
        ChallengeManager.activity = null
        super.onDestroy()
    }
}
