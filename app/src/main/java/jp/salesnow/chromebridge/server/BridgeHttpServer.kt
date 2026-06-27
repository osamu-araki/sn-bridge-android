// Version: 1.7.0 | Updated: 2026-05-18
// [2026-03-08] server/index.js (Express + ws) を NanoHTTPd で再実装
// API 仕様は完全互換: POST /fetch, GET /status
// [2026-03-08] Status import 修正、TOO_MANY_REQUESTS を INTERNAL_ERROR に置換
// [2026-03-11] StatsCollector によるメトリクス記録を追加
// [2026-03-11] 直列キュー → Semaphore ベース並列処理、タイムアウトクランプ
// [2026-03-11] GET /stats エンドポイント追加
// [2026-03-12] レートリミッタ・キュー上限チェック削除（Semaphore + TCP バックログに委譲）
// [2026-05-18] スレッドプールを有界キュー化、過負荷時に 503 を返す過負荷防御を追加
// [2026-05-18] 500 排除: 一時障害は 503+Retry-After、URL個別失敗は 200+ok:false に整理。
//   全エラー応答に retryable / category フィールドを付与。/status に累計メトリクス追加。
package jp.salesnow.chromebridge.server

import com.google.gson.Gson
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import jp.salesnow.chromebridge.data.RequestMetrics
import jp.salesnow.chromebridge.data.StatsCollector
import jp.salesnow.chromebridge.data.StatsRepository
import jp.salesnow.chromebridge.fetcher.FetchRequest
import jp.salesnow.chromebridge.fetcher.WebViewPool
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BridgeHttpServer(
    port: Int,
    private val pool: WebViewPool,
    private val auth: AuthMiddleware,
    private val maxTimeout: Int = 60,
    private val maxWait: Int = 10,
    private val statsCollector: StatsCollector? = null,
    private val statsRepository: StatsRepository? = null,
    // [2026-06-10] OTA: Portal の notify から POST /update-check で呼ばれる
    //   戻り値は「実際に起動したか」（false = 既に実行中で skip）
    private val onUpdateCheck: (() -> Boolean)? = null,
    private val onLog: (String) -> Unit = {}
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val startTime = System.currentTimeMillis()
    private val pendingCount = AtomicInteger(0)

    // [2026-05-18] 起動来の累計メトリクス（/status で公開）
    private val metricRequests = AtomicLong(0)   // fetch を実行したリクエスト数
    private val metricErrors = AtomicLong(0)     // 失敗したリクエスト数
    private val metricTimeouts = AtomicLong(0)   // タイムアウトしたリクエスト数
    private val metricElapsedMs = AtomicLong(0)  // 処理時間の累計（平均算出用）

    // [2026-05-18] NanoHTTPD 2.3.1 の Status enum に 503 が無いためカスタム定義
    private val statusServiceUnavailable = object : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = 503
        override fun getDescription(): String = "503 Service Unavailable"
    }

    // [2026-06-10] Codex#3: Payload Too Large（413）も Status enum 未収録のためカスタム定義
    private val statusPayloadTooLarge = object : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = 413
        override fun getDescription(): String = "413 Payload Too Large"
    }

    // [2026-06-26] Circuit Breaker open 用の non-standard 499。
    //   Google が IP/UA 単位でクライアントを 403 永続ブロックする事例に対する保護。
    //   Bridge が短時間に同 host への失敗を閾値超え検知したら一定時間 fetch を即拒否する。
    private val statusCircuitOpen = object : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = 499
        override fun getDescription(): String = "499 Circuit Open"
    }

    companion object {
        // [2026-06-10] Codex#3: バリデーション・上限定数
        const val MAX_REQUEST_BODY_BYTES = 256 * 1024  // 256 KB
        const val MAX_MAX_LENGTH = 5 * 1024 * 1024     // 5 MB（text 取得の最大）
        const val MAX_TIMEOUT_HARD_LIMIT = 300         // 5 分（maxTimeout 設定の上限ガード）
        // [2026-06-20] user_agent 文字列の上限。一般的な UA は 200 文字以下。
        const val MAX_USER_AGENT_LEN = 1024
    }

    // [2026-03-12] スレッドプール制限: 無制限スレッド生成を防止
    // コアプール=pool数、最大=pool数×8（待機リクエスト含む）、60秒アイドルで縮小
    init {
        val maxThreads = maxOf(pool.currentSize * 8, 32)
        setAsyncRunner(BoundRunner(maxThreads, onLog))
    }

    override fun serve(session: IHTTPSession): Response {
        // [2026-05-18] 想定外例外を NanoHTTPd デフォルトの 500 に落とさず 503 JSON で返す
        return try {
            val uri = session.uri
            val method = session.method
            when {
                method == Method.POST && uri == "/fetch" -> handleFetch(session)
                method == Method.GET && uri == "/status" -> handleStatus()
                // [2026-03-11] 統計 API エンドポイント
                method == Method.GET && uri == "/stats" -> handleStats(session)
                // [2026-06-10] OTA: Portal の notify から呼ばれる「今すぐ更新確認」
                method == Method.POST && uri == "/update-check" -> handleUpdateCheck(session)
                else -> errorResponse(
                    Status.NOT_FOUND, "not_found",
                    "エンドポイントが見つかりません", retryable = false, category = "client"
                )
            }
        } catch (e: Exception) {
            onLog("未捕捉例外: ${e.message}")
            errorResponse(
                statusServiceUnavailable, "internal_error",
                "サーバー内部エラー: ${e.message}", retryable = true, category = "bridge"
            )
        }
    }

    private fun handleFetch(session: IHTTPSession): Response {
        // 認証チェック
        val authResult = auth.check(
            session.headers["authorization"],
            session.remoteIpAddress
        )
        if (authResult is AuthMiddleware.AuthResult.Error) {
            val errorCode = if (authResult.code == 401) "unauthorized" else "forbidden"
            // [2026-03-11] 認証エラーのメトリクスを記録
            statsCollector?.record(RequestMetrics(
                url = "", success = false, errorCode = errorCode, durationMs = 0, responseBytes = 0
            ))
            val status = if (authResult.code == 401) Status.UNAUTHORIZED else Status.FORBIDDEN
            return errorResponse(status, errorCode, authResult.message, retryable = false, category = "client")
        }

        // [2026-03-12] レートリミッタ削除: API Key 認証 + Semaphore バックプレッシャーで制御
        // NanoHTTPd スレッド/リクエスト → Semaphore ブロック → WebView 空き次第処理

        // [2026-05-18] 過負荷防御: 処理中リクエストが上限を超えていたら即 503 を返す
        val concurrentLimit = maxOf(pool.currentSize * 8, 32)
        if (pendingCount.get() >= concurrentLimit) {
            onLog("過負荷により 503 を返却 (pending=${pendingCount.get()}, limit=$concurrentLimit)")
            statsCollector?.record(RequestMetrics(
                url = "", success = false, errorCode = "server_busy", durationMs = 0, responseBytes = 0
            ))
            return errorResponse(
                statusServiceUnavailable, "server_busy",
                "サーバーが過負荷状態です。時間をおいて再試行してください",
                retryable = true, category = "bridge"
            )
        }

        // リクエストボディ解析
        val body = try {
            readBody(session)
        } catch (e: RequestTooLargeException) {
            // [2026-06-10] Codex#3: Payload Too Large。413 を JSON で明示返却
            return errorResponse(
                statusPayloadTooLarge, "payload_too_large",
                e.message ?: "リクエストボディが上限を超えています",
                retryable = false, category = "client"
            )
        }
        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request", "JSON パースエラー",
                retryable = false, category = "client"
            )
        }

        // [2026-05-18] フィールドの型不正（asString/asInt の例外）も bad_request 扱いにする。
        // 例: {"url":{}} や {"wait":"abc"} は型不正で例外を投げるため、ここで捕捉して 400 を返す。
        val url: String?
        val mode: String?
        val requestedWait: Int
        val maxLength: Int
        val requestedTimeout: Int
        // [2026-06-20] user_agent: リクエスト毎の WebView User-Agent 上書き（null/空文字なら WebView デフォルト）
        val userAgent: String?
        // [2026-06-26] purpose: リクエストの用途識別子（"healthcheck" 等）。
        //   チャレンジ画面の表示モード判定（EXCLUDE_HEALTHCHECK）に利用。
        val purpose: String?
        try {
            url = json.get("url")?.asString
            mode = json.get("mode")?.asString
            requestedWait = json.get("wait")?.asInt ?: 3
            maxLength = json.get("max_length")?.asInt ?: 50000
            requestedTimeout = json.get("timeout")?.asInt ?: 30
            // [2026-06-20] `"user_agent": null` を仕様どおり「指定なし」として扱う（JsonNull.asString が
            //   例外を投げて 400 になる Codex 指摘への対応）。
            userAgent = json.get("user_agent")?.takeUnless { it.isJsonNull }?.asString
            purpose = json.get("purpose")?.takeUnless { it.isJsonNull }?.asString
        } catch (e: Exception) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request",
                "リクエストパラメータの型が不正です: ${e.message}",
                retryable = false, category = "client"
            )
        }

        if (url.isNullOrBlank()) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request", "url は必須です",
                retryable = false, category = "client"
            )
        }

        // [2026-06-10] Codex#2: SSRF 対策。scheme/host を allow list で検証する。
        val urlDecision = UrlPolicy.check(url)
        if (urlDecision is UrlPolicy.Decision.Deny) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request",
                urlDecision.reason,
                retryable = false, category = "client"
            )
        }

        if (mode != null && mode != "text" && mode != "dom") {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request",
                "mode は \"text\" または \"dom\" を指定してください",
                retryable = false, category = "client"
            )
        }

        // [2026-06-10] Codex#3: 各パラメータの下限・上限バリデーション
        // - wait: 0..maxWait（0 は許容、負値は拒否）
        // - timeout: 1..maxTimeout（0/負値は即タイムアウトを誘発するので拒否）
        // - max_length: 0..MAX_MAX_LENGTH（負値は substring 例外、巨大値は OOM 誘発）
        if (requestedWait < 0) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request",
                "wait は 0 以上を指定してください",
                retryable = false, category = "client"
            )
        }
        if (requestedTimeout < 1) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request",
                "timeout は 1 以上を指定してください",
                retryable = false, category = "client"
            )
        }
        if (requestedTimeout > MAX_TIMEOUT_HARD_LIMIT) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request",
                "timeout は ${MAX_TIMEOUT_HARD_LIMIT} 以下を指定してください",
                retryable = false, category = "client"
            )
        }
        if (maxLength < 0 || maxLength > MAX_MAX_LENGTH) {
            return errorResponse(
                Status.BAD_REQUEST, "bad_request",
                "max_length は 0..${MAX_MAX_LENGTH} の範囲で指定してください",
                retryable = false, category = "client"
            )
        }
        // [2026-06-20] user_agent のバリデーション: 長さ上限 + HTTP ヘッダ汚染になる制御文字を拒否
        if (userAgent != null) {
            if (userAgent.length > MAX_USER_AGENT_LEN) {
                return errorResponse(
                    Status.BAD_REQUEST, "bad_request",
                    "user_agent は ${MAX_USER_AGENT_LEN} 文字以内で指定してください",
                    retryable = false, category = "client"
                )
            }
            // [2026-06-20] HTTP ヘッダ汚染対策。RFC 的な制御文字（0x00-0x1F + 0x7F DEL）を一切拒否。
            if (userAgent.any { it.code < 0x20 || it.code == 0x7F }) {
                return errorResponse(
                    Status.BAD_REQUEST, "bad_request",
                    "user_agent に制御文字（CR/LF/DEL 等）を含めることはできません",
                    retryable = false, category = "client"
                )
            }
        }

        val fetchMode = mode ?: "text"

        // [2026-03-11] タイムアウト・wait のクランプ（サーバー側上限を超えないよう制限）
        val effectiveTimeout = minOf(requestedTimeout, maxTimeout)
        val effectiveWait = minOf(requestedWait, maxWait)
        val timeoutClamped = requestedTimeout > maxTimeout
        val waitClamped = requestedWait > maxWait

        val startMs = System.currentTimeMillis()
        onLog("リクエスト受信: $url (timeout=${effectiveTimeout}s, wait=${effectiveWait}s, pool=${pool.availableCount}/${pool.currentSize})")

        pendingCount.incrementAndGet()
        metricRequests.incrementAndGet()

        // [2026-03-11] WebViewPool が並行数を Semaphore で制御するため、直接 fetch を呼ぶ
        // NanoHTTPd は各リクエストを独自スレッドで処理するので、ここでブロッキング OK
        statsCollector?.onProcessingStart()
        // [2026-05-18] メトリクス会計は finally で一度だけ行う（二重加算防止）
        var resultError: String? = null
        try {
            val request = FetchRequest(
                url = url,
                wait = effectiveWait,
                maxLength = maxLength,
                timeout = effectiveTimeout,
                mode = fetchMode,
                userAgent = userAgent?.takeIf { it.isNotBlank() },
                purpose = purpose?.takeIf { it.isNotBlank() },
            )
            val fetchResult = pool.fetch(request)
            val elapsed = System.currentTimeMillis() - startMs

            if (fetchResult.ok) {
                onLog("成功 [$fetchMode]: $url — ${fetchResult.text.length}文字 (${elapsed}ms, http=${fetchResult.httpStatus})")
                val resp = mutableMapOf<String, Any?>(
                    "ok" to true,
                    "mode" to fetchResult.mode,
                    "url" to url,
                    "final_url" to fetchResult.finalUrl,
                    "title" to fetchResult.title,
                    "text" to fetchResult.text,
                    "length" to fetchResult.text.length,
                    "elapsed_ms" to elapsed,
                    // [2026-06-27] origin HTTP status code (デフォルト 200)
                    "http_status" to fetchResult.httpStatus,
                )
                if (fetchMode == "dom" && fetchResult.dom != null) {
                    resp["dom"] = JsonParser.parseString(fetchResult.dom)
                }
                // [2026-03-11] クランプ情報をレスポンスに含める
                if (timeoutClamped || waitClamped) {
                    val clampedInfo = mutableMapOf<String, Any>()
                    if (timeoutClamped) {
                        clampedInfo["timeout"] = mapOf("requested" to requestedTimeout, "applied" to effectiveTimeout)
                    }
                    if (waitClamped) {
                        clampedInfo["wait"] = mapOf("requested" to requestedWait, "applied" to effectiveWait)
                    }
                    resp["clamped"] = clampedInfo
                }
                val responseJson = gson.toJson(resp)
                statsCollector?.record(RequestMetrics(
                    url = url,
                    success = true,
                    durationMs = elapsed,
                    responseBytes = responseJson.toByteArray().size.toLong()
                ))
                return jsonResponse(Status.OK, resp)
            } else {
                // [2026-05-18] エラー種別を HTTP ステータスへマッピング（500 は使わない）
                //   Bridge 自身の一時障害 → 503 + Retry-After（リトライ可）
                //   対象 URL 個別の取得失敗 → 200 + ok:false（n8n が ok を見て個別判定）
                val errCode = fetchResult.error ?: "unknown"
                resultError = errCode
                val (status, retryable, category) = classifyError(errCode)
                onLog("エラー: $url — $errCode (${elapsed}ms, status=${status.requestStatus}, http=${fetchResult.httpStatus})")

                val errorResp = mutableMapOf<String, Any?>(
                    "ok" to false,
                    "error" to errCode,
                    "message" to (fetchResult.message ?: ""),
                    "retryable" to retryable,
                    "category" to category,
                    // [2026-06-27] WebView の onReceivedHttpError で捕捉した origin HTTP status code
                    "http_status" to fetchResult.httpStatus,
                )
                // [2026-06-26] circuit_open は remaining_seconds をクライアントが利用するため添付
                if (errCode == "circuit_open") {
                    val host = try { java.net.URI(url).host?.lowercase() } catch (_: Exception) { null }
                    val remainingMs = if (host != null)
                        jp.salesnow.chromebridge.fetcher.GoogleSearchCircuitBreaker.remainingTripMs(host)
                    else 0L
                    // Codex 指摘: 切り捨てで 0 になるとクライアントが即リトライしてしまうため ceil
                    errorResp["remaining_seconds"] = ((remainingMs + 999) / 1000).coerceAtLeast(0)
                }
                val responseJson = gson.toJson(errorResp)
                statsCollector?.record(RequestMetrics(
                    url = url,
                    success = false,
                    errorCode = fetchResult.error,
                    durationMs = elapsed,
                    responseBytes = responseJson.toByteArray().size.toLong()
                ))
                val resp = jsonResponse(status, errorResp)
                if (status.requestStatus == 503 && retryable) {
                    resp.addHeader("Retry-After", retryAfterSeconds().toString())
                }
                return resp
            }
        } catch (e: Exception) {
            resultError = "internal_error"
            val elapsed = System.currentTimeMillis() - startMs
            onLog("想定外エラー: $url — ${e.message} (${elapsed}ms)")
            val errorResp = mapOf<String, Any?>(
                "ok" to false,
                "error" to "internal_error",
                "message" to (e.message ?: "内部エラー"),
                "retryable" to true,
                "category" to "bridge"
            )
            statsCollector?.record(RequestMetrics(
                url = url,
                success = false,
                errorCode = "internal_error",
                durationMs = elapsed,
                responseBytes = gson.toJson(errorResp).toByteArray().size.toLong()
            ))
            val resp = jsonResponse(statusServiceUnavailable, errorResp)
            resp.addHeader("Retry-After", retryAfterSeconds().toString())
            return resp
        } finally {
            statsCollector?.onProcessingEnd()
            pendingCount.decrementAndGet()
            // [2026-05-18] elapsed・error はここで一度だけ計上する（二重加算防止）
            metricElapsedMs.addAndGet(System.currentTimeMillis() - startMs)
            if (resultError != null) {
                metricErrors.incrementAndGet()
                if (resultError == "timeout") metricTimeouts.incrementAndGet()
            }
        }
    }

    /**
     * [2026-05-18] error コードを (HTTPステータス, リトライ可否, カテゴリ) に分類する。
     * 原則: Bridge 自身の一時障害は 503、対象 URL 個別の失敗は 200+ok:false。500 は使わない。
     */
    private fun classifyError(error: String): Triple<NanoHTTPD.Response.IStatus, Boolean, String> =
        when (error) {
            // Bridge 自身の一時的な過負荷・障害 → 503（リトライ可）
            "pool_busy", "server_busy", "renderer_gone", "internal_error" ->
                Triple(statusServiceUnavailable, true, "bridge")
            // [2026-06-26] Google サーキットブレーカー open → 499（しばらく停止中）
            "circuit_open" ->
                Triple(statusCircuitOpen, false, "blocked")
            // [2026-06-26] throttle 待機中の割り込み → 503（リトライ可）
            "interrupted" ->
                Triple(statusServiceUnavailable, true, "bridge")
            // 対象 URL 個別の取得失敗 → 200 + ok:false（リトライ可、要判定）
            "timeout", "fetch_failed" ->
                Triple(Status.OK, true, "target")
            // 対象ページの構造起因 → 200 + ok:false（同じ URL の再試行は非推奨）
            "extract_failed", "parse_failed" ->
                Triple(Status.OK, false, "target")
            // 未知のエラーは Bridge 一時障害として扱う
            else ->
                Triple(statusServiceUnavailable, true, "bridge")
        }

    /**
     * [2026-05-18] Retry-After に入れる秒数。処理中リクエスト数に応じて段階的に伸ばす。
     */
    private fun retryAfterSeconds(): Int {
        val n = pendingCount.get()
        return when {
            n <= 4 -> 2
            n <= 16 -> 5
            else -> 10
        }
    }

    private fun handleStatus(): Response {
        val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
        val reqs = metricRequests.get()
        val avgElapsed = if (reqs > 0) metricElapsedMs.get() / reqs else 0L
        return jsonResponse(Status.OK, mapOf(
            "webview_ready" to true,
            "pending_requests" to pendingCount.get(),
            // [2026-05-18] queue_depth は pending_requests と同義（処理中＋プール待ち）
            "queue_depth" to pendingCount.get(),
            "pool_size" to pool.currentSize,
            "pool_available" to pool.availableCount,
            "max_timeout" to maxTimeout,
            "max_wait" to maxWait,
            "uptime_seconds" to uptimeSeconds,
            // [2026-05-18] 起動来の累計メトリクス
            "total_requests" to reqs,
            "total_errors" to metricErrors.get(),
            "total_timeouts" to metricTimeouts.get(),
            "avg_elapsed_ms" to avgElapsed
        ))
    }

    // [2026-06-10] OTA: Portal の notify から呼ばれる「今すぐ manifest をチェック」
    //   認証は /fetch と同じ API キー（Bearer）。fire-and-forget で 200 を返し、
    //   実際の DL / install は別スレッドで実行される（Service の callback 内で）。
    //   既に実行中なら 200 { ok:true, skipped:"already_running" } を返す（Codex#2）。
    private fun handleUpdateCheck(session: IHTTPSession): Response {
        val authResult = auth.check(
            session.headers["authorization"],
            session.remoteIpAddress
        )
        if (authResult is AuthMiddleware.AuthResult.Error) {
            val status = if (authResult.code == 401) Status.UNAUTHORIZED else Status.FORBIDDEN
            val code = if (authResult.code == 401) "unauthorized" else "forbidden"
            return errorResponse(status, code, authResult.message, retryable = false, category = "client")
        }
        val cb = onUpdateCheck
        if (cb == null) {
            return errorResponse(
                statusServiceUnavailable, "no_handler",
                "更新ハンドラ未登録", retryable = false, category = "bridge"
            )
        }
        val started = cb.invoke()
        return jsonResponse(
            Status.OK,
            if (started) mapOf("ok" to true) else mapOf("ok" to true, "skipped" to "already_running")
        )
    }

    // [2026-03-11] GET /stats?period=daily&limit=30 or period=monthly&limit=12
    private fun handleStats(session: IHTTPSession): Response {
        // [2026-03-11] 認証チェック（/fetch と同様）
        val authResult = auth.check(
            session.headers["authorization"],
            session.remoteIpAddress
        )
        if (authResult is AuthMiddleware.AuthResult.Error) {
            val errorCode = if (authResult.code == 401) "unauthorized" else "forbidden"
            val status = if (authResult.code == 401) Status.UNAUTHORIZED else Status.FORBIDDEN
            return errorResponse(status, errorCode, authResult.message, retryable = false, category = "client")
        }

        val repo = statsRepository
            ?: return errorResponse(
                statusServiceUnavailable, "stats_unavailable",
                "統計機能が初期化されていません", retryable = false, category = "bridge"
            )

        val params = session.parms ?: emptyMap()
        val period = params["period"] ?: "daily"
        val limit = (params["limit"]?.toIntOrNull() ?: if (period == "monthly") 12 else 30).coerceIn(1, 90)

        return try {
            when (period) {
                "daily" -> {
                    val data = repo.getDailyStats(limit)
                    val today = repo.getTodayStats()
                    jsonResponse(Status.OK, mapOf(
                        "ok" to true,
                        "period" to "daily",
                        "today" to today?.let { mapDailyStats(it) },
                        "data" to data.map { mapDailyStats(it) }
                    ))
                }
                "monthly" -> {
                    val data = repo.getMonthlyStats(limit)
                    val current = repo.getCurrentMonthStats()
                    jsonResponse(Status.OK, mapOf(
                        "ok" to true,
                        "period" to "monthly",
                        "current_month" to current?.let { mapMonthlyStats(it) },
                        "data" to data.map { mapMonthlyStats(it) }
                    ))
                }
                else -> errorResponse(
                    Status.BAD_REQUEST, "bad_request",
                    "period は \"daily\" または \"monthly\" を指定してください",
                    retryable = false, category = "client"
                )
            }
        } catch (e: Exception) {
            errorResponse(
                statusServiceUnavailable, "internal_error",
                e.message ?: "統計取得エラー", retryable = true, category = "bridge"
            )
        }
    }

    private fun mapDailyStats(s: jp.salesnow.chromebridge.data.DailyStatsData): Map<String, Any?> = mapOf(
        "date" to s.dateKey,
        "total_requests" to s.totalRequests,
        "success_count" to s.successCount,
        "error_count" to s.errorCount,
        "success_rate" to s.successRate,
        "total_bytes" to s.totalBytes,
        "avg_duration_ms" to s.avgDurationMs,
        "max_duration_ms" to s.maxDurationMs,
        "peak_concurrency" to s.peakConcurrency,
        "errors" to mapOf(
            "timeout" to s.errorTimeout,
            "fetch" to s.errorFetch,
            "queue" to s.errorQueue,
            "auth" to s.errorAuth,
            "rate" to s.errorRate,
            "parse" to s.errorParse
        )
    )

    private fun mapMonthlyStats(s: jp.salesnow.chromebridge.data.MonthlyStatsData): Map<String, Any?> = mapOf(
        "month" to s.monthKey,
        "total_requests" to s.totalRequests,
        "success_count" to s.successCount,
        "error_count" to s.errorCount,
        "success_rate" to s.successRate,
        "total_bytes" to s.totalBytes,
        "avg_duration_ms" to s.avgDurationMs,
        "max_duration_ms" to s.maxDurationMs
    )

    // [2026-03-11] InputStream.read() は1回で全バイト読めない場合があるためループで確実に読む
    // [2026-06-10] Codex#3: Content-Length 上限を設けて OOM 攻撃を防止する。
    //   超過時は例外で抜けて handleFetch の catch が internal_error(503) を返す。
    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return "{}"
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            throw RequestTooLargeException(
                "リクエストボディが上限を超えています ($contentLength > $MAX_REQUEST_BODY_BYTES bytes)"
            )
        }
        val buf = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = session.inputStream.read(buf, offset, contentLength - offset)
            if (read < 0) break
            offset += read
        }
        return String(buf, 0, offset)
    }

    private class RequestTooLargeException(message: String) : RuntimeException(message)

    // [2026-05-18] Status enum とカスタム IStatus（503 等）の両方を受け付ける
    private fun jsonResponse(status: NanoHTTPD.Response.IStatus, data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }

    /**
     * [2026-05-18] 構造化エラーレスポンスを生成する。
     * 全エラー応答に retryable（再試行可否）と category（bridge/target/client）を付与する。
     * リトライ可能な 503 には Retry-After ヘッダを付ける。
     */
    private fun errorResponse(
        status: NanoHTTPD.Response.IStatus,
        error: String,
        message: String,
        retryable: Boolean,
        category: String
    ): Response {
        val body = mapOf(
            "ok" to false,
            "error" to error,
            "message" to message,
            "retryable" to retryable,
            "category" to category
        )
        val resp = jsonResponse(status, body)
        if (status.requestStatus == 503 && retryable) {
            resp.addHeader("Retry-After", retryAfterSeconds().toString())
        }
        return resp
    }

    fun stopServer() {
        stop()
    }
}

