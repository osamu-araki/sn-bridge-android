// Version: 1.0.1 | Updated: 2026-03-11
// [2026-03-08] Chrome 拡張の handleFetchRequest を Android WebView で再実装
// [2026-03-11] @Deprecated: WebViewPool に置き換え済み。FetchRequest/FetchResult の定義元として残す。
package jp.salesnow.chromebridge.fetcher

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class FetchRequest(
    val url: String,
    val wait: Int = 3,
    val maxLength: Int = 50000,
    val timeout: Int = 30,
    val mode: String = "text",
    // [2026-06-20] リクエスト毎の User-Agent 上書き。null/空文字なら WebView デフォルト UA を使う。
    val userAgent: String? = null,
    // [2026-06-26] リクエストの用途識別子。"healthcheck" 等。null/空 なら通常リクエスト。
    //   チャレンジ画面の表示モード判定 (EXCLUDE_HEALTHCHECK) に利用。
    val purpose: String? = null,
)

data class FetchResult(
    val ok: Boolean,
    val text: String = "",
    val title: String = "",
    val finalUrl: String = "",
    val mode: String = "text",
    val dom: String? = null,
    val error: String? = null,
    val message: String? = null,
    // [2026-06-27] WebView の onReceivedHttpError で捕捉した HTTP status code。
    //   既定 200 (= エラーが起きなかった想定)。403 / 404 / 5xx 等を捕捉した場合に上書き。
    val httpStatus: Int = 200,
)

/**
 * 非表示の WebView を1つ保持し、ページ取得リクエストを処理する
 * Chrome 拡張のワーカータブ固定化と同じ発想
 *
 * @deprecated WebViewPool に置き換え済み。新しいコードでは WebViewPool を使用すること。
 */
@Deprecated("WebViewPool に置き換え済み", replaceWith = ReplaceWith("WebViewPool"))
class HeadlessWebViewFetcher(private val context: Context) {
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // [2026-03-08] メインスレッドから呼ばれた場合はデッドロック防止のため直接初期化
    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            initWebView()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                initWebView()
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // [2026-03-08] Chrome 互換の UA に設定（WebView ブロック対策）
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace("Version/\\d+\\.\\d+".toRegex(), "")
        }
    }

    fun fetch(request: FetchRequest): FetchResult {
        val wv = webView ?: return FetchResult(
            ok = false, error = "webview_not_ready", message = "WebView が初期化されていません"
        )

        val latch = CountDownLatch(1)
        var jsResult: String? = null
        var pageError: String? = null

        mainHandler.post {
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // ページ読み込み完了後、wait 秒待機して JS 実行
                    mainHandler.postDelayed({
                        val js = if (request.mode == "dom") JsExtractors.EXTRACT_DOM
                                 else JsExtractors.EXTRACT_TEXT
                        wv.evaluateJavascript(js) { result ->
                            jsResult = result
                            latch.countDown()
                        }
                    }, request.wait * 1000L)
                }

                override fun onReceivedError(
                    view: WebView?, errorCode: Int,
                    description: String?, failingUrl: String?
                ) {
                    pageError = "WebView エラー: $description (code=$errorCode)"
                    latch.countDown()
                }
            }
            wv.loadUrl(request.url)
        }

        val completed = latch.await(request.timeout.toLong(), TimeUnit.SECONDS)

        if (!completed) {
            // タイムアウト — ロード中止
            mainHandler.post { wv.stopLoading() }
            return FetchResult(
                ok = false, error = "timeout",
                message = "${request.timeout}秒でタイムアウトしました"
            )
        }

        if (pageError != null) {
            return FetchResult(ok = false, error = "fetch_failed", message = pageError)
        }

        return parseJsResult(jsResult, request)
    }

    private fun parseJsResult(raw: String?, request: FetchRequest): FetchResult {
        if (raw == null || raw == "null") {
            return FetchResult(ok = false, error = "extract_failed", message = "JS 実行結果が空です")
        }

        try {
            // evaluateJavascript は JSON 文字列をさらに引用符で囲むため、アンエスケープ
            val unescaped = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                raw.substring(1, raw.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
            } else raw

            val json = com.google.gson.JsonParser.parseString(unescaped).asJsonObject
            var text = json.get("text")?.asString ?: ""
            if (text.length > request.maxLength) text = text.substring(0, request.maxLength)

            val title = json.get("title")?.asString ?: ""
            val finalUrl = json.get("url")?.asString ?: request.url

            val domJson = if (request.mode == "dom" && json.has("dom")) {
                json.getAsJsonObject("dom").toString()
            } else null

            return FetchResult(
                ok = true, text = text, title = title,
                finalUrl = finalUrl, mode = request.mode, dom = domJson
            )
        } catch (e: Exception) {
            return FetchResult(
                ok = false, error = "parse_failed",
                message = "結果のパースに失敗: ${e.message}"
            )
        }
    }

    fun destroy() {
        mainHandler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}
