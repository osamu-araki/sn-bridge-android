// Version: 1.0.0 | Updated: 2026-06-28
// [2026-06-28] Cronet (Chromium ネットワークスタック) の singleton 管理。
//   WebView の HTTP リクエストを shouldInterceptRequest で intercept し、
//   Cronet の TLS handshake (JA3/JA4) を使うことで WebView 内蔵の TLS fingerprint を変える。
package jp.salesnow.chromebridge.fetcher

import android.content.Context
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cronet シングルトン。Application.onCreate で初期化。
 *
 * 使い方:
 *   CronetManager.init(context)
 *   val engine = CronetManager.engine ?: return null
 *   ...
 */
object CronetManager {

    @Volatile
    var engine: CronetEngine? = null
        private set

    // [2026-06-29] 最後の fetchSync 失敗理由 (UI 表示用)
    @Volatile
    var lastFetchError: String? = null
        private set

    /** ChallengeManager.show 等から呼び出される独立ロガー (file log には繋がない) */
    private var onLog: (String) -> Unit = {}

    fun setLogger(logger: (String) -> Unit) {
        onLog = logger
    }

    // [2026-06-29] init 二重実行ガード
    private val initializing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Cronet を初期化する。Play Services Cronet を async install して、成功時のみ engine を作る。
     * 既に初期化済み or 初期化中なら何もしない。失敗時は engine = null (WebView default fallback)。
     *
     * Codex 設計レビュー反映:
     *   - GMS install 失敗時に Java fallback で別 TLS fingerprint を出すリスクを回避
     *   - cronet-fallback は意図的に入れない
     *   - 失敗時は engine=null のまま (shouldInterceptRequest 等の既存実装が WebView default に倒す)
     *   - install 中は initializing フラグで二重 install を防ぐ
     */
    fun init(context: Context) {
        if (engine != null) return
        if (!initializing.compareAndSet(false, true)) return
        try {
            com.google.android.gms.net.CronetProviderInstaller
                .installProvider(context.applicationContext)
                .addOnCompleteListener { task ->
                    try {
                        if (task.isSuccessful) {
                            try {
                                engine = CronetEngine.Builder(context.applicationContext)
                                    .enableQuic(true)
                                    .enableHttp2(true)
                                    .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 1024 * 1024)
                                    .build()
                                onLog("CronetManager: GMS Cronet 初期化成功 version=${engine?.versionString}")
                            } catch (e: Throwable) {
                                engine = null
                                onLog("CronetManager: CronetEngine.Builder 失敗: ${e.javaClass.simpleName}: ${e.message}")
                            }
                        } else {
                            engine = null
                            val errorReason = task.exception?.javaClass?.simpleName ?: "unknown"
                            val errorMsg = task.exception?.message ?: ""
                            onLog("CronetManager: GMS Cronet install 失敗 ($errorReason: $errorMsg) → engine=null (WebView default fallback)")
                        }
                    } finally {
                        initializing.set(false)
                    }
                }
        } catch (e: Throwable) {
            engine = null
            initializing.set(false)
            onLog("CronetManager: installProvider 呼び出し失敗 ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * [Phase 2a] TLS Fingerprint 計測用の単発 fetch。
     * tls.peet.ws/api/all 等の JSON を取得して JA3/JA4 を返す。
     *
     * @return (statusCode, bodyText) または null (失敗時)
     */
    fun fetchSync(
        url: String,
        timeoutSeconds: Int = 30,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String>? {
        lastFetchError = null
        val e = engine
        if (e == null) {
            val msg = "CronetEngine 未初期化 (null)"
            lastFetchError = msg
            onLog("fetchSync: $msg url=$url")
            return null
        }
        val body = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var statusCode = 0
        var error: Throwable? = null
        var ssrfBlocked = false
        val executor = Executors.newSingleThreadExecutor()
        try {
            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(req: UrlRequest, info: UrlResponseInfo, newLocation: String) {
                    // [2026-07-16 SSRF] リダイレクト先が private/loopback/metadata 等なら follow せずキャンセル。
                    val v = SsrfGuard.evaluate(newLocation, System.currentTimeMillis())
                    if (v.blocked) {
                        onLog("blocked(ssrf-redirect): $newLocation [${v.reason ?: "-"}]")
                        ssrfBlocked = true; req.cancel(); return
                    }
                    req.followRedirect()
                }
                override fun onResponseStarted(req: UrlRequest, info: UrlResponseInfo) {
                    statusCode = info.httpStatusCode
                    req.read(ByteBuffer.allocateDirect(32 * 1024))
                }
                override fun onReadCompleted(req: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                    byteBuffer.flip()
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    body.write(bytes)
                    byteBuffer.clear()
                    req.read(byteBuffer)
                }
                override fun onSucceeded(req: UrlRequest, info: UrlResponseInfo) {
                    latch.countDown()
                }
                override fun onFailed(req: UrlRequest, info: UrlResponseInfo?, ex: org.chromium.net.CronetException) {
                    error = ex
                    latch.countDown()
                }
                override fun onCanceled(req: UrlRequest, info: UrlResponseInfo?) {
                    latch.countDown()
                }
            }
            val builder = e.newUrlRequestBuilder(url, callback, executor)
                .setHttpMethod("GET")
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            val req = builder.build()
            req.start()
            if (!latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                req.cancel()
                val msg = "timeout (${timeoutSeconds}s)"
                lastFetchError = msg
                onLog("fetchSync: $msg url=$url")
                return null
            }
            if (error != null) {
                val msg = "${error?.javaClass?.simpleName}: ${error?.message}"
                lastFetchError = msg
                onLog("fetchSync: failed url=$url $msg")
                return null
            }
            // [2026-07-16 SSRF] リダイレクト先遮断でキャンセルした場合は成功扱いにしない。
            if (ssrfBlocked) { lastFetchError = "blocked(ssrf-redirect)"; return null }
            onLog("fetchSync: ok url=$url status=$statusCode body=${body.size()}B")
            // [2026-06-29] Android ART は Java 10+ の ByteArrayOutputStream.toString(Charset) を
            //   持たないため (NoSuchMethodError 発生)、String(ByteArray, Charset) で decode する
            return statusCode to String(body.toByteArray(), Charsets.UTF_8)
        } catch (e: Throwable) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            lastFetchError = msg
            onLog("fetchSync: 例外 url=$url $msg")
            return null
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * [Phase 2b] WebView.shouldInterceptRequest 用の同期 fetch。
     * subresource GET/HEAD のみ対象。Range header あり / POST 系 / main frame は呼び出し側で除外する。
     *
     * Codex 指摘反映済:
     *   - 3xx ステータスは null 返却 (WebResourceResponse は 3xx 非対応)
     *   - body 全読みに 5MB 上限を設定 (OOM 防止)
     *   - shared executor (fixed pool size 4) で thread churn 削減
     *   - 複数 Set-Cookie ヘッダーを全部保持 (allHeadersAsList で複数エントリー取得)
     *   - restricted header から Origin / Referer を除外 (CORS / hotlink 互換性)
     *
     * @return InterceptResponse または null (= WebView default fallback)
     */
    data class InterceptResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val mimeType: String?,
        val encoding: String?,
        val headers: Map<String, String>,
        val setCookies: List<String>,  // 複数 Set-Cookie をすべて保持
        val body: ByteArray,
        val finalUrl: String,  // [2026-06-28] redirect 後の最終 URL (Phase 2e で baseUrl に使用)
        // [2026-06-28 Phase 2e] redirect 中の各 URL に対する Set-Cookie 群。
        //   呼び出し側で各 URL に対して CookieManager.setCookie を呼ぶことで session 維持。
        val redirectSetCookies: List<Pair<String, String>>,  // (urlAtTime, setCookieValue)
    )

    // [2026-06-28] Codex 指摘: 1 リクエスト 1 executor は thread churn が大きい。
    //   fixed pool で頭打ちにする (subresource 並列度の上限)
    private val sharedExecutor = Executors.newFixedThreadPool(4)

    // [2026-06-29 Phase 2d] streaming 用は専用 executor (cached pool)。
    //   理由: SSE / 大きい asset で backpressure block が発生すると sharedExecutor (4 thread)
    //   が枯渇するため、streaming は別 pool に分離。
    //   cached pool は idle thread を再利用して churn を抑え、必要時に伸縮する。
    private val streamingExecutor = Executors.newCachedThreadPool()

    // [2026-06-29 Codex 指摘] cached pool は無制限なので、同時 streaming 数をカウンタで制限する。
    //   上限超のとき streamingFetch は null fallback (= 既存 interceptFetch / WebView default)。
    //   WebView が close() し忘れた stream が積み重なって thread が枯渇するリスクを抑える。
    private val streamingActiveCount = java.util.concurrent.atomic.AtomicInteger(0)
    private const val STREAMING_MAX_CONCURRENT = 8

    // [2026-06-28] Codex 指摘: body 全読みの OOM 防止。subresource は 5MB を超えたら fallback
    private const val MAX_BODY_BYTES = 5 * 1024 * 1024  // 5MB

    fun interceptFetch(
        url: String,
        method: String,
        requestHeaders: Map<String, String>,
        timeoutSeconds: Int = 10,
    ): InterceptResponse? {
        val e = engine ?: return null
        val body = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var statusCode = 0
        var statusText = "OK"
        val responseHeaders = mutableMapOf<String, String>()
        val setCookies = mutableListOf<String>()
        val redirectSetCookies = mutableListOf<Pair<String, String>>()
        var finalUrl = url  // 初期値、redirect / 最終 response の URL で更新
        var error: Throwable? = null
        var ssrfBlocked = false
        var sizeExceeded = false
        try {
            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(req: UrlRequest, info: UrlResponseInfo, newLocation: String) {
                    // [2026-06-28 Phase 2e] redirect 中の Set-Cookie を保持 (URL ごと)
                    val urlAtRedirect = info.url ?: url
                    info.allHeadersAsList.forEach { entry ->
                        if (entry.key.equals("Set-Cookie", ignoreCase = true)) {
                            redirectSetCookies.add(urlAtRedirect to entry.value)
                        }
                    }
                    // [2026-07-16 SSRF] リダイレクト先が private/loopback/metadata 等なら follow せずキャンセル。
                    val v = SsrfGuard.evaluate(newLocation, System.currentTimeMillis())
                    if (v.blocked) {
                        onLog("blocked(ssrf-redirect): $newLocation [${v.reason ?: "-"}]")
                        ssrfBlocked = true; req.cancel(); return
                    }
                    req.followRedirect()
                }
                override fun onResponseStarted(req: UrlRequest, info: UrlResponseInfo) {
                    statusCode = info.httpStatusCode
                    statusText = info.httpStatusText.takeIf { it.isNotEmpty() } ?: "OK"
                    finalUrl = info.url ?: url  // [Phase 2e] 最終 URL を baseUrl 用に保存
                    // [Codex 指摘] 複数 Set-Cookie を含む全 header を取得
                    info.allHeadersAsList.forEach { entry ->
                        val k = entry.key
                        val v = entry.value
                        if (k.equals("Set-Cookie", ignoreCase = true)) {
                            setCookies.add(v)
                        }
                        // 同名ヘッダーは最初の値を保持 (Set-Cookie 以外で衝突するヘッダーは通常 1 個)
                        if (!responseHeaders.containsKey(k)) responseHeaders[k] = v
                    }
                    req.read(ByteBuffer.allocateDirect(32 * 1024))
                }
                override fun onReadCompleted(req: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                    byteBuffer.flip()
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    body.write(bytes)
                    byteBuffer.clear()
                    // [Codex 指摘] body サイズ上限 (5MB 超で cancel → fallback)
                    if (body.size() > MAX_BODY_BYTES) {
                        sizeExceeded = true
                        req.cancel()
                        return
                    }
                    req.read(byteBuffer)
                }
                override fun onSucceeded(req: UrlRequest, info: UrlResponseInfo) {
                    latch.countDown()
                }
                override fun onFailed(req: UrlRequest, info: UrlResponseInfo?, ex: org.chromium.net.CronetException) {
                    error = ex
                    latch.countDown()
                }
                override fun onCanceled(req: UrlRequest, info: UrlResponseInfo?) {
                    latch.countDown()
                }
            }
            val builder = e.newUrlRequestBuilder(url, callback, sharedExecutor)
                .setHttpMethod(method)
            requestHeaders.forEach { (k, v) ->
                if (!isRestrictedHeader(k)) builder.addHeader(k, v)
            }
            val req = builder.build()
            req.start()
            if (!latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                req.cancel()
                throw IOException("Cronet timeout (${timeoutSeconds}s) for $url")
            }
            if (error != null) throw IOException("Cronet error: ${error?.message}")
            // [2026-07-16 SSRF] リダイレクト先遮断でキャンセルした場合は、statusCode=0 応答や
            //   null（＝WebView default fallback で再フェッチ）に倒さず、403 空応答を確定で返す。
            //   これで redirect target が WebView 側で再実行される余地を残さない。
            if (ssrfBlocked) return InterceptResponse(
                statusCode = 403,
                reasonPhrase = "Forbidden",
                mimeType = "text/plain",
                encoding = "utf-8",
                headers = emptyMap(),
                setCookies = emptyList(),
                body = ByteArray(0),
                finalUrl = url,
                redirectSetCookies = emptyList(),
            )
            if (sizeExceeded) return null  // OOM 防止のため fallback
            // [Codex 指摘] 3xx は WebResourceResponse 非対応 → fallback (WebView 側で redirect させる)
            // 注: Cronet 自体は 3xx を自動 follow するので、ここに来るのは「follow 不可な 3xx」のみ
            if (statusCode in 300..399) return null
            val contentType = responseHeaders["Content-Type"] ?: responseHeaders["content-type"]
            val mimeType = contentType?.substringBefore(";")?.trim()
            val charset = contentType?.split(";")
                ?.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
                ?.substringAfter("=")?.trim()
            return InterceptResponse(
                statusCode = statusCode,
                reasonPhrase = statusText,
                mimeType = mimeType,
                encoding = charset,
                headers = responseHeaders.toMap(),
                setCookies = setCookies.toList(),
                body = body.toByteArray(),
                finalUrl = finalUrl,
                redirectSetCookies = redirectSetCookies.toList(),
            )
        } catch (e: Throwable) {
            return null
        }
    }

    /** Cronet が拒否する HTTP ヘッダー (hop-by-hop 系のみ)。Origin/Referer は CORS / hotlink 互換性のため通す。 */
    private val restrictedHeaderNames = setOf(
        "accept-charset", "accept-encoding", "access-control-request-headers",
        "access-control-request-method", "connection", "content-length",
        "cookie2", "date", "dnt", "expect", "host", "keep-alive",
        "te", "trailer", "transfer-encoding", "upgrade", "via",
    )

    private fun isRestrictedHeader(name: String): Boolean =
        restrictedHeaderNames.contains(name.lowercase())

    // ========== [Phase 2d] streamingFetch ==========

    /**
     * [Phase 2d] SSE / 大きな asset 向けの streaming fetch。
     *
     * Cronet の onReadCompleted から PipedOutputStream に書き、WebView の
     * WebResourceResponse 側は PipedInputStream を読む。これで body を全部
     * メモリに溜めずに stream できる (= SSE / AI Overview の XHR / 動画等に対応)。
     *
     * Codex 設計レビュー反映:
     *   - streamingExecutor (cached pool) で sharedExecutor 枯渇を回避
     *   - CancelableInputStream wrapper で WebView の close 時に req.cancel() を保証
     *   - headersStarted フラグで onCanceled/onFailed 時の statusCode=0 fallback を厳密に
     *   - redirect 中の Set-Cookie を URL ごとに即時 CookieManager.setCookie
     *   - response headers から Set-Cookie / Content-Length / Content-Encoding を除外
     *   - PipedInputStream buffer 256KB
     *   - headerLatch 5s (短め、shouldInterceptRequest を詰まらせない)
     *
     * 戻り値: WebResourceResponse (PipedInputStream wrapper を含む) または null (fallback)
     */
    fun streamingFetch(
        url: String,
        method: String,
        requestHeaders: Map<String, String>,
        headerTimeoutSeconds: Int = 5,
    ): android.webkit.WebResourceResponse? {
        val e = engine ?: return null

        // [Codex 指摘] 同時 streaming 数を上限制御 (cached pool 枯渇防止)
        val active = streamingActiveCount.get()
        if (active >= STREAMING_MAX_CONCURRENT) {
            onLog("streamingFetch: max concurrent ($active/$STREAMING_MAX_CONCURRENT) → fallback url=$url")
            return null
        }
        streamingActiveCount.incrementAndGet()
        val decrementedAtomic = java.util.concurrent.atomic.AtomicBoolean(false)
        fun decrementOnce() {
            if (decrementedAtomic.compareAndSet(false, true)) {
                streamingActiveCount.decrementAndGet()
            }
        }

        val pipeIn = java.io.PipedInputStream(256 * 1024)
        val pipeOut = java.io.PipedOutputStream(pipeIn)
        val headerLatch = CountDownLatch(1)

        var statusCode = 0
        var statusText: String? = null
        val responseHeaders = mutableMapOf<String, String>()
        var mimeType: String? = null
        var encoding: String? = null
        var headerError: Throwable? = null
        var headersStarted = false
        var ssrfBlocked = false
        // [Codex 指摘] WebView の close() で確実に UrlRequest.cancel() するため AtomicReference で保持
        val currentReq = java.util.concurrent.atomic.AtomicReference<UrlRequest?>(null)

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(req: UrlRequest, info: UrlResponseInfo, newLocation: String) {
                // Set-Cookie 同期 (redirect 元 URL)
                val urlAtRedirect = info.url ?: url
                info.allHeadersAsList.forEach { entry ->
                    if (entry.key.equals("Set-Cookie", ignoreCase = true)) {
                        try {
                            android.webkit.CookieManager.getInstance().setCookie(urlAtRedirect, entry.value)
                        } catch (_: Throwable) {}
                    }
                }
                // [2026-07-16 SSRF] リダイレクト先が private/loopback/metadata 等なら follow せずキャンセル。
                val v = SsrfGuard.evaluate(newLocation, System.currentTimeMillis())
                if (v.blocked) {
                    onLog("blocked(ssrf-redirect): $newLocation [${v.reason ?: "-"}]")
                    ssrfBlocked = true; req.cancel(); return
                }
                req.followRedirect()
            }
            override fun onResponseStarted(req: UrlRequest, info: UrlResponseInfo) {
                statusCode = info.httpStatusCode
                statusText = info.httpStatusText.takeIf { it.isNotEmpty() }
                val finalUrl = info.url ?: url
                info.allHeadersAsList.forEach { entry ->
                    val k = entry.key
                    val v = entry.value
                    val lower = k.lowercase()
                    // 最終 URL の Set-Cookie を即時同期
                    if (lower == "set-cookie") {
                        try {
                            android.webkit.CookieManager.getInstance().setCookie(finalUrl, v)
                        } catch (_: Throwable) {}
                        return@forEach
                    }
                    // WebResourceResponse の headers から除外するもの:
                    //   - Content-Length / Content-Encoding: Cronet が decode した結果と矛盾するため
                    //   - Set-Cookie: 上記で同期済
                    if (lower == "content-length" || lower == "content-encoding") return@forEach
                    if (!responseHeaders.containsKey(k)) responseHeaders[k] = v
                }
                val ct = responseHeaders["Content-Type"] ?: responseHeaders["content-type"]
                mimeType = ct?.substringBefore(";")?.trim()
                encoding = ct?.split(";")
                    ?.firstOrNull { it.trim().startsWith("charset=", ignoreCase = true) }
                    ?.substringAfter("=")?.trim()
                headersStarted = true
                headerLatch.countDown()
                // body 読み開始 (HEAD / 204 / 205 等は body なし → onSucceeded が即来る)
                req.read(ByteBuffer.allocateDirect(32 * 1024))
            }
            override fun onReadCompleted(req: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                try {
                    val bytes = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(bytes)
                    pipeOut.write(bytes)
                } catch (e: IOException) {
                    // WebView 側が close (read 終了) → pipe broken → request cancel
                    try { req.cancel() } catch (_: Throwable) {}
                    return
                }
                byteBuffer.clear()
                req.read(byteBuffer)
            }
            override fun onSucceeded(req: UrlRequest, info: UrlResponseInfo) {
                try { pipeOut.close() } catch (_: Exception) {}
                decrementOnce()
            }
            override fun onFailed(req: UrlRequest, info: UrlResponseInfo?, ex: org.chromium.net.CronetException) {
                headerError = ex
                try { pipeOut.close() } catch (_: Exception) {}
                headerLatch.countDown()
                decrementOnce()
            }
            override fun onCanceled(req: UrlRequest, info: UrlResponseInfo?) {
                try { pipeOut.close() } catch (_: Exception) {}
                headerLatch.countDown()
                decrementOnce()
            }
        }

        try {
            val builder = e.newUrlRequestBuilder(url, callback, streamingExecutor).setHttpMethod(method)
            requestHeaders.forEach { (k, v) ->
                if (!isRestrictedHeader(k)) builder.addHeader(k, v)
            }
            val req = builder.build()
            currentReq.set(req)
            req.start()

            if (!headerLatch.await(headerTimeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                try { req.cancel() } catch (_: Throwable) {}
                try { pipeOut.close() } catch (_: Throwable) {}
                try { pipeIn.close() } catch (_: Throwable) {}
                onLog("streamingFetch: header timeout (${headerTimeoutSeconds}s) url=$url")
                return null
            }
            if (headerError != null) {
                try { pipeIn.close() } catch (_: Throwable) {}
                onLog("streamingFetch: failed url=$url error=${headerError?.javaClass?.simpleName}: ${headerError?.message}")
                return null
            }
            // [2026-07-16 SSRF] リダイレクト先遮断でキャンセルした場合は、fallback で再フェッチさせず
            //   403 空応答を確定で返す（redirect target を WebView 側で再実行させない）。
            if (ssrfBlocked) {
                try { pipeIn.close() } catch (_: Throwable) {}
                try { pipeOut.close() } catch (_: Throwable) {}
                decrementOnce()
                onLog("streamingFetch: blocked(ssrf-redirect) url=$url")
                return android.webkit.WebResourceResponse(
                    "text/plain", "utf-8", 403, "Forbidden",
                    HashMap<String, String>(), java.io.ByteArrayInputStream(ByteArray(0))
                )
            }
            if (!headersStarted) {
                // onCanceled で latch.countDown された場合
                try { pipeIn.close() } catch (_: Throwable) {}
                onLog("streamingFetch: canceled before headers url=$url")
                return null
            }
            if (statusCode in 300..399) {
                // 3xx は WebResourceResponse 非対応 (通常は Cronet が自動 follow するのでここに来ない)
                try { req.cancel() } catch (_: Throwable) {}
                try { pipeIn.close() } catch (_: Throwable) {}
                return null
            }
            val reasonPhrase = statusText ?: defaultReasonPhrase(statusCode)
            val wrappedStream = CancelableInputStream(pipeIn, currentReq.get(), pipeOut)
            onLog("streamingFetch: stream start url=$url status=$statusCode mime=$mimeType")
            return android.webkit.WebResourceResponse(
                mimeType, encoding, statusCode, reasonPhrase,
                responseHeaders.toMap(), wrappedStream
            )
        } catch (e: Throwable) {
            try { pipeOut.close() } catch (_: Throwable) {}
            try { pipeIn.close() } catch (_: Throwable) {}
            decrementOnce()
            onLog("streamingFetch: 例外 url=$url error=${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    /** WebView が close() したときに Cronet UrlRequest を確実に cancel する wrapper */
    private class CancelableInputStream(
        private val delegate: java.io.PipedInputStream,
        private val req: UrlRequest?,
        private val pipeOut: java.io.PipedOutputStream,
    ) : java.io.FilterInputStream(delegate) {
        override fun close() {
            try { req?.cancel() } catch (_: Throwable) {}
            try { pipeOut.close() } catch (_: Throwable) {}
            try { delegate.close() } catch (_: Throwable) {}
        }
    }

    /** status code に対する標準 reason phrase fallback (空文字を避けるため) */
    private fun defaultReasonPhrase(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }
}