// [2026-03-12] スレッドプール付き AsyncRunner
// NanoHTTPD デフォルトは無制限にスレッド生成するため、上限を設けてクラッシュを防止
// [2026-05-18] 有界キュー化: 無制限キューだとリクエストが無限に積まれ OOM の恐れがある。
//   ArrayBlockingQueue にすることでキュー満杯時にスレッドが maxThreads まで増え、
//   それも飽和したら reject される。reject 時は接続を切断する
//   （CallerRunsPolicy だと最大120秒の fetch を accept スレッドで実行し新規接続が全凍結するため不採用）。
private class BoundRunner(
    maxThreads: Int,
    private val onLog: (String) -> Unit
) : NanoHTTPD.AsyncRunner {
    private val executor = ThreadPoolExecutor(
        4, maxThreads, 60L, TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(maxThreads)
    ).apply {
        rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy()
    }
    private val running = java.util.Collections.synchronizedList(mutableListOf<NanoHTTPD.ClientHandler>())

    override fun closeAll() {
        // [2026-05-18] synchronizedList の安全なスナップショット取得
        val snapshot = synchronized(running) { running.toList() }
        snapshot.forEach { it.close() }
        executor.shutdown()
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        running.remove(clientHandler)
    }

    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        running.add(clientHandler)
        try {
            executor.execute(clientHandler)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // [2026-05-18] スレッドプール飽和 → 接続を切断（n8n 側はリトライで回復する）
            running.remove(clientHandler)
            onLog("過負荷によりリクエストをドロップ（スレッドプール飽和）")
            try { clientHandler.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            running.remove(clientHandler)
            onLog("スレッドプール実行エラー: ${e.message}")
            try { clientHandler.close() } catch (_: Exception) {}
        }
    }
}
