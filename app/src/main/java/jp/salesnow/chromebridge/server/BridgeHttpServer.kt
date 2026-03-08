// Version: 1.0.1 | Updated: 2026-03-08
// [2026-03-08] server/index.js (Express + ws) を NanoHTTPd で再実装
// API 仕様は完全互換: POST /fetch, GET /status
// [2026-03-08] Status import 修正、TOO_MANY_REQUESTS を INTERNAL_ERROR に置換
package jp.salesnow.chromebridge.server

import com.google.gson.Gson
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import jp.salesnow.chromebridge.fetcher.FetchRequest
import jp.salesnow.chromebridge.fetcher.HeadlessWebViewFetcher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BridgeHttpServer(
    port: Int,
    private val fetcher: HeadlessWebViewFetcher,
    private val auth: AuthMiddleware,
    private val rateLimiter: RateLimiter,
    private val onLog: (String) -> Unit = {}
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val startTime = System.currentTimeMillis()
    private val processing = AtomicBoolean(false)
    private val queueSize = AtomicInteger(0)

    companion object {
        const val MAX_QUEUE_SIZE = 10
    }

    // [2026-03-08] server/index.js L77-100 の直列キューを Kotlin で再実装
    private val queue = LinkedBlockingQueue<() -> Unit>(MAX_QUEUE_SIZE)
    private val queueThread = Thread {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val task = queue.take()
                processing.set(true)
                task()
                processing.set(false)
                queueSize.decrementAndGet()
            } catch (_: InterruptedException) {
                break
            }
        }
    }.apply {
        isDaemon = true
        name = "bridge-queue"
        start()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            method == Method.POST && uri == "/fetch" -> handleFetch(session)
            method == Method.GET && uri == "/status" -> handleStatus()
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
            val status = if (authResult.code == 401) Status.UNAUTHORIZED else Status.FORBIDDEN
            return jsonResponse(status, mapOf(
                "ok" to false, "error" to if (authResult.code == 401) "unauthorized" else "forbidden",
                "message" to authResult.message
            ))
        }

        // レート制限（NanoHTTPd に 429 がないため INTERNAL_ERROR で代替）
        if (!rateLimiter.isAllowed(session.remoteIpAddress)) {
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

        // キュー上限チェック
        val totalPending = queueSize.get() + if (processing.get()) 1 else 0
        if (totalPending >= MAX_QUEUE_SIZE) {
            return jsonResponse(Status.INTERNAL_ERROR, mapOf(
                "ok" to false, "error" to "queue_full",
                "message" to "キューが上限（${MAX_QUEUE_SIZE}件）に達しています"
            ))
        }

        val wait = json.get("wait")?.asInt ?: 3
        val maxLength = json.get("max_length")?.asInt ?: 50000
        val timeout = json.get("timeout")?.asInt ?: 30
        val fetchMode = mode ?: "text"

        val startMs = System.currentTimeMillis()
        onLog("リクエスト受信: $url")

        // 同期的にキューで処理（NanoHTTPd はスレッドプールで各リクエストを処理）
        val request = FetchRequest(url, wait, maxLength, timeout, fetchMode)
        val result = CompletableFuture<Map<String, Any?>>()

        val offered = queue.offer {
            try {
                val fetchResult = fetcher.fetch(request)
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
                    result.complete(resp)
                } else {
                    onLog("エラー: $url — ${fetchResult.error} (${elapsed}ms)")
                    result.complete(mapOf(
                        "ok" to false,
                        "error" to (fetchResult.error ?: "unknown"),
                        "message" to (fetchResult.message ?: "")
                    ))
                }
            } catch (e: Exception) {
                result.complete(mapOf(
                    "ok" to false, "error" to "internal_error", "message" to e.message
                ))
            }
        }

        if (!offered) {
            return jsonResponse(Status.INTERNAL_ERROR, mapOf(
                "ok" to false, "error" to "queue_full",
                "message" to "キューが上限（${MAX_QUEUE_SIZE}件）に達しています"
            ))
        }

        queueSize.incrementAndGet()

        // タイムアウト付きで結果を待つ
        val responseMap = try {
            result.get(timeout.toLong() + 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to "timeout", "message" to "${timeout}秒でタイムアウトしました")
        }

        val status = when {
            responseMap["ok"] == true -> Status.OK
            else -> Status.INTERNAL_ERROR
        }

        return jsonResponse(status, responseMap)
    }

    private fun handleStatus(): Response {
        val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
        return jsonResponse(Status.OK, mapOf(
            "webview_ready" to true,
            "pending_requests" to (queueSize.get() + if (processing.get()) 1 else 0),
            "uptime_seconds" to uptimeSeconds
        ))
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return "{}"
        val buf = ByteArray(contentLength)
        session.inputStream.read(buf, 0, contentLength)
        return String(buf)
    }

    private fun jsonResponse(status: Status, data: Any): Response {
        val json = gson.toJson(data)
        return newFixedLengthResponse(status, "application/json", json)
    }

    fun stopServer() {
        queueThread.interrupt()
        stop()
    }
}
