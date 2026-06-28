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

    /**
     * Cronet を初期化する。既に初期化済みなら何もしない。
     * 失敗時は engine = null のまま。
     */
    @Synchronized
    fun init(context: Context) {
        if (engine != null) return
        try {
            engine = CronetEngine.Builder(context.applicationContext)
                .enableQuic(true)
                .enableHttp2(true)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, 1024 * 1024)
                .build()
            onLog("CronetManager: 初期化成功 version=${engine?.versionString}")
        } catch (e: Throwable) {
            engine = null
            onLog("CronetManager: 初期化失敗: ${e.message}")
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
        val executor = Executors.newSingleThreadExecutor()
        try {
            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(req: UrlRequest, info: UrlResponseInfo, newLocation: String) {
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
            onLog("fetchSync: ok url=$url status=$statusCode body=${body.size()}B")
            return statusCode to body.toString(Charsets.UTF_8)
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
}
