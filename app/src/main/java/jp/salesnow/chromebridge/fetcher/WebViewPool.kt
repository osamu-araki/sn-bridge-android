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
            // [2026-06-20] リクエスト UA 未指定時に毎回戻すための初期値を記録（最初の 1 個で十分、全 WebView 同じ値）
            if (defaultUserAgent.isEmpty()) defaultUserAgent = settings.userAgentString
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
                message = "Google 検索のサーキットブレーカーが開いています。残り ${remainingSec} 秒"
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
        val waitMs = synchronized(throttleLock) {
            val now = System.currentTimeMillis()
            val nextAllowed = nextAllowedAtByHost[host] ?: 0L
            val startAt = maxOf(nextAllowed, now)
            nextAllowedAtByHost[host] = startAt + intervalMs
            startAt - now
        }
        if (waitMs > 0L) {
            onLog("throttle: host=$host wait=${waitMs}ms")
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

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (extracted || settled.get()) return
                    if (url == null || url == "about:blank") return

                    // [2026-06-25] タイトル + URL + reCAPTCHA iframe + 本文長の複合判定。
                    //   evaluateJavascript の callback には JSON.stringify(...) の戻り値が
                    //   さらに文字列リテラルとして来るため、二段 parse する。
                    //   parse 失敗時は challenge 判定を skip して通常抽出に進む。
                    val detectJs = "JSON.stringify({" +
                        "title: document.title||''," +
                        "url: location.href||''," +
                        "recaptcha: !!document.querySelector(" +
                            "'iframe[src*=\"recaptcha\"],iframe[src*=\"google.com/recaptcha\"]')," +
                        "bodyLen: (document.body && document.body.innerText || '').trim().length" +
                        "})"
                    wv.evaluateJavascript(detectJs) { jsonRaw ->
                        if (extracted || settled.get()) return@evaluateJavascript
                        var title = ""
                        var pageUrl = ""
                        var hasRecaptcha = false
                        var bodyLen = -1
                        var parsedOk = false
                        try {
                            val unwrapped = com.google.gson.JsonParser.parseString(jsonRaw).asString
                            val obj = com.google.gson.JsonParser.parseString(unwrapped).asJsonObject
                            title = obj.get("title")?.asString ?: ""
                            pageUrl = obj.get("url")?.asString ?: ""
                            hasRecaptcha = obj.get("recaptcha")?.asBoolean ?: false
                            bodyLen = obj.get("bodyLen")?.asInt ?: -1
                            parsedOk = true
                        } catch (_: Exception) {
                            // parse 失敗時は challenge 判定を skip（通常抽出にフォールスルー）
                        }

                        if (parsedOk && ChallengeManager.isChallenge(title, pageUrl, hasRecaptcha, bodyLen)) {
                            // チャレンジページ検知 → ユーザーに画面を表示
                            challengeDetected.set(true)
                            if (!challengeShown.getAndSet(true)) {
                                challengeStartMs = System.currentTimeMillis()
                                val lowerUrl = pageUrl.lowercase()
                                val isGoogleBlocked = ChallengeManager.isGoogleSearchBlocked(pageUrl, bodyLen)
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
                                if (isGoogleBlocked) {
                                    val gHost = try { java.net.URI(pageUrl).host?.lowercase() } catch (_: Exception) { null }
                                    if (gHost != null) {
                                        GoogleSearchCircuitBreaker.recordFailure(context, gHost)
                                        val remaining = GoogleSearchCircuitBreaker.remainingTripMs(gHost)
                                        if (remaining > 0) {
                                            onLog("circuit tripped: host=$gHost trip=${remaining / 1000}s")
                                        }
                                    }
                                }
                                val shown = ChallengeManager.show(context, wv)
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
                            // チャレンジ画面が出ていたら閉じる
                            if (challengeShown.get()) {
                                val elapsed = System.currentTimeMillis() - challengeStartMs
                                onLog("チャレンジ成功: domain=$challengeDomain ${elapsed}ms (worker=$workerId)")
                                onChallengeResult(true)
                                // [2026-06-10] Codex#4: 自分の WebView を明示指定して dismiss
                                mainHandler.post { ChallengeManager.dismiss(wv) }
                            }
                            mainHandler.postDelayed({
                                // [2026-05-18] fetch が既に確定していたら古い WebView を触らない
                                if (settled.get()) return@postDelayed
                                val js = if (request.mode == "dom") JsExtractors.EXTRACT_DOM
                                         else JsExtractors.EXTRACT_TEXT
                                wv.evaluateJavascript(js) { result ->
                                    if (settled.get()) return@evaluateJavascript
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
                    if (challengeShown.get()) mainHandler.post { ChallengeManager.dismiss(wv) }
                    latch.countDown()
                    return true
                }
            }
            // [2026-06-20] リクエスト毎の User-Agent を適用。null/空文字なら createWebView 時の
            //   デフォルトに戻す（前回 override の影響を消す）。loadUrl の直前に必ず実行。
            // [2026-06-26] 優先順位: per-request UA > 設定 defaultUserAgentOverride > WebView 既定 UA。
            //   Google 等で `; wv` の付く Android WebView UA が flag されやすいため、設定でデスクトップ
            //   Chrome UA に差し替えてグローバルに適用できるようにする。
            val requestedUa = request.userAgent?.takeIf { it.isNotBlank() }
            val configuredDefault = try {
                jp.salesnow.chromebridge.data.SettingsRepository(context)
                    .defaultUserAgentOverride.takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            wv.settings.userAgentString = requestedUa ?: configuredDefault ?: defaultUserAgent
            wv.loadUrl(request.url)
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
                message = pageError ?: "WebView レンダラが異常終了しました (worker=$workerId)"
            )
        }

        // タイムアウト時はチャレンジ画面も閉じる
        if (!completed) {
            mainHandler.post {
                wv.stopLoading()
                // [2026-06-10] Codex#4: 自分の WebView を明示指定して dismiss
                if (challengeShown.get()) ChallengeManager.dismiss(wv)
            }
            val msg = if (challengeDetected.get()) {
                val elapsed = System.currentTimeMillis() - challengeStartMs
                onChallengeResult(false)
                "チャレンジ失敗（タイムアウト）: domain=$challengeDomain ${elapsed}ms (worker=$workerId)".also { onLog(it) }
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
     * 最低1つは残す。permit を消費したまま allInstances からも削除するため
     * 不変条件（permit 数 == allInstances.size）は維持される。
     */
    fun onLowMemory() {
        var shrunk = 0
        while (available.size > 1) {
            val wv = available.poll() ?: break
            if (semaphore.tryAcquire()) {
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
        mainHandler.post {
            try { dead.stopLoading(); dead.destroy() } catch (_: Exception) {}
            synchronized(allInstances) { allInstances.remove(dead) }
            if (closed) return@post
            try {
                val fresh = createWebView()
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
                    val fresh = try {
                        createWebView()
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
