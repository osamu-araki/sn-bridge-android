// Version: 1.11.0 | Updated: 2026-06-26
// [2026-06-26] デフォルト User-Agent override (Settings) を fetch 時に適用 + 同一ホストへの
//   最小リクエスト間隔 throttle を追加（Google 403 等の予防策）
// [2026-06-25] チャレンジ検知をタイトル単体から「title + URL + reCAPTCHA iframe」の複合判定に拡張
// [2026-06-20] FetchRequest.userAgent を doFetch 内で WebView.settings に適用、リクエスト毎の UA 上書きをサポート
// [2026-03-11] Semaphore ベースの WebView プール。複数 WebView を並列管理する。
// [2026-03-13] メインスレッドデッドロック修正、SSL エラーハンドリング追加
// [2026-03-13] Cookie 永続化: CookieManager でディスク保存、reCAPTCHA 対策
// [2026-03-13] チャレンジ検知: Cloudflare 等の認証ページを検知し手動解除画面を表示
// [2026-04-12] チャレンジログ強化: ドメイン・所要時間・成否を記録
// [2026-05-18] レンダラクラッシュ検知(onRenderProcessGone)・プール自己補充を追加
package jp.salesnow.chromebridge.fetcher

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
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
 * [2026-03-13] Cookie 永続化: CookieManager でディスク保存、reCAPTCHA 対策
 *
 * [2026-05-18] 不変条件: semaphore の総 permit 数 == allInstances.size
 *   - acquire/release は permit を 1 つ消費・返却する
 *   - onLowMemory は permit を消費したまま allInstances からも削除（縮小）
 *   - replaceWebView は呼び出し側の保持 permit を引き継ぐ（成功時のみ返却）
 *   - maybeReplenish は新規 WebView ごとに permit を 1 つ追加する
 */
