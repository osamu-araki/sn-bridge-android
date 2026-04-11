// Version: 1.6.0 | Updated: 2026-03-14
// [2026-03-11] Semaphore ベースの WebView プール。複数 WebView を並列管理する。
// [2026-03-13] メインスレッドデッドロック修正、SSL エラーハンドリング追加
// [2026-03-13] Cookie 永続化: CookieManager でディスク保存、reCAPTCHA 対策
// [2026-03-13] チャレンジ検知: Cloudflare 等の認証ページを検知し手動解除画面を表示
package jp.salesnow.chromebridge.fetcher

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 複数の WebView インスタンスを管理するプール。
 * Semaphore で同時使用数を制御し、空き WebView を ConcurrentLinkedQueue で管理する。
 *
 * 注意: WebView の生成・操作はメインスレッドで行う必要がある。
 * [2026-03-13] SSL エラーハンドリング追加（Service コンテキストの証明書検証問題対策）
// [2026-03-13] Cookie 永続化: CookieManager でディスク保存、reCAPTCHA 対策
 */
class WebViewPool(
    private val context: Context,
    private val poolSize: Int,
    private val onLog: (String) -> Unit = {}
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
            // [2026-03-13] Cookie 永続化: CookieManager を有効化
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            for (i in 0 until poolSize) {
                val wv = createWebView()
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
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
            // [2026-03-13] データベースストレージ有効化（Cookie/localStorage 永続化）
            settings.databaseEnabled = true
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
        // [2026-03-13] stopLoading のみ。about:blank ロードは次の loadUrl で上書きされるため不要
        mainHandler.post {
            webView.stopLoading()
            // [2026-03-13] Cookie をディスクにフラッシュ
            CookieManager.getInstance().flush()
        }
        available.add(webView)
        semaphore.release()
    }

    /**
     * フェッチリクエストを実行する。WebView の取得・返却を内部で管理する。
     */
    // [2026-03-12] acquire タイムアウトを短縮（5秒）して cloudflared の接続保持時間を削減
    private val acquireTimeoutMs = 5000L

    fun fetch(request: FetchRequest): FetchResult {
        val pair = acquire(acquireTimeoutMs)
            ?: return FetchResult(
                ok = false, error = "pool_busy",
                message = "WebView プールが空きません（${acquireTimeoutMs / 1000}秒待機）"
            )

        val (wv, workerId) = pair
        try {
            return doFetch(wv, request, workerId)
        } finally {
            release(wv)
        }
    }

    // [2026-03-13] チャレンジ手動解除の追加待ち時間（秒）
    private val challengeExtraTimeout = 120L

    private fun doFetch(wv: WebView, request: FetchRequest, workerId: Int): FetchResult {
        val latch = CountDownLatch(1)
        var jsResult: String? = null
        var pageError: String? = null
        val challengeDetected = AtomicBoolean(false)
        val challengeShown = AtomicBoolean(false)

        mainHandler.post {
            wv.webViewClient = object : WebViewClient() {
                private var extracted = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (extracted) return
                    if (url == null || url == "about:blank") return

                    // [2026-03-13] タイトルでチャレンジページかどうか判定
                    wv.evaluateJavascript("document.title") { titleRaw ->
                        if (extracted) return@evaluateJavascript
                        val title = titleRaw?.trim('"') ?: ""

                        if (ChallengeManager.isChallengeTitle(title)) {
                            // チャレンジページ検知 → ユーザーに画面を表示
                            challengeDetected.set(true)
                            if (!challengeShown.getAndSet(true)) {
                                android.util.Log.d("ChromeBridge", "チャレンジ検知: $title (worker=$workerId)")
                                onLog("チャレンジ検知: $title (worker=$workerId)")
                                ChallengeManager.show(context, wv)
                            }
                            // latch は countDown しない → ユーザーの操作を待つ
                        } else {
                            // 通常ページ → コンテンツ抽出
                            extracted = true
                            // チャレンジ画面が出ていたら閉じる
                            if (challengeShown.get()) {
                                android.util.Log.d("ChromeBridge", "チャレンジ解除: $title (worker=$workerId)")
                                onLog("チャレンジ解除: $title (worker=$workerId)")
                                mainHandler.post { ChallengeManager.dismiss() }
                            }
                            mainHandler.postDelayed({
                                val js = if (request.mode == "dom") JsExtractors.EXTRACT_DOM
                                         else JsExtractors.EXTRACT_TEXT
                                wv.evaluateJavascript(js) { result ->
                                    jsResult = result
                                    latch.countDown()
                                }
                            }, request.wait * 1000L)
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?, errorCode: Int,
                    description: String?, failingUrl: String?
                ) {
                    pageError = "WebView エラー: $description (code=$errorCode, worker=$workerId)"
                    latch.countDown()
                }

                // [2026-03-13] SSL エラー時にページ読み込みを続行
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    android.util.Log.w("ChromeBridge", "SSL エラー: ${error?.primaryError} url=${error?.url}")
                    handler?.proceed()
                }
            }
            wv.loadUrl(request.url)
        }

        // [2026-03-13] 通常タイムアウトで待機
        var completed = latch.await(request.timeout.toLong(), TimeUnit.SECONDS)

        // チャレンジが検知されていた場合、追加で待機（ユーザーの手動操作時間）
        if (!completed && challengeDetected.get()) {
            android.util.Log.d("ChromeBridge", "チャレンジ待機中... 追加 ${challengeExtraTimeout}秒 (worker=$workerId)")
            onLog("チャレンジ待機中... 追加 ${challengeExtraTimeout}秒 (worker=$workerId)")
            completed = latch.await(challengeExtraTimeout, TimeUnit.SECONDS)
        }

        // タイムアウト時はチャレンジ画面も閉じる
        if (!completed) {
            mainHandler.post {
                wv.stopLoading()
                if (challengeShown.get()) ChallengeManager.dismiss()
            }
            val msg = if (challengeDetected.get()) {
                "認証タイムアウト（${challengeExtraTimeout}秒）(worker=$workerId)".also { onLog(it) }
            } else {
                "${request.timeout}秒でタイムアウトしました (worker=$workerId)"
            }
            return FetchResult(ok = false, error = "timeout", message = msg)
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
                available.add(wv)
                break
            }
        }
    }

    val currentSize: Int get() = synchronized(allInstances) { allInstances.size }
    val availableCount: Int get() = available.size

    /**
     * 全 WebView を破棄する。サーバー停止時に呼ぶ。
     * [2026-03-13] メインスレッドから呼ばれた場合のデッドロックを修正
     */
    fun destroy() {
        val destroyAction = Runnable {
            // [2026-03-13] 破棄前に Cookie をフラッシュして永続化
            CookieManager.getInstance().flush()
            synchronized(allInstances) {
                for (wv in allInstances) {
                    wv.stopLoading()
                    wv.destroy()
                }
                allInstances.clear()
            }
            available.clear()
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyAction.run()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                destroyAction.run()
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)
        }
    }
}
