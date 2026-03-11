// Version: 1.0.0 | Updated: 2026-03-11
// [2026-03-11] Semaphore ベースの WebView プール。複数 WebView を並列管理する。
package jp.salesnow.chromebridge.fetcher

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 複数の WebView インスタンスを管理するプール。
 * Semaphore で同時使用数を制御し、空き WebView を ConcurrentLinkedQueue で管理する。
 *
 * 注意: WebView の生成・操作はメインスレッドで行う必要がある。
 * acquire() は Semaphore で待機後、プールから WebView を取得する。
 * 呼び出し側は使用後に必ず release() を呼ぶこと。
 */
class WebViewPool(
    private val context: Context,
    private val poolSize: Int
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val semaphore = Semaphore(poolSize)
    private val available = ConcurrentLinkedQueue<WebView>()
    private val allInstances = mutableListOf<WebView>()
    private val idCounter = AtomicInteger(0)

    /**
     * プール内の全 WebView をメインスレッドで初期化する。
     * サーバー起動時に1回だけ呼ぶ。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        val latch = CountDownLatch(1)
        val initAction = Runnable {
            for (i in 0 until poolSize) {
                val wv = createWebView()
                allInstances.add(wv)
                available.add(wv)
            }
            latch.countDown()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            initAction.run()
        } else {
            mainHandler.post(initAction)
            latch.await(30, TimeUnit.SECONDS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // [2026-03-11] Chrome 互換の UA（WebView ブロック対策）
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace("Version/\\d+\\.\\d+".toRegex(), "")
        }
    }

    /**
     * WebView を1つ取得する。空きがなければ Semaphore で待機する。
     * @param timeoutMs 取得待ちタイムアウト（ミリ秒）
     * @return WebView と workerId のペア。タイムアウト時は null。
     */
    fun acquire(timeoutMs: Long): Pair<WebView, Int>? {
        val acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        if (!acquired) return null

        val wv = available.poll()
        if (wv == null) {
            // 本来到達しないが、安全のため Semaphore を返却
            semaphore.release()
            return null
        }
        val workerId = idCounter.incrementAndGet()
        return Pair(wv, workerId)
    }

    /**
     * 使用済みの WebView をプールに返却する。
     */
    fun release(webView: WebView) {
        // [2026-03-11] 返却前にクリーンアップ（前のページの状態を引き継がない）
        mainHandler.post {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }
        available.add(webView)
        semaphore.release()
    }

    /**
     * フェッチリクエストを実行する。WebView の取得・返却を内部で管理する。
     */
    fun fetch(request: FetchRequest): FetchResult {
        val pair = acquire(request.timeout * 1000L)
            ?: return FetchResult(
                ok = false, error = "pool_busy",
                message = "WebView プールが空きません（${request.timeout}秒待機）"
            )

        val (wv, workerId) = pair
        try {
            return doFetch(wv, request, workerId)
        } finally {
            release(wv)
        }
    }

    private fun doFetch(wv: WebView, request: FetchRequest, workerId: Int): FetchResult {
        val latch = CountDownLatch(1)
        var jsResult: String? = null
        var pageError: String? = null

        mainHandler.post {
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
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
                    pageError = "WebView エラー: $description (code=$errorCode, worker=$workerId)"
                    latch.countDown()
                }
            }
            wv.loadUrl(request.url)
        }

        val completed = latch.await(request.timeout.toLong(), TimeUnit.SECONDS)

        if (!completed) {
            mainHandler.post { wv.stopLoading() }
            return FetchResult(
                ok = false, error = "timeout",
                message = "${request.timeout}秒でタイムアウトしました (worker=$workerId)"
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

    /**
     * メモリ逼迫時にプールを縮小する（未使用の WebView を破棄）。
     * 最低1つは残す。
     */
    fun onLowMemory() {
        var shrunk = 0
        while (available.size > 1) {
            val wv = available.poll() ?: break
            if (semaphore.tryAcquire()) {
                mainHandler.post {
                    wv.stopLoading()
                    wv.destroy()
                }
                synchronized(allInstances) { allInstances.remove(wv) }
                shrunk++
            } else {
                // 使用中なので戻す
                available.add(wv)
                break
            }
        }
    }

    /**
     * 現在のプールサイズ（アクティブな WebView 数）
     */
    val currentSize: Int get() = synchronized(allInstances) { allInstances.size }

    /**
     * 現在の空き WebView 数
     */
    val availableCount: Int get() = available.size

    /**
     * 全 WebView を破棄する。サーバー停止時に呼ぶ。
     */
    fun destroy() {
        val latch = CountDownLatch(1)
        mainHandler.post {
            synchronized(allInstances) {
                for (wv in allInstances) {
                    wv.stopLoading()
                    wv.destroy()
                }
                allInstances.clear()
            }
            available.clear()
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
    }
}
