// Version: 1.4.0 | Updated: 2026-03-11
// [2026-03-08] server/index.js (Express + ws) を NanoHTTPd で再実装
// API 仕様は完全互換: POST /fetch, GET /status
// [2026-03-08] Status import 修正、TOO_MANY_REQUESTS を INTERNAL_ERROR に置換
// [2026-03-11] StatsCollector によるメトリクス記録を追加
// [2026-03-11] 直列キュー → Semaphore ベース並列処理、タイムアウトクランプ
// [2026-03-11] GET /stats エンドポイント追加
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
import java.util.concurrent.atomic.AtomicInteger

class BridgeHttpServer(
    port: Int,
    private val pool: WebViewPool,
    private val auth: AuthMiddleware,
    private val rateLimiter: RateLimiter,
    private val maxQueueSize: Int = 20,
    private val maxTimeout: Int = 60,
    private val maxWait: Int = 10,
    private val statsCollector: StatsCollector? = null,
    private val statsRepository: StatsRepository? = null,
    private val onLog: (String) -> Unit = {}
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val startTime = System.currentTimeMillis()
    private val pendingCount = AtomicInteger(0)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            method == Method.POST && uri == "/fetch" -> handleFetch(session)
            method == Method.GET && uri == "/status" -> handleStatus()
            // [2026-03-11] 統計 API エンドポイント
            method == Method.GET && uri == "/stats" -> handleStats(session)
            else -> jsonResponse(Status.NOT_FOUND, mapOf("ok" to false, "error" to "not_found"))
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
            return jsonResponse(status, mapOf(
                "ok" to false, "error" to errorCode,
                "message" to authResult.message
            ))
        }

        // レート制限（NanoHTTPd に 429 がないため INTERNAL_ERROR で代替）
        if (!rateLimiter.isAllowed(session.remoteIpAddress)) {
            // [2026-03-11] レートリミットのメトリクスを記録
            statsCollector?.record(RequestMetrics(
                url = "", success = false, errorCode = "rate_limit", durationMs = 0, responseBytes = 0
            ))
            return jsonResponse(Status.INTERNAL_ERROR, mapOf(
                "ok" to false, "error" to "rate_limit",
                "message" to "リクエスト数が上限を超えました（1分あたり20件）"
            ))
        }

        // リクエストボディ解析
        val body = readBody(session)
        val json = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            return jsonResponse(Status.BAD_REQUEST, mapOf(
                "ok" to false, "error" to "bad_request", "message" to "JSON パースエラー"
            ))
        }

        val url = json.get("url")?.asString
        if (url.isNullOrBlank()) {
            return jsonResponse(Status.BAD_REQUEST, mapOf(
                "ok" to false, "error" to "bad_request", "message" to "url は必須です"
            ))
        }

        val mode = json.get("mode")?.asString
        if (mode != null && mode != "text" && mode != "dom") {
            return jsonResponse(Status.BAD_REQUEST, mapOf(
                "ok" to false, "error" to "bad_request",
                "message" to "mode は \"text\" または \"dom\" を指定してください"
            ))
        }

        // [2026-03-11] キュー上限チェック（設定可能な maxQueueSize を使用）
        val totalPending = pendingCount.get()
        if (totalPending >= maxQueueSize) {
            statsCollector?.record(RequestMetrics(
                url = url, success = false, errorCode = "queue_full", durationMs = 0, responseBytes = 0
            ))
            return jsonResponse(Status.INTERNAL_ERROR, mapOf(
                "ok" to false, "error" to "queue_full",
                "message" to "キューが上限（${maxQueueSize}件）に達しています"
            ))
        }

        val requestedWait = json.get("wait")?.asInt ?: 3
        val maxLength = json.get("max_length")?.asInt ?: 50000
        val requestedTimeout = json.get("timeout")?.asInt ?: 30
        val fetchMode = mode ?: "text"

        // [2026-03-11] タイムアウト・wait のクランプ（サーバー側上限を超えないよう制限）
        val effectiveTimeout = minOf(requestedTimeout, maxTimeout)
        val effectiveWait = minOf(requestedWait, maxWait)
        val timeoutClamped = requestedTimeout > maxTimeout
        val waitClamped = requestedWait > maxWait

        val startMs = System.currentTimeMillis()
        onLog("リクエスト受信: $url (timeout=${effectiveTimeout}s, wait=${effectiveWait}s, pool=${pool.availableCount}/${pool.currentSize})")

        pendingCount.incrementAndGet()

        // [2026-03-11] WebViewPool が並行数を Semaphore で制御するため、直接 fetch を呼ぶ
        // NanoHTTPd は各リクエストを独自スレッドで処理するので、ここでブロッキング OK
        statsCollector?.onProcessingStart()
        try {
            val request = FetchRequest(url, effectiveWait, maxLength, effectiveTimeout, fetchMode)
            val fetchResult = pool.fetch(request)
            val elapsed = System.currentTimeMillis() - startMs

            if (fetchResult.ok) {
                onLog("成功 [$fetchMode]: $url — ${fetchResult.text.length}文字 (${elapsed}ms)")
                val resp = mutableMapOf<String, Any?>(
                    "ok" to true,
                    "mode" to fetchResult.mode,
                    "url" to url,
                    "final_url" to fetchResult.finalUrl,
                    "title" to fetchResult.title,
                    "text" to fetchResult.text,
                    "length" to fetchResult.text.length,
                    "elapsed_ms" to elapsed
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
                onLog("エラー: $url — ${fetchResult.error} (${elapsed}ms)")
                val errorResp = mapOf<String, Any?>(
                    "ok" to false,
                    "error" to (fetchResult.error ?: "unknown"),
                    "message" to (fetchResult.message ?: "")
                )
                val responseJson = gson.toJson(errorResp)
                statsCollector?.record(RequestMetrics(
                    url = url,
                    success = false,
                    errorCode = fetchResult.error,
                    durationMs = elapsed,
                    responseBytes = responseJson.toByteArray().size.toLong()
                ))
                return jsonResponse(Status.INTERNAL_ERROR, errorResp)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startMs
            val errorResp = mapOf(
                "ok" to false, "error" to "internal_error", "message" to e.message
            )
            statsCollector?.record(RequestMetrics(
                url = url,
                success = false,
                errorCode = "internal_error",
                durationMs = elapsed,
                responseBytes = gson.toJson(errorResp).toByteArray().size.toLong()
            ))
            return jsonResponse(Status.INTERNAL_ERROR, errorResp)
        } finally {
            statsCollector?.onProcessingEnd()
            pendingCount.decrementAndGet()
        }
    }

    private fun handleStatus(): Response {
        val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
        return jsonResponse(Status.OK, mapOf(
            "webview_ready" to true,
            "pending_requests" to pendingCount.get(),
            "pool_size" to pool.currentSize,
            "pool_available" to pool.availableCount,
            "max_queue_size" to maxQueueSize,
            "max_timeout" to maxTimeout,
            "max_wait" to maxWait,
            "uptime_seconds" to uptimeSeconds
        ))
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
            return jsonResponse(status, mapOf(
                "ok" to false, "error" to errorCode, "message" to authResult.message
            ))
        }

        val repo = statsRepository
            ?: return jsonResponse(Status.INTERNAL_ERROR, mapOf(
                "ok" to false, "error" to "stats_unavailable", "message" to "統計機能が初期化されていません"
            ))

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
                else -> jsonResponse(Status.BAD_REQUEST, mapOf(
                    "ok" to false, "error" to "bad_request",
                    "message" to "period は \"daily\" または \"monthly\" を指定してください"
                ))
            }
        } catch (e: Exception) {
            jsonResponse(Status.INTERNAL_ERROR, mapOf(
                "ok" to false, "error" to "internal_error", "message" to e.message
            ))
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
    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return "{}"
        val buf = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = session.inputStream.read(buf, offset, contentLength - offset)
            if (read < 0) break
            offset += read
        }
        return String(buf, 0, offset)
    }

    private fun jsonResponse(status: Status, data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }

    fun stopServer() {
        stop()
    }
}