class WebViewPool(
    private val context: Context,
    private val poolSize: Int,
    private val onLog: (String) -> Unit = {},
    // [2026-04-12] チャレンジ統計記録コールバック (success: Boolean)
    private val onChallengeResult: (Boolean) -> Unit = {}
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val semaphore = Semaphore(poolSize)
    private val available = ConcurrentLinkedQueue<WebView>()
    private val allInstances = mutableListOf<WebView>()
    private val idCounter = AtomicInteger(0)

    // [2026-05-18] レンダラがクラッシュした WebView を記録するセット
    private val deadInstances: MutableSet<WebView> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<WebView, Boolean>())
    // [2026-06-20] createWebView 時に決まる「リクエスト UA 未指定時のフォールバック値」。
    //   各 WebView は createWebView で同じ式から導出されるので 1 個保持で十分。
    //   doFetch 開始時に必ずこの値か request.userAgent のどちらかで明示的に設定し直すので、
    //   前回 override の影響が残らない。
    @Volatile private var defaultUserAgent: String = ""

    // [2026-06-29] AI モード (udm=50) 待機ゲート用パラメータ。AI 回答描画を待つ。
    //   本文 (innerText) がこの長さ以下なら「まだ AI 回答が生成中」とみなす。
    private val AI_MODE_MIN_BODY_LEN = 200
    //   再判定の最大回数 × 間隔 = 最大待機時間 (6 × 2500ms = 15s)。fetch の timeout が全体上限。
    private val MAX_AI_MODE_REDETECTS = 6
    private val AI_MODE_REDETECT_INTERVAL_MS = 2500L

    // [2026-06-26] 同一ホストへの最小リクエスト間隔 throttle 用。
    //   nextAllowedAtByHost[host] = 「次に fetch を開始してよい時刻 (ms)」
    //   並列で来ても synchronized(throttleLock) 内で順序付けされ、
    //   同じ host への 2 件目以降は minRequestIntervalMs ずつ後ろにシフトされる。
    private val throttleLock = Any()
    private val nextAllowedAtByHost = ConcurrentHashMap<String, Long>()
    // [2026-05-18] maybeReplenish の多重実行ガード
    private val replenishing = AtomicBoolean(false)
    // [2026-05-18] destroy 後の release / replace / replenish を無効化するフラグ
    @Volatile private var closed = false

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
                // [2026-06-28] UA ローテーション ON 時はスロット index で UA を固定 (slot 0..poolSize-1)
                val wv = createWebView(slotIndex = i)
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

    // [2026-06-28] UA ローテーション用の Android Chrome 系 UA プール。
    //   Codex 指摘: macOS / iPhone UA は Sec-CH-UA-Mobile や WebView fingerprint と不整合
    //   になるため Phase 1 では Android Chrome 系のみに絞る。
    //   各 WebView (slot) ごとに UA_POOL[slot % size] を固定割り当て。
    private val UA_POOL = listOf(
        "Mozilla/5.0 (Linux; Android 14; SM-S928U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; SM-A546B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    )
    // [2026-06-28] 各 WebView の slot index を保持。slot 0..poolSize-1 で固定。
    //   UA は `UA_POOL[slot % size]` で都度導出 (Codex 指摘: UA 文字列逆算は脆いので slot を直接保存)。
    //   init / replaceWebView / maybeReplenish で割り当て、destroy / onLowMemory / replaceWebView で remove。
    private val webViewSlots = java.util.concurrent.ConcurrentHashMap<WebView, Int>()

    // [2026-06-28] navigator.* 整合: addDocumentStartJavaScript で登録した ScriptHandler を保持。
    //   doFetch ごとに ON/OFF / UA を再評価し、必要なら登録 / 解除を切り替える。
    //   未対応端末 (WebViewFeature.DOCUMENT_START_SCRIPT 不可) では map は使われない。
    private val navigatorOverrideHandlers =
        java.util.concurrent.ConcurrentHashMap<WebView, androidx.webkit.ScriptHandler>()

    // [2026-06-28 緊急修正] 1.2.45 で crash の真因: shouldInterceptRequest 内で
    //   wv.settings.userAgentString を呼ぶと "WebView method called on thread 'ThreadPoolForeg'"
    //   例外で process 死亡。shouldInterceptRequest は worker thread で呼ばれるため WebView API
    //   全般を触れない。doFetch で UA を設定した直後に map に記録しておき、shouldInterceptRequest
    //   は map から読むことで WebView API 呼び出しを回避する。
    //   destroy / replaceWebView / onLowMemory で cleanup する (他の map と同じパターン)。
    private val currentUserAgents = java.util.concurrent.ConcurrentHashMap<WebView, String>()
    // [2026-06-28] navigator override 用 JS (Codex 指摘で minimal 化: languages + language のみ)。
    //   Android Chrome の通常端末挙動に揃える (日本ユーザー想定)。
    //   onPageStarted の evaluateJavascript より確実な addDocumentStartJavaScript で挿入。
    private val navigatorOverrideJs = """
        (function(){
          try {
            Object.defineProperty(navigator, 'languages', {get: () => ['ja-JP','ja']});
            Object.defineProperty(navigator, 'language', {get: () => 'ja-JP'});
          } catch(e) {}
        })();
    """.trimIndent()
    // [2026-06-28] addDocumentStartJavaScript の allowed origins (* で全 origin に inject)
    private val navigatorOverrideOrigins = setOf("*")

    /** Android Chrome 系 UA か簡易判定 (mobile Chrome のみ true。デスクトップ Chrome / Safari / iOS は false) */
    private fun isAndroidChromeUa(ua: String): Boolean {
        val lower = ua.lowercase()
        return lower.contains("android") && lower.contains("chrome") && lower.contains("mobile")
    }

    /** [Phase 2e] Google 検索 URL か判定 (host = google.com / co.jp、path = /search で始まる) */
    private fun isGoogleSearchUrl(url: String): Boolean {
        return try {
            val u = java.net.URI(url)
            val host = u.host?.lowercase() ?: return false
            val path = u.path?.lowercase() ?: return false
            val isGoogleHost = host == "google.com" || host == "google.co.jp" ||
                host == "www.google.com" || host == "www.google.co.jp"
            isGoogleHost && path.startsWith("/search")
        } catch (_: Exception) { false }
    }

    /**
     * [2026-06-28 Phase 2e] Google 検索 main frame を Cronet で fetch し
     * WebView.loadDataWithBaseURL で流し込む。Cronet を background thread で実行し
     * 完了時に main thread post で loadDataWithBaseURL or fallback loadUrl。
     *
     * 制約 (Codex 設計レビュー反映済):
     * - 対象は Google 検索 GET https のみ (呼び出し側で確認済)
     * - finalUrl を baseUrl/historyUrl に使う (redirect 後の URL 基準で subresource 解決)
     * - redirect 中 + 最終の Set-Cookie をすべて CookieManager に同期 + flush()
     * - 4xx 以上は CircuitBreaker.recordFailure するが HTML があれば表示する
     * - Cronet 失敗 (null) / 例外時は通常 WebView loadUrl にフォールバック (回帰防止)
     */
    private fun runCronetMainFrameFetch(
        wv: WebView,
        request: FetchRequest,
        effectiveUa: String,
        extraHeaders: Map<String, String>,
        workerId: Int,
        settled: AtomicBoolean,
        lastHttpStatus: java.util.concurrent.atomic.AtomicInteger,
    ) {
        Thread {
            try {
                if (settled.get()) return@Thread  // [Codex] 外側 timeout 後の遅延実行を無効化
                val cookieHeader = try {
                    CookieManager.getInstance().getCookie(request.url)
                } catch (_: Exception) { null }
                val headers = mutableMapOf<String, String>()
                headers["User-Agent"] = effectiveUa
                extraHeaders.forEach { (k, v) -> headers[k] = v }  // Accept-Language 等
                if (!cookieHeader.isNullOrBlank()) headers["Cookie"] = cookieHeader

                // [Codex] timeout 予算分配: Cronet fetch は外側 timeout - wait - 2 秒で打ち切る
                //   (loadDataWithBaseURL → onPageFinished → wait → humanize → extract の時間を残す)
                //   最低 5s、最大 15s に丸める
                val cronetTimeout = maxOf(5, minOf(15, request.timeout - request.wait - 2))

                val response = CronetManager.interceptFetch(
                    url = request.url,
                    method = "GET",
                    requestHeaders = headers,
                    timeoutSeconds = cronetTimeout,
                )
                if (settled.get()) return@Thread  // [Codex] Cronet 完了時にも settled 再確認
                if (response != null) {
                    // [Codex] Cronet response の status を FetchResult.httpStatus に反映する
                    //   (loadDataWithBaseURL 経路では WebView の onReceivedHttpError が呼ばれない)
                    lastHttpStatus.set(response.statusCode)
                    // Cookie 同期: redirect 中 → 最終 URL の順
                    response.redirectSetCookies.forEach { (urlAtTime, sc) ->
                        try { CookieManager.getInstance().setCookie(urlAtTime, sc) } catch (_: Exception) {}
                    }
                    response.setCookies.forEach { sc ->
                        try { CookieManager.getInstance().setCookie(response.finalUrl, sc) } catch (_: Exception) {}
                    }
                    try { CookieManager.getInstance().flush() } catch (_: Exception) {}

                    // [Codex] CircuitBreaker.recordFailure は 403 のみ (既存 WebView 経路と整合)。
                    //   404/500 で trip は過剰、429 は challenge 経路で別途扱う。
                    if (response.statusCode == 403) {
                        val host = try { java.net.URI(response.finalUrl).host?.lowercase() } catch (_: Exception) { null }
                        val isGoogle = host != null && (
                            host == "google.com" || host.endsWith(".google.com") ||
                            host == "google.co.jp" || host.endsWith(".google.co.jp")
                        )
                        if (isGoogle) {
                            try {
                                GoogleSearchCircuitBreaker.recordFailure(context, host!!)
                                val remaining = GoogleSearchCircuitBreaker.remainingTripMs(host)
                                if (remaining > 0) {
                                    onLog("Cronet main frame: circuit tripped (HTTP 403) host=$host trip=${remaining / 1000}s")
                                } else {
                                    onLog("Cronet main frame: $host HTTP 403 → recordFailure (worker=$workerId)")
                                }
                            } catch (_: Throwable) {}
                        }
                    }

                    // HTML を WebView に流し込む (main thread)
                    val charset = try {
                        java.nio.charset.Charset.forName(response.encoding ?: "UTF-8")
                    } catch (_: Exception) { Charsets.UTF_8 }
                    val htmlBody = try { String(response.body, charset) } catch (_: Exception) {
                        String(response.body, Charsets.UTF_8)
                    }
                    onLog("Cronet main frame fetch: url=${request.url} status=${response.statusCode} body=${response.body.size}B finalUrl=${response.finalUrl} (worker=$workerId)")
                    mainHandler.post {
                        if (settled.get()) return@post  // [Codex] post 内でも settled 再確認
                        try {
                            wv.loadDataWithBaseURL(
                                response.finalUrl,
                                htmlBody,
                                response.mimeType ?: "text/html",
                                response.encoding ?: "UTF-8",
                                response.finalUrl
                            )
                        } catch (e: Throwable) {
                            onLog("Cronet main frame: loadDataWithBaseURL 例外 → fallback: ${e.message}")
                            try {
                                if (extraHeaders.isEmpty()) wv.loadUrl(request.url)
                                else wv.loadUrl(request.url, extraHeaders)
                            } catch (_: Throwable) {}
                        }
                    }
                } else {
                    onLog("Cronet main frame fetch 失敗 → WebView loadUrl fallback: url=${request.url} (worker=$workerId)")
                    mainHandler.post {
                        if (settled.get()) return@post
                        if (extraHeaders.isEmpty()) wv.loadUrl(request.url)
                        else wv.loadUrl(request.url, extraHeaders)
                    }
                }
            } catch (e: Throwable) {
                onLog("Cronet main frame fetch 例外 → fallback: ${e.message} (worker=$workerId)")
                mainHandler.post {
                    if (settled.get()) return@post
                    try {
                        if (extraHeaders.isEmpty()) wv.loadUrl(request.url)
                        else wv.loadUrl(request.url, extraHeaders)
                    } catch (_: Throwable) {}
                }
            }
        }.start()
    }

    /**
     * [2026-06-28] navigator.* 整合の script handler を同期する。
     *   - 設定 ON + Android Chrome UA + DOCUMENT_START_SCRIPT サポート時のみ登録
     *   - それ以外 (設定 OFF / デスクトップ UA / 未対応端末) は既存 handler を解除
     *   - loadUrl の直前で都度呼び出し、状態を一致させる (再起動不要)
     *
     * [2026-06-28 緊急修正] WebViewCompat.addDocumentStartJavaScript は UI thread 必須。
     *   doFetch (worker thread) から直接呼ぶと crash する。mainHandler.post で wrap。
     *   さらに try-catch で何が起きても process crash しない安全策 + 例外時は
     *   navigatorOverride を強制 OFF にして再発防止 (起動復旧可能にする)。
     */
    private fun syncNavigatorOverride(wv: WebView, effectiveUa: String) {
        val enabled = try {
            jp.salesnow.chromebridge.data.SettingsRepository(context).navigatorOverride
        } catch (_: Exception) { false }
        val androidChrome = isAndroidChromeUa(effectiveUa)
        val featureSupported = try {
            androidx.webkit.WebViewFeature
                .isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)
        } catch (_: Throwable) { false }
        val shouldEnable = enabled && androidChrome && featureSupported
        // [緊急修正] WebViewCompat.addDocumentStartJavaScript は main thread 必須
        mainHandler.post {
            try {
                val existing = navigatorOverrideHandlers[wv]
                if (shouldEnable && existing == null) {
                    val handler = androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                        wv, navigatorOverrideJs, navigatorOverrideOrigins
                    )
                    navigatorOverrideHandlers[wv] = handler
                    onLog("navigator override: 登録 ua=${effectiveUa.take(50)}...")
                } else if (!shouldEnable && existing != null) {
                    try { existing.remove() } catch (_: Exception) {}
                    navigatorOverrideHandlers.remove(wv)
                    onLog("navigator override: 解除")
                }
            } catch (e: Throwable) {
                // [緊急修正] 何が起きても process を落とさない + 設定を強制 OFF (再発防止)
                onLog("navigator override: 致命的エラー → 設定を強制 OFF します: ${e.message}")
                try {
                    jp.salesnow.chromebridge.data.SettingsRepository(context).navigatorOverride = false
                } catch (_: Throwable) {}
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(slotIndex: Int = -1): WebView {
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
            // [2026-06-20] リクエスト UA 未指定時に毎回戻すための初期値を記録（最初の 1 個で十分、全 WebView 同じ値）
            if (defaultUserAgent.isEmpty()) defaultUserAgent = settings.userAgentString
            // [2026-06-28] slot index を常に記録 (rotation OFF でも記録、rotation 切替時に
            //   再起動なしで参照しないので問題なし)
            if (slotIndex >= 0) {
                webViewSlots[this] = slotIndex
                val settingsRepo = jp.salesnow.chromebridge.data.SettingsRepository(context)
                if (settingsRepo.userAgentRotation) {
                    val ua = UA_POOL[slotIndex % UA_POOL.size]
                    settings.userAgentString = ua
                    onLog("UA rotation: slot=$slotIndex ua=${ua.take(80)}...")
                }
            }
        }
    }

    /**
     * WebView を1つ取得する。空きがなければ Semaphore で待機する。
     * [2026-05-18] poll した WebView がアイドル中にレンダラ死亡していたら
     * 置換してスキップし、改めて取得を試みる。
     * @param timeoutMs 取得待ちタイムアウト（ミリ秒）
     * @return WebView と workerId のペア。タイムアウト時は null。
     */
    fun acquire(timeoutMs: Long): Pair<WebView, Int>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            if (!semaphore.tryAcquire(remaining, TimeUnit.MILLISECONDS)) return null

            val wv = available.poll()
            if (wv == null) {
                // 本来到達しないが、安全のため Semaphore を返却
                semaphore.release()
                return null
            }

            // [2026-05-18] アイドル中にレンダラが死んだ WebView はスキップして置換する。
            // 取得済み permit は replaceWebView へ引き継ぐ（ここでは release しない）。
            // 置換後の新 WebView 用 permit は replaceWebView が返却するので、
            // continue して改めて tryAcquire する。
            if (deadInstances.remove(wv)) {
                replaceWebView(wv)
                continue
            }

            val workerId = idCounter.incrementAndGet()
            return Pair(wv, workerId)
        }
    }

    /**
     * 使用済みの WebView をプールに返却する。
     */
    fun release(webView: WebView) {
        // [2026-05-18] 停止後は available に戻さず破棄する（破棄済みプールへの返却防止）
        if (closed) {
            mainHandler.post { try { webView.destroy() } catch (_: Exception) {} }
            return
        }
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
        // [2026-05-18] 欠損があれば非同期でプールを補充（トラフィック時に自己回復）
        maybeReplenish()

        // [2026-06-26] Google サーキットブレーカーが open なら即拒否（HTTP 層で 499 に変換）
        val urlHost = try { java.net.URI(request.url).host?.lowercase() } catch (_: Exception) { null }
        if (urlHost != null && GoogleSearchCircuitBreaker.isOpen(urlHost)) {
            val remainingMs = GoogleSearchCircuitBreaker.remainingTripMs(urlHost)
            // [2026-06-26] Codex 指摘: 切り捨てで 0 になりクライアントが即リトライしないよう ceil
            val remainingSec = ((remainingMs + 999) / 1000).coerceAtLeast(0)
            onLog("circuit open: host=$urlHost remaining=${remainingSec}s → 499")
            return FetchResult(
                ok = false, error = "circuit_open",
                message = "Google 検索のサーキットブレーカーが開いています。残り ${remainingSec} 秒",
                // [Codex 指摘 #2] origin 到達せず即拒否だが、trip の原因は origin 403 なので意味的に 403 を返す
                httpStatus = 403,
            )
        }

        // [2026-06-26] 同一ホストへの最小リクエスト間隔 throttle
        if (!applyHostThrottleIfNeeded(request.url)) {
            return FetchResult(
                ok = false, error = "interrupted",
                message = "throttle 待機中に割り込みを受けたため中断しました"
            )
        }

        val pair = acquire(acquireTimeoutMs)
            ?: return FetchResult(
                ok = false, error = "pool_busy",
                message = "WebView プールが空きません（${acquireTimeoutMs / 1000}秒待機）"
            )

        val (wv, workerId) = pair
        try {
            return doFetch(wv, request, workerId)
        } finally {
            // [2026-05-18] fetch 中にレンダラが死んでいたら置換、通常は返却。
            // どちらも保持 permit を 1 つだけ処理する（二重 release しない）。
            if (deadInstances.remove(wv)) replaceWebView(wv)
            else release(wv)
        }
    }

    // [2026-03-13] チャレンジ手動解除の追加待ち時間（秒）
    private val challengeExtraTimeout = 120L

    /**
     * [2026-06-26] 同一ホストに対する最小リクエスト間隔を強制する。
     *   - 設定値が 0 以下なら何もしない（従来挙動）
     *   - 複数並列で来ても synchronized 内で「次に開始してよい時刻」を順次後ろにずらすので、
     *     N 件来た場合は 0, +interval, +2*interval, ... の順に間隔が空く
     *   - 待機は Thread.sleep（fetch 呼び出しスレッドをブロック）。WebView 取得前に行うので
     *     プールの permit を消費しない＝他 host のリクエストは並列で進める
     */
    /** 戻り値: true=throttle 完了で fetch 続行可、false=中断で fetch を打ち切るべき */
    private fun applyHostThrottleIfNeeded(url: String): Boolean {
        val intervalMs = try {
            jp.salesnow.chromebridge.data.SettingsRepository(context).minRequestIntervalMs
        } catch (_: Exception) { 0L }
        if (intervalMs <= 0L) return true
        val host = try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) { null } ?: return true
        // [2026-06-27] bot 検出回避: 規則的な間隔 (例: 6.0s 毎) を避けるため
        //   各リクエストの開始時刻に 0-1000ms の random jitter を加算する
        val jitterMs = (0..1000).random().toLong()
        val waitMs = synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val nextAllowed = nextAllowedAtByHost[host] ?: 0L
            val startAt = maxOf(nextAllowed, now) + jitterMs
            nextAllowedAtByHost[host] = startAt + intervalMs
            startAt - now
        }
        if (waitMs > 0L) {
            onLog("throttle: host=$host wait=${waitMs}ms (jitter=${jitterMs}ms)")
            try { Thread.sleep(waitMs) } catch (_: InterruptedException) {
                // [2026-06-26] Codex 指摘: ここで interrupt() を立てると後続の Semaphore.tryAcquire
                //   が InterruptedException を投げて fetch() 側でハンドルされないため、明示的に
                //   "中断" を呼び出し元に返して FetchResult(error="interrupted") に倒す。
                return false
            }
        }
        return true
    }

    private fun doFetch(wv: WebView, request: FetchRequest, workerId: Int): FetchResult {
        val latch = CountDownLatch(1)
        var jsResult: String? = null
        var pageError: String? = null
        val challengeDetected = AtomicBoolean(false)
        val challengeShown = AtomicBoolean(false)
        // [2026-06-27] displayMode == ALL の場合は challenge 検知前から ChallengeActivity を
        //   起動して WebView を可視化する（運用上「Bridge が今何を取りに行ってるか」を見せる）。
        //   抽出完了 / タイムアウト時の dismiss 判定で challengeShown と OR する。
        val alwaysShown = AtomicBoolean(false)
        // [2026-06-27] WebViewClient.onReceivedHttpError で捕捉した HTTP status code。
        //   メインフレームのみ反映。デフォルト 200 (= エラーが起きなかった想定)。
        val lastHttpStatus = java.util.concurrent.atomic.AtomicInteger(200)
        // [2026-06-27] Google /search の SPA 初期 HTML 段階で isGoogleSearchBlocked 単独判定された
        //   ケース (本文 bodyLen <= 200) は SPA レンダリング待ちのため 3s 後に再 detect する。
        //   一度しか scheduled しないようフラグを保持。
        val googleSerpRedetectScheduled = AtomicBoolean(false)
        // [2026-06-29] AI モード (udm=50) は AI 回答描画が遅く本文が育つまで時間がかかる。
        //   bodyLen<=200 でも challenge 化せず、最大 MAX_AI_MODE_REDETECTS 回まで再判定して待つ。
        //   fetch 単位で初期化 (WebView 再利用時に状態が残らないよう doFetch スコープに置く)。
        val aiModeRedetectCount = java.util.concurrent.atomic.AtomicInteger(0)
        // [2026-06-27] Google origin 403 を本文ベースで検知したら true。
        //   challenge 経路にも入っている可能性があるので、最終 return 直前に 499 へ倒す。
        val googleOrigin403Detected = AtomicBoolean(false)
        // [2026-05-18] fetch 確定後に遅延コールバックが古い WebView を触らないためのフラグ
        val settled = AtomicBoolean(false)
        // [2026-04-12] チャレンジ計測用
        var challengeStartMs = 0L
        val challengeDomain = try {
            java.net.URI(request.url).host ?: request.url
        } catch (_: Exception) { request.url }

        mainHandler.post {
            wv.webViewClient = object : WebViewClient() {
                private var extracted = false

                // [2026-06-25] タイトル + URL + reCAPTCHA iframe + 本文長の複合判定。
                //   evaluateJavascript の callback には JSON.stringify(...) の戻り値が
                //   さらに文字列リテラルとして来るため、二段 parse する。
                //   parse 失敗時は challenge 判定を skip して通常抽出に進む。
                // [2026-06-27] bodySample (先頭 500 文字) を含む。Google origin 403 を本文ベースで
                //   検知して即 499 経路に倒すため (1.2.34 相当)。
                private val detectJs = "JSON.stringify({" +
                    "title: document.title||''," +
                    "url: location.href||''," +
                    "recaptcha: !!document.querySelector(" +
                        "'iframe[src*=\"recaptcha\"],iframe[src*=\"google.com/recaptcha\"]')," +
                    "bodyLen: (document.body && document.body.innerText || '').trim().length," +
                    "bodySample: (document.body && document.body.innerText || '').substring(0, 500)" +
                    "})"

                // [2026-06-27] humanize: 抽出前にダミーのマウス操作と画面スクロールを実行。
                //   bot 検出の典型シグナル「ページ開いて即抽出する自動化パターン」を緩和。
                //   - mousemove/mouseover を 2 箇所 (ランダム座標 + 200ms 後に別座標) で発火
                //   - window.scrollTo (smooth) で下に N px → 600ms 後に少し戻す
                //   ChallengeActivity / AlwaysShow モード時には画面で scroll が視覚的に見える。
                private val humanizeJs = """
                    (function(){
                      try {
                        var w = window.innerWidth || 1000;
                        var h = window.innerHeight || 600;
                        var x1 = Math.floor(Math.random() * w);
                        var y1 = Math.floor(Math.random() * h);
                        ['mousemove','mouseover'].forEach(function(t){
                          document.dispatchEvent(new MouseEvent(t,{clientX:x1,clientY:y1,bubbles:true,cancelable:true}));
                        });
                        setTimeout(function(){
                          var x2 = Math.floor(Math.random() * w);
                          var y2 = Math.floor(Math.random() * h);
                          ['mousemove','mouseover'].forEach(function(t){
                            document.dispatchEvent(new MouseEvent(t,{clientX:x2,clientY:y2,bubbles:true,cancelable:true}));
                          });
                        }, 200);
                        var sy = Math.floor(Math.random() * 300) + 250;
                        window.scrollTo({top: sy, behavior: 'smooth'});
                        setTimeout(function(){
                          window.scrollTo({top: Math.floor(sy * 0.3), behavior: 'smooth'});
                        }, 600);
                        return 'humanize ok mouse=('+x1+','+y1+') scroll='+sy;
                      } catch(e) { return 'humanize err: '+e.message; }
                    })()
                """.trimIndent()

                // [2026-06-27] detect 処理を関数化。SPA 初期 HTML 段階の誤検知を防ぐため再 detect を可能に。
                val runDetect: () -> Unit = run@{
                    if (extracted || settled.get()) return@run
                    wv.evaluateJavascript(detectJs) { jsonRaw ->
                        if (extracted || settled.get()) return@evaluateJavascript
                        var title = ""
                        var pageUrl = ""
                        var hasRecaptcha = false
                        var bodyLen = -1
                        var bodySample = ""
                        var parsedOk = false
                        try {
                            val unwrapped = com.google.gson.JsonParser.parseString(jsonRaw).asString
                            val obj = com.google.gson.JsonParser.parseString(unwrapped).asJsonObject
                            title = obj.get("title")?.asString ?: ""
                            pageUrl = obj.get("url")?.asString ?: ""
                            hasRecaptcha = obj.get("recaptcha")?.asBoolean ?: false
                            bodyLen = obj.get("bodyLen")?.asInt ?: -1
                            bodySample = obj.get("bodySample")?.asString ?: ""
                            parsedOk = true
                        } catch (_: Exception) {
                            // parse 失敗時は challenge 判定を skip（通常抽出にフォールスルー）
                        }

                        // [2026-06-27] Google origin 403 を本文ベースで先に判定 (1.2.34 相当)。
                        //   challenge にも通常抽出にも入る前に CircuitBreaker.recordFailure して latch.countDown する。
                        //   doFetch 末尾で googleOrigin403Detected を見て 499 を返す。
                        if (parsedOk) {
                            val reqHost = try { java.net.URI(pageUrl).host?.lowercase() } catch (_: Exception) { null }
                            if (ChallengeManager.isGoogleOrigin403(reqHost, bodySample)) {
                                googleOrigin403Detected.set(true)
                                extracted = true
                                reqHost?.let {
                                    GoogleSearchCircuitBreaker.recordFailure(context, it)
                                    val remaining = GoogleSearchCircuitBreaker.remainingTripMs(it)
                                    if (remaining > 0) {
                                        onLog("circuit tripped (origin 403, detect path): host=$it trip=${remaining / 1000}s")
                                    } else {
                                        onLog("origin 403 detected (detect path): host=$it → 499 即返却 (worker=$workerId)")
                                    }
                                }
                                latch.countDown()
                                return@evaluateJavascript
                            }
                        }

                        // [2026-06-29] AI モード (udm=50) 待機ゲート。
                        //   Codex 指摘: 強い challenge シグナル (recaptcha / sorry / challenge title) を
                        //   先に通し、それらが無い「AI 回答が未生成なだけ」のケースだけ待機する。
                        //   これらの強いシグナルは下の isChallenge で即 challenge 経路に入る。
                        if (parsedOk) {
                            val lowerPageUrl = pageUrl.lowercase()
                            val strongChallenge = ChallengeManager.isChallengeTitle(title) ||
                                lowerPageUrl.contains("google.com/sorry") ||
                                lowerPageUrl.contains("google.co.jp/sorry") ||
                                hasRecaptcha ||
                                // [Codex] HTTP 429 / 既に challenge 検知済みなら AI 待機で握り潰さず即 challenge へ
                                challengeDetected.get() ||
                                lastHttpStatus.get() == 429
                            if (!strongChallenge &&
                                ChallengeManager.isAiModeSearch(pageUrl) &&
                                bodyLen in 0..AI_MODE_MIN_BODY_LEN &&
                                aiModeRedetectCount.getAndIncrement() < MAX_AI_MODE_REDETECTS
                            ) {
                                onLog("AI モード (udm=50) 本文未生成 bodyLen=$bodyLen → ${AI_MODE_REDETECT_INTERVAL_MS}ms 後再判定 (試行 ${aiModeRedetectCount.get()}/$MAX_AI_MODE_REDETECTS, worker=$workerId)")
                                mainHandler.postDelayed({ runDetect() }, AI_MODE_REDETECT_INTERVAL_MS)
                                return@evaluateJavascript
                            }
                        }

                        if (parsedOk && ChallengeManager.isChallenge(title, pageUrl, hasRecaptcha, bodyLen)) {
                            // [2026-06-27] Google /search で isGoogleSearchBlocked 単独判定された場合は
                            //   SPA 初期 HTML 段階の誤検知の可能性が高い (loading→通常 SERP に化ける)。
                            //   3 秒待って再 detect。reCAPTCHA / sorry / title マッチがあれば本物 challenge
                            //   なのでこの分岐には入らず即起動する (1.2.34 相当)。
                            val lowerUrl = pageUrl.lowercase()
                            val isGoogleBlocked = ChallengeManager.isGoogleSearchBlocked(pageUrl, bodyLen)
                            val isOnlyGoogleSearchBlocked = isGoogleBlocked &&
                                !ChallengeManager.isChallengeTitle(title) &&
                                !lowerUrl.contains("google.com/sorry") &&
                                !lowerUrl.contains("google.co.jp/sorry") &&
                                !hasRecaptcha
                            if (isOnlyGoogleSearchBlocked && !googleSerpRedetectScheduled.getAndSet(true)) {
                                onLog("Google /search 初期 HTML 段階の可能性 → 3s 待機して再判定 (bodyLen=$bodyLen, worker=$workerId)")
                                mainHandler.postDelayed({ runDetect() }, 3000L)
                                return@evaluateJavascript
                            }
                            // チャレンジページ検知 → ユーザーに画面を表示
                            challengeDetected.set(true)
                            if (!challengeShown.getAndSet(true)) {
                                challengeStartMs = System.currentTimeMillis()
                                val kind = when {
                                    lowerUrl.contains("google.com/sorry") ||
                                        lowerUrl.contains("google.co.jp/sorry") -> "Google sorry"
                                    isGoogleBlocked -> "Google blocked SERP"
                                    hasRecaptcha -> "reCAPTCHA iframe"
                                    else -> "title"
                                }
                                onLog("チャレンジ検知 [$kind]: domain=$challengeDomain title=\"$title\" url=\"$pageUrl\" (worker=$workerId)")
                                // [2026-06-26] Google 検索の極小レスポンス (403 等) はサーキットブレーカーに記録。
                                //   N 回 / 5 分で trip し、以降の fetch は 499 で即拒否される。
                                // [2026-06-27] reCAPTCHA が出ているケースは「手動で突破可能 = 一過性」なので
                                //   circuit には記録しない。純粋な 403 ページ（reCAPTCHA なし）だけ記録対象。
                                if (isGoogleBlocked && !hasRecaptcha) {
                                    val gHost = try { java.net.URI(pageUrl).host?.lowercase() } catch (_: Exception) { null }
                                    if (gHost != null) {
                                        GoogleSearchCircuitBreaker.recordFailure(context, gHost)
                                        val remaining = GoogleSearchCircuitBreaker.remainingTripMs(gHost)
                                        if (remaining > 0) {
                                            onLog("circuit tripped: host=$gHost trip=${remaining / 1000}s")
                                        }
                                    }
                                }
                                val shown = ChallengeManager.show(context, wv, request.purpose)
                                if (!shown) {
                                    // [2026-06-26] 自動タップ専用モード + memory なしで画面起動を
                                    //   拒否されたケース。手動操作が来ない前提なので challenge 経路を
                                    //   閉じ、通常タイムアウト経路に倒す（challengeExtraTimeout 待ちしない）。
                                    challengeDetected.set(false)
                                    challengeShown.set(false)
                                    onLog("チャレンジ画面起動 拒否（自動タップ memory 無し）: domain=$challengeDomain (worker=$workerId)")
                                }
                            }
                            // latch は countDown しない → ユーザーの操作を待つ
                            //   ただし shown=false の場合は通常タイムアウト後に処理される
                        } else {
                            // 通常ページ → コンテンツ抽出
                            extracted = true
                            // [2026-06-27] AlwaysShow モード時は humanize 後に dismiss
                            //   = ユーザーが画面で humanize の scroll を視覚的に確認できるようにする
                            val wasAlwaysShown = alwaysShown.get()
                            // チャレンジ画面 (= challenge 検知由来) は即 dismiss (reCAPTCHA 操作後すぐ閉じる)
                            if (challengeShown.get()) {
                                val elapsed = System.currentTimeMillis() - challengeStartMs
                                onLog("チャレンジ成功: domain=$challengeDomain ${elapsed}ms (worker=$workerId)")
                                onChallengeResult(true)
                                // [2026-06-10] Codex#4: 自分の WebView を明示指定して dismiss
                                mainHandler.post { ChallengeManager.dismiss(wv) }
                            }
                            // ※ AlwaysShow の dismiss は humanize callback 内で行う (画面で scroll を見せるため)
                            mainHandler.postDelayed({
                                // [2026-05-18] fetch が既に確定していたら古い WebView を触らない
                                if (settled.get()) return@postDelayed
                                // [2026-06-27] humanize: 抽出前にダミーのマウス操作とスクロールを実行
                                //   bot 検出のシグナル (=「ページ開いて即抽出する」自動化パターン) を緩和。
                                //   challenge 検知時には到達しない (challenge 経路は別)。
                                //   AlwaysShow モード時には画面に WebView が attach 済なので、
                                //   smooth scroll が視覚的に確認可能 (このため AlwaysShow の dismiss は
                                //   humanize 後に遅延させる)。
                                wv.evaluateJavascript(humanizeJs) { humanizeResult ->
                                    if (settled.get()) return@evaluateJavascript
                                    onLog("humanize 実行: $humanizeResult (worker=$workerId)")
                                    // AlwaysShow モード用の遅延 dismiss (humanize の smooth scroll 完了後)
                                    if (wasAlwaysShown) {
                                        mainHandler.postDelayed({
                                            mainHandler.post { ChallengeManager.dismiss(wv) }
                                        }, 1300L)  // scroll smooth (600ms) + 戻し (600ms) + 余裕 100ms
                                    }
                                    // humanize 完了後 700ms 待って extract (smooth scroll の完了待ち)
                                    mainHandler.postDelayed({
                                        if (settled.get()) return@postDelayed
                                        val js = when (request.mode) {
                                            "dom"  -> JsExtractors.EXTRACT_DOM
                                            "html" -> JsExtractors.extractHtml(request.maxLength)
                                            else   -> JsExtractors.EXTRACT_TEXT
                                        }
                                        wv.evaluateJavascript(js) { result ->
                                            if (settled.get()) return@evaluateJavascript
                                            jsResult = result
                                            latch.countDown()
                                        }
                                    }, 700L)
                                }
                            }, request.wait * 1000L)
                        }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (extracted || settled.get()) return
                    if (url == null || url == "about:blank") return
                    runDetect()
                }

                override fun onReceivedError(
                    view: WebView?, errorCode: Int,
                    description: String?, failingUrl: String?
                ) {
                    pageError = "WebView エラー: $description (code=$errorCode, worker=$workerId)"
                    latch.countDown()
                }

                // [2026-06-27] HTTP layer で status code を捕捉。メインフレームのみ反映。
                //   FetchResult.httpStatus / http.log / JSON http_status に記録。
                //   Google host 限定で:
                //     - status=403 → 完全ブロック扱い → CircuitBreaker.recordFailure + 即 499 (extracted=true / latch.countDown)
                //     - status=429 → sorry リダイレクト (レート制限) 扱い → ChallengeActivity 起動 (手動 reCAPTCHA 突破可能)
                //     - 200 は誤認しないため何もしない (通常 SERP)
                override fun onReceivedHttpError(
                    view: WebView?,
                    httpReq: android.webkit.WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    // [Codex 指摘 #1] settled 後の遅延 callback で次 fetch の lastHttpStatus を汚染しないよう先に return
                    if (settled.get()) return
                    if (httpReq == null || errorResponse == null) return
                    if (!httpReq.isForMainFrame) return
                    val statusCode = errorResponse.statusCode
                    lastHttpStatus.set(statusCode)
                    val reqHost = try { httpReq.url.host?.lowercase() } catch (_: Exception) { null }
                    val isGoogle = reqHost != null && (
                        reqHost == "google.com" || reqHost.endsWith(".google.com") ||
                        reqHost == "google.co.jp" || reqHost.endsWith(".google.co.jp")
                    )
                    onLog("HTTP error: status=$statusCode host=$reqHost url=${httpReq.url} (worker=$workerId)")
                    if (!isGoogle || extracted) return
                    when (statusCode) {
                        // [2026-06-27] HTTP 403 = Google 完全ブロック (reCAPTCHA すら出ない)
                        //   → 即 CircuitBreaker.recordFailure + extracted/latch.countDown で 499 返却
                        //   doFetch 末尾の googleOrigin403Detected 経路で circuit_open エラーになる
                        403 -> {
                            googleOrigin403Detected.set(true)
                            extracted = true
                            reqHost?.let {
                                GoogleSearchCircuitBreaker.recordFailure(context, it)
                                val remaining = GoogleSearchCircuitBreaker.remainingTripMs(it)
                                if (remaining > 0) {
                                    onLog("circuit tripped (HTTP 403): host=$it trip=${remaining / 1000}s")
                                } else {
                                    onLog("HTTP 403 detected: host=$it → 499 即返却 + CircuitBreaker.recordFailure (worker=$workerId)")
                                }
                            }
                            if (challengeShown.get() || alwaysShown.get()) {
                                mainHandler.post { ChallengeManager.dismiss(wv) }
                            }
                            latch.countDown()
                        }
                        // [2026-06-27] HTTP 429 = Google レート制限 (sorry リダイレクト)
                        //   → ChallengeActivity 起動して手動 reCAPTCHA 突破。recordFailure は呼ばない
                        //   (手動突破で回復可能なので CircuitBreaker trip は過剰)
                        429 -> {
                            onLog("HTTP 429 detected: host=$reqHost → ChallengeActivity 起動 (worker=$workerId)")
                            challengeDetected.set(true)
                            if (!challengeShown.getAndSet(true)) {
                                challengeStartMs = System.currentTimeMillis()
                                mainHandler.post {
                                    val shown = ChallengeManager.show(context, wv, request.purpose)
                                    if (!shown) {
                                        challengeDetected.set(false)
                                        challengeShown.set(false)
                                        onLog("HTTP 429 経路: チャレンジ画面起動拒否（自動タップ memory 無し）(worker=$workerId)")
                                    }
                                }
                            }
                        }
                    }
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

                // [2026-06-28] Phase 2b: Cronet で subresource (GET/HEAD) を intercept。
                //   トグル ON 時のみ。Main frame / POST / Range / 非 https は WebView default に戻す。
                //   Cookie 同期 / redirect は Cronet 内で自動 follow。エラー時は null で WebView fallback。
                override fun shouldInterceptRequest(
                    view: WebView?,
                    httpReq: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    if (httpReq == null) return null
                    val cronetEnabled = try {
                        jp.salesnow.chromebridge.data.SettingsRepository(context).cronetIntercept
                    } catch (_: Exception) { false }
                    if (!cronetEnabled) return null
                    // 除外条件: Phase 2b は subresource GET/HEAD のみ。それ以外は WebView default に倒す。
                    if (httpReq.isForMainFrame) return null
                    val method = httpReq.method?.uppercase() ?: "GET"
                    if (method != "GET" && method != "HEAD") return null
                    val urlStr = httpReq.url?.toString() ?: return null
                    if (!urlStr.startsWith("https://")) return null
                    // Range request は Cronet で扱わない (動画 / 部分取得 / 再開系)
                    if (httpReq.requestHeaders?.keys?.any { it.equals("Range", ignoreCase = true) } == true) return null
                    // Cookie 注入
                    val cookieHeader = try {
                        CookieManager.getInstance().getCookie(urlStr)
                    } catch (_: Exception) { null }
                    val headers = httpReq.requestHeaders.toMutableMap()
                    if (!cookieHeader.isNullOrBlank()) headers["Cookie"] = cookieHeader
                    // [2026-06-28] navigator 整合 ON + Android Chrome UA のときだけ
                    //   Accept-Language を Cronet subresource にも付与 (main frame と整合)。
                    //   Codex 指摘: UA 判定なしだとデスクトップ UA でも付与してしまう
                    // [2026-06-28 緊急修正] wv.settings.userAgentString は worker thread から触れない
                    //   ため、doFetch で記録した currentUserAgents map から読む。
                    val navOverride = try {
                        jp.salesnow.chromebridge.data.SettingsRepository(context).navigatorOverride
                    } catch (_: Exception) { false }
                    val currentUa = currentUserAgents[wv] ?: ""
                    if (navOverride && isAndroidChromeUa(currentUa) &&
                        !headers.keys.any { it.equals("Accept-Language", ignoreCase = true) }) {
                        headers["Accept-Language"] = "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7"
                    }
                    // [2026-06-29 Phase 2d v2] streamingFetch への opt-in を強化:
                    //   1. Accept: text/event-stream (SSE 確実)
                    //   2. Sec-Fetch-Dest: empty (XHR/fetch — Chromium 慣習)
                    //   3. Google 関連 host で「非リソース系」(image/style/script/font/document 以外)
                    //      → AI Overview の動的 XHR を漏れなく拾うため、Google host は緩く判定
                    val acceptHeader = headers.entries.firstOrNull {
                        it.key.equals("Accept", ignoreCase = true)
                    }?.value?.lowercase() ?: ""
                    val secFetchDest = headers.entries.firstOrNull {
                        it.key.equals("Sec-Fetch-Dest", ignoreCase = true)
                    }?.value?.lowercase() ?: ""
                    val reqHost = try { httpReq.url?.host?.lowercase() } catch (_: Exception) { null }
                    val isGoogleHost = reqHost != null && (
                        reqHost == "google.com" || reqHost.endsWith(".google.com") ||
                        reqHost == "google.co.jp" || reqHost.endsWith(".google.co.jp") ||
                        reqHost.endsWith(".gstatic.com") || reqHost.endsWith(".googleapis.com")
                    )
                    // 「リソース系」(image/script/style/font/document) は body 全読みで OK
                    val resourceLikeDest = secFetchDest in setOf("image", "script", "style", "font", "document", "track", "video", "audio")
                    val useStreaming =
                        acceptHeader.contains("text/event-stream") ||
                        secFetchDest == "empty" ||
                        (isGoogleHost && !resourceLikeDest)
                    // 詳細ログ ON 時のみ判定経路を出力 (AI Overview 調査用)
                    val verbose = try {
                        jp.salesnow.chromebridge.data.SettingsRepository(context).verboseLog
                    } catch (_: Exception) { false }
                    if (verbose) {
                        onLog("intercept: ${if (useStreaming) "STREAM" else "BODY  "} ${reqHost ?: "?"}${try { httpReq.url?.path ?: "" } catch (_: Exception) { "" }} accept=${acceptHeader.take(40)} secFetchDest=$secFetchDest")
                    }
                    if (useStreaming) {
                        return CronetManager.streamingFetch(
                            url = urlStr,
                            method = method,
                            requestHeaders = headers,
                            headerTimeoutSeconds = 5,
                        )
                    }
                    val response = CronetManager.interceptFetch(
                        url = urlStr,
                        method = method,
                        requestHeaders = headers,
                        timeoutSeconds = 10,
                    ) ?: return null  // 失敗時 (3xx / OOM / timeout 等) は WebView default にフォールバック
                    // [Codex 指摘] 複数 Set-Cookie をすべて CookieManager に同期
                    response.setCookies.forEach { sc ->
                        try { CookieManager.getInstance().setCookie(urlStr, sc) } catch (_: Exception) {}
                    }
                    return android.webkit.WebResourceResponse(
                        response.mimeType,
                        response.encoding,
                        response.statusCode,
                        response.reasonPhrase,
                        response.headers,
                        java.io.ByteArrayInputStream(response.body),
                    )
                }

                // [2026-05-18] レンダラプロセスの異常終了（OOM/クラッシュ）を検知。
                // true を返すとアプリ側が WebView の破棄責任を持つ（replaceWebView で実施）。
                override fun onRenderProcessGone(
                    view: WebView?, detail: RenderProcessGoneDetail?
                ): Boolean {
                    deadInstances.add(wv)
                    settled.set(true)
                    val crashed = detail?.didCrash() == true
                    pageError = "WebView レンダラ異常終了 (worker=$workerId, crashed=$crashed)"
                    onLog(pageError!!)
                    if (challengeShown.get() || alwaysShown.get()) mainHandler.post { ChallengeManager.dismiss(wv) }
                    latch.countDown()
                    return true
                }
            }
            // [2026-06-20] リクエスト毎の User-Agent を適用。null/空文字なら createWebView 時の
            //   デフォルトに戻す（前回 override の影響を消す）。loadUrl の直前に必ず実行。
            // [2026-06-26] 優先順位: per-request UA > 設定 defaultUserAgentOverride > WebView 既定 UA。
            //   Google 等で `; wv` の付く Android WebView UA が flag されやすいため、設定でデスクトップ
            //   Chrome UA に差し替えてグローバルに適用できるようにする。
            // [2026-06-28] UA ローテーション ON 時は slot index で固定の UA を使う:
            //   per-request UA > 設定 defaultUserAgentOverride > rotated UA > WebView 既定 UA
            val requestedUa = request.userAgent?.takeIf { it.isNotBlank() }
            val configuredDefault = try {
                jp.salesnow.chromebridge.data.SettingsRepository(context)
                    .defaultUserAgentOverride.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            val rotatedUa = if (try { jp.salesnow.chromebridge.data.SettingsRepository(context).userAgentRotation } catch (_: Exception) { false }) {
                webViewSlots[wv]?.let { UA_POOL[it % UA_POOL.size] }
            } else null
            val effectiveUa = requestedUa ?: configuredDefault ?: rotatedUa ?: defaultUserAgent
            wv.settings.userAgentString = effectiveUa
            // [2026-06-28 緊急修正] shouldInterceptRequest (worker thread) から WebView API を
            //   触れないため、UA を map に保存する。shouldInterceptRequest 内ではこの map を読む。
            currentUserAgents[wv] = effectiveUa
            // [2026-06-28] navigator.* 整合: Android Chrome 系 UA のときだけ navigator override を
            //   適用 (デスクトップ UA だと platform 等が不整合)。
            //   addDocumentStartJavaScript で document load 前に inject (Codex 推奨: onPageStarted の
            //   evaluateJavascript より確実)。トグル ON/OFF / UA 切替に応じて script handler を同期。
            syncNavigatorOverride(wv, effectiveUa)
            // [2026-06-27] displayMode == ALL の場合、challenge 検知を待たずに最初から
            //   ChallengeActivity を表示して WebView を可視化する。
            try {
                val displayMode = jp.salesnow.chromebridge.data.SettingsRepository(context)
                    .challengeDisplayMode
                if (displayMode == jp.salesnow.chromebridge.data.SettingsRepository.DISPLAY_MODE_ALL) {
                    val shown = ChallengeManager.show(context, wv, request.purpose, alwaysShow = true)
                    if (shown) alwaysShown.set(true)
                }
            } catch (_: Exception) {
                // 設定読み込み失敗時は alwaysShow を諦めて通常 fetch を続行
            }
            // [2026-06-28] navigator.* 整合 ON 時は Accept-Language ヘッダーも main frame に付与
            //   (subresource は WebView default fetch or Cronet 経由で別途付与)
            val extraHeaders = if (try {
                jp.salesnow.chromebridge.data.SettingsRepository(context).navigatorOverride &&
                    isAndroidChromeUa(effectiveUa)
            } catch (_: Exception) { false }) {
                mapOf("Accept-Language" to "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            } else emptyMap()
            // [2026-06-28 Phase 2e] Google 検索 main frame を Cronet 経由で fetch する分岐。
            //   両トグル ON + Google 検索 GET + https + Cronet engine 利用可 のときだけ。
            //   それ以外は既存の WebView loadUrl 経路に倒す (回帰防止)。
            val useCronetMainFrame = try {
                val repo = jp.salesnow.chromebridge.data.SettingsRepository(context)
                repo.cronetIntercept && repo.cronetMainFrameIntercept
            } catch (_: Exception) { false }
                && request.url.startsWith("https://")
                && isGoogleSearchUrl(request.url)
                && CronetManager.engine != null
            if (useCronetMainFrame) {
                runCronetMainFrameFetch(wv, request, effectiveUa, extraHeaders, workerId, settled, lastHttpStatus)
            } else if (extraHeaders.isEmpty()) {
                wv.loadUrl(request.url)
            } else {
                wv.loadUrl(request.url, extraHeaders)
            }
        }

        // [2026-03-13] 通常タイムアウトで待機
        var completed = latch.await(request.timeout.toLong(), TimeUnit.SECONDS)

        // チャレンジが検知されていた場合、追加で待機（ユーザーの手動操作時間）
        if (!completed && challengeDetected.get()) {
            onLog("チャレンジ待機中: domain=$challengeDomain 追加${challengeExtraTimeout}秒 (worker=$workerId)")
            completed = latch.await(challengeExtraTimeout, TimeUnit.SECONDS)
        }

        // [2026-05-18] 以後の遅延コールバックを無効化（古い WebView 参照を防止）
        settled.set(true)

        // [2026-05-18] レンダラが死亡していた場合は専用エラーを返す（fetch() が置換する）
        if (deadInstances.contains(wv)) {
            return FetchResult(
                ok = false, error = "renderer_gone",
                message = pageError ?: "WebView レンダラが異常終了しました (worker=$workerId)",
                httpStatus = lastHttpStatus.get(),
            )
        }

        // タイムアウト時はチャレンジ画面 / 常時表示画面も閉じる
        if (!completed) {
            mainHandler.post {
                wv.stopLoading()
                // [2026-06-10] Codex#4: 自分の WebView を明示指定して dismiss
                if (challengeShown.get() || alwaysShown.get()) ChallengeManager.dismiss(wv)
            }
            val msg = if (challengeDetected.get()) {
                val elapsed = System.currentTimeMillis() - challengeStartMs
                onChallengeResult(false)
                "チャレンジ失敗（タイムアウト）: domain=$challengeDomain ${elapsed}ms (worker=$workerId)".also { onLog(it) }
            } else {
                "${request.timeout}秒でタイムアウトしました (worker=$workerId)"
            }
            return FetchResult(ok = false, error = "timeout", message = msg, httpStatus = lastHttpStatus.get())
        }

        if (pageError != null) {
            return FetchResult(ok = false, error = "fetch_failed", message = pageError, httpStatus = lastHttpStatus.get())
        }

        // [2026-06-27] detect フェーズで Google origin 403 を本文ベース検知した場合は即 499 返却 (1.2.34 相当)。
        if (googleOrigin403Detected.get()) {
            // [Codex 指摘] displayMode==ALL の場合は detect 前に ChallengeManager.show(alwaysShow=true)
            //   が走って WebView が Activity に attach 済みなので、明示的に dismiss する
            if (challengeShown.get() || alwaysShown.get()) {
                mainHandler.post { ChallengeManager.dismiss(wv) }
            }
            return FetchResult(
                ok = false,
                error = "circuit_open",
                message = "Google origin 403 を検知しました。bridge 単位で Google を停止中です。",
                httpStatus = if (lastHttpStatus.get() != 200) lastHttpStatus.get() else 403,
            )
        }

        val parsed = parseJsResult(jsResult, request)

        // [2026-06-27] 通常抽出後の最終フォールバック (detect フェーズで bodySample 500 文字に
        //   含まれなかった本文後半マッチを救済)。1.2.34 相当。
        if (parsed.ok) {
            val reqHost = try { java.net.URI(request.url).host?.lowercase() } catch (_: Exception) { null }
            if (ChallengeManager.isGoogleOrigin403(reqHost, parsed.text)) {
                reqHost?.let {
                    GoogleSearchCircuitBreaker.recordFailure(context, it)
                    val remaining = GoogleSearchCircuitBreaker.remainingTripMs(it)
                    if (remaining > 0) {
                        onLog("circuit tripped (origin 403, extract path): host=$it trip=${remaining / 1000}s")
                    } else {
                        onLog("origin 403 detected (extract path): host=$it → 499 即返却 (worker=$workerId)")
                    }
                }
                if (challengeShown.get() || alwaysShown.get()) {
                    mainHandler.post { ChallengeManager.dismiss(wv) }
                }
                return FetchResult(
                    ok = false,
                    error = "circuit_open",
                    message = "Google origin 403 を検知しました。bridge 単位で Google を停止中です。",
                    httpStatus = if (lastHttpStatus.get() != 200) lastHttpStatus.get() else 403,
                )
            }
        }

        return parsed.copy(httpStatus = lastHttpStatus.get())
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

            // [2026-06-30] mode="html": 生 HTML と iframe src 一覧。html は JS 側で maxLength 切り詰め済だが
            //   念のため Kotlin 側でも防御的に切り詰める。iframes は成功時は空でも配列 ([]) を返す。
            val htmlStr = if (request.mode == "html" && json.has("html")) {
                var h = json.get("html").asString
                if (h.length > request.maxLength) h = h.substring(0, request.maxLength)
                h
            } else null
            val iframeList = if (request.mode == "html" && json.has("iframes")) {
                json.getAsJsonArray("iframes").mapNotNull { runCatching { it.asString }.getOrNull() }
            } else null

            return FetchResult(
                ok = true, text = text, title = title,
                finalUrl = finalUrl, mode = request.mode, dom = domJson,
                html = htmlStr, iframes = iframeList
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
     * 最低1つは残す。permit を消費したまま allInstances からも削除するため
     * 不変条件（permit 数 == allInstances.size）は維持される。
     */
    fun onLowMemory() {
        var shrunk = 0
        while (available.size > 1) {
            val wv = available.poll() ?: break
            if (semaphore.tryAcquire()) {
                // [2026-06-28] Codex 指摘: destroyed WebView の slot map をクリアしないとリーク
                webViewSlots.remove(wv)
                navigatorOverrideHandlers.remove(wv)
                currentUserAgents.remove(wv)
                mainHandler.post {
                    try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                }
                synchronized(allInstances) { allInstances.remove(wv) }
                shrunk++
            } else {
                available.add(wv)
                break
            }
        }
        if (shrunk > 0) onLog("メモリ逼迫: WebView ${shrunk}個を破棄 (pool=$currentSize)")
    }

    /**
     * [2026-05-18] レンダラがクラッシュした WebView を破棄し、新しい WebView へ置換する。
     * 呼び出し側は対象 WebView 用の permit を 1 つ保持している前提。
     * 置換成功時のみ permit を返却し、不変条件を維持する。
     * 置換失敗時は permit を返さず（allInstances・permit ともに 1 減で整合）、
     * 後続の maybeReplenish が補充する。
     */
    private fun replaceWebView(dead: WebView) {
        // [2026-06-28] dead WebView の slot index を継承するため、消す前に webViewSlots から取得。
        val deadSlotIndex = webViewSlots.remove(dead) ?: -1
        navigatorOverrideHandlers.remove(dead)
        currentUserAgents.remove(dead)
        mainHandler.post {
            try { dead.stopLoading(); dead.destroy() } catch (_: Exception) {}
            synchronized(allInstances) { allInstances.remove(dead) }
            if (closed) return@post
            try {
                // [2026-06-28] UA rotation 有効時は dead の slot index で同じ UA を新規 WebView に割り当てる
                val fresh = createWebView(slotIndex = deadSlotIndex)
                CookieManager.getInstance().setAcceptThirdPartyCookies(fresh, true)
                synchronized(allInstances) { allInstances.add(fresh) }
                available.add(fresh)
                semaphore.release()  // 置換成功 → permit を返却
                onLog("WebView 再生成: クラッシュインスタンスを置換 (pool=$currentSize)")
            } catch (e: Exception) {
                // 置換失敗 → permit は返却しない（後で maybeReplenish が補充）
                onLog("WebView 再生成失敗、後で補充します: ${e.message}")
            }
        }
    }

    /**
     * [2026-05-18] プールが poolSize 未満なら不足分を補充する。
     * fetch() 呼び出し時に呼ばれ、トラフィックがある時に自己回復する。
     * 新規 WebView ごとに permit を 1 つ追加して不変条件を維持する。
     */
    private fun maybeReplenish() {
        if (closed) return
        if (currentSize >= poolSize) return
        if (!replenishing.compareAndSet(false, true)) return
        mainHandler.post {
            try {
                if (closed) return@post
                var added = 0
                while (currentSize < poolSize) {
                    // [2026-06-28] 空き slot を計算 (Codex 指摘: currentSize は onLowMemory 後の歯抜けに対応できない)
                    val usedSlots = webViewSlots.values.toSet()
                    val nextSlotIndex = (0 until poolSize).firstOrNull { it !in usedSlots } ?: -1
                    val fresh = try {
                        createWebView(slotIndex = nextSlotIndex)
                    } catch (e: Exception) {
                        onLog("WebView 補充失敗: ${e.message}")
                        break
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(fresh, true)
                    synchronized(allInstances) { allInstances.add(fresh) }
                    available.add(fresh)
                    semaphore.release()  // 新規 WebView 分の permit を追加
                    added++
                }
                if (added > 0) onLog("WebView プール補充: +$added (pool=$currentSize)")
            } finally {
                replenishing.set(false)
            }
        }
    }

    val currentSize: Int get() = synchronized(allInstances) { allInstances.size }
    val availableCount: Int get() = available.size

    /**
     * 全 WebView を破棄する。サーバー停止時に呼ぶ。
     * [2026-03-13] メインスレッドから呼ばれた場合のデッドロックを修正
     * [2026-05-18] closed フラグを立て、以後の release/replace/replenish を無効化
     */
    fun destroy() {
        closed = true
        val destroyAction = Runnable {
            // [2026-03-13] 破棄前に Cookie をフラッシュして永続化
            CookieManager.getInstance().flush()
            synchronized(allInstances) {
                for (wv in allInstances) {
                    try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
                }
                allInstances.clear()
            }
            available.clear()
            deadInstances.clear()
            // [2026-06-28] Codex 指摘: 完全シャットダウン時に slot map もクリア
            webViewSlots.clear()
            navigatorOverrideHandlers.clear()
            currentUserAgents.clear()
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
