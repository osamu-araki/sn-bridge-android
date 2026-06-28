// Version: 1.6.0 | Updated: 2026-06-26
// [2026-06-26] デフォルト User-Agent override + 同一ホストへの最小リクエスト間隔を追加
//   (Google 403 等のクライアント単位ブロックの予防策)
// [2026-06-25] Challenge 自動タップ記憶 (saveTapMemory / getTapMemory / clearTapMemory) を追加
// [2026-03-08] Tunnel トークン設定を追加
// [2026-03-11] 並列処理・キュー・タイムアウト設定を追加
// [2026-06-10] OTA アップデート設定（Portal manifest URL / トークン / 自動チェック）を追加
// [2026-06-10] OTA 設定の空欄時 BuildConfig fallback（CI ビルドで埋め込んだデフォルト）
package jp.salesnow.chromebridge.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import jp.salesnow.chromebridge.BuildConfig

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("chrome_bridge_settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 3000)
        set(value) = prefs.edit { putInt("port", value) }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit { putString("api_key", value) }

    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(value) = prefs.edit { putBoolean("auto_start", value) }

    var tunnelDomain: String
        get() = prefs.getString("tunnel_domain", "") ?: ""
        set(value) = prefs.edit { putString("tunnel_domain", value) }

    // [2026-03-08] cloudflared tunnel token で取得したトークン
    var tunnelToken: String
        get() = prefs.getString("tunnel_token", "") ?: ""
        set(value) = prefs.edit { putString("tunnel_token", value) }

    // [2026-03-12] 並列処理数（WebView プール数）1-4、デフォルト 2
    // 8 だとメモリ圧迫で cloudflared が不安定になるため上限を4に制限
    var concurrency: Int
        get() = prefs.getInt("concurrency", 2).coerceIn(1, 4)
        set(value) = prefs.edit { putInt("concurrency", value.coerceIn(1, 4)) }

    // [2026-03-11] キューサイズ上限 5-100、デフォルト 20
    var queueSize: Int
        get() = prefs.getInt("queue_size", 20).coerceIn(5, 100)
        set(value) = prefs.edit { putInt("queue_size", value.coerceIn(5, 100)) }

    // [2026-03-11] サーバー側タイムアウト上限（秒）10-120、デフォルト 60
    var maxTimeout: Int
        get() = prefs.getInt("max_timeout", 60).coerceIn(10, 120)
        set(value) = prefs.edit { putInt("max_timeout", value.coerceIn(10, 120)) }

    // [2026-03-11] サーバー側 wait 上限（秒）1-30、デフォルト 10
    var maxWait: Int
        get() = prefs.getInt("max_wait", 10).coerceIn(1, 30)
        set(value) = prefs.edit { putInt("max_wait", value.coerceIn(1, 30)) }

    // [2026-03-13] チャレンジ認証のバックグラウンド通知（ON: 通知で画面表示、OFF: 通知なし）
    var challengeNotify: Boolean
        get() = prefs.getBoolean("challenge_notify", true)
        set(value) = prefs.edit { putBoolean("challenge_notify", value) }

    // [2026-03-13] Slack Incoming Webhook URL（チャレンジ検知時に通知）
    var slackWebhookUrl: String
        get() = prefs.getString("slack_webhook_url", "") ?: ""
        set(value) = prefs.edit { putString("slack_webhook_url", value) }

    // [2026-03-14] 詳細ログモード（false=簡易、true=詳細）
    var verboseLog: Boolean
        get() = prefs.getBoolean("verbose_log", false)
        set(value) = prefs.edit { putBoolean("verbose_log", value) }

    // [2026-03-14] ログ自動バックアップ（SAF フォルダへローテーション時にコピー）
    var logAutoBackup: Boolean
        get() = prefs.getBoolean("log_auto_backup", false)
        set(value) = prefs.edit { putBoolean("log_auto_backup", value) }

    // [2026-03-14] バックアップ先フォルダの URI（SAF で選択）
    var logBackupUri: String
        get() = prefs.getString("log_backup_uri", "") ?: ""
        set(value) = prefs.edit { putString("log_backup_uri", value) }

    // [2026-06-10] OTA: Portal manifest エンドポイント
    //   端末で未保存 / 空文字なら BuildConfig のデフォルト値（CI ビルド時に埋め込み）に fallback
    var portalManifestUrl: String
        get() = (prefs.getString("portal_manifest_url", "") ?: "")
            .ifBlank { BuildConfig.DEFAULT_PORTAL_MANIFEST_URL }
        set(value) = prefs.edit { putString("portal_manifest_url", value) }

    // [2026-06-10] OTA: manifest 認証用 Bearer トークン（Portal の system_settings に同じ値）
    //   同上、BuildConfig fallback
    var portalCheckToken: String
        get() = (prefs.getString("portal_check_token", "") ?: "")
            .ifBlank { BuildConfig.DEFAULT_PORTAL_CHECK_TOKEN }
        set(value) = prefs.edit { putString("portal_check_token", value) }

    // [2026-06-10] OTA: 自動チェック ON/OFF（デフォルト ON、間隔は固定 1 時間）
    var autoUpdateCheck: Boolean
        get() = prefs.getBoolean("auto_update_check", true)
        set(value) = prefs.edit { putBoolean("auto_update_check", value) }

    // [2026-06-10] Tunnel: cloudflared --edge に渡すエッジ IP リスト（"IP:PORT,IP:PORT,..."）。
    //   未指定なら TunnelManager.DEFAULT_CLOUDFLARED_EDGE_IPS に fallback。
    //   将来 Cloudflare 側でエッジ帯が変更された際に、Portal などから上書き可能にするための窓口。
    var cloudflaredEdgeIps: String
        get() = prefs.getString("cloudflared_edge_ips", "") ?: ""
        set(value) = prefs.edit { putString("cloudflared_edge_ips", value) }

    // [2026-06-26] Google サーキットブレーカー設定。GoogleSearchCircuitBreaker が参照する。
    //   閾値・window・停止時間をユーザーが UI から変更できるようにする（運用に応じてチューニング可）。
    var circuitFailureThreshold: Int
        get() = prefs.getInt("circuit_failure_threshold", 3).coerceAtLeast(1)
        set(value) = prefs.edit { putInt("circuit_failure_threshold", value.coerceAtLeast(1)) }

    var circuitWindowMinutes: Int
        get() = prefs.getInt("circuit_window_minutes", 5).coerceAtLeast(1)
        set(value) = prefs.edit { putInt("circuit_window_minutes", value.coerceAtLeast(1)) }

    var circuitTripMinutes: Int
        get() = prefs.getInt("circuit_trip_minutes", 60).coerceAtLeast(1)
        set(value) = prefs.edit { putInt("circuit_trip_minutes", value.coerceAtLeast(1)) }

    // [2026-06-26] チャレンジ画面の表示モード（3 択）
    //   0 = MANUAL_ONLY: 手動タップが必要な時だけ表示（memory ありは invisible mode で
    //       自動突破、memory なしは Slack 通知のみ）
    //   1 = EXCLUDE_HEALTHCHECK: チャレンジ認証はすべて表示するが、ヘルスチェック (cron)
    //       由来のリクエストは表示しない（Slack 通知のみ）
    //   2 = ALL: すべて表示（デフォルト・従来挙動）
    //
    //   旧 `challenge_auto_tap_only_mode` (Boolean) からのマイグレーション:
    //   - true  → 0 (MANUAL_ONLY)
    //   - false → 2 (ALL)
    var challengeDisplayMode: Int
        get() {
            if (prefs.contains("challenge_display_mode")) {
                return prefs.getInt("challenge_display_mode", DISPLAY_MODE_ALL).coerceIn(0, 2)
            }
            // マイグレーション: 旧 Boolean フラグがあればそれを使う
            return if (prefs.getBoolean("challenge_auto_tap_only_mode", false))
                DISPLAY_MODE_MANUAL_ONLY
            else DISPLAY_MODE_ALL
        }
        set(value) = prefs.edit { putInt("challenge_display_mode", value.coerceIn(0, 2)) }

    companion object {
        const val DISPLAY_MODE_MANUAL_ONLY = 0
        const val DISPLAY_MODE_EXCLUDE_HEALTHCHECK = 1
        const val DISPLAY_MODE_ALL = 2
    }

    // [2026-06-26] Bridge が WebView で外部 URL を fetch する際のデフォルト User-Agent。
    //   空文字なら WebView 既定の Android UA を使用（従来挙動）。Google 等で `; wv` を含む
    //   WebView UA が flag されやすいため、デスクトップ Chrome UA に差し替えて回避する。
    //   FetchRequest.userAgent が指定されていればそちらを優先（per-request override は据え置き）。
    var defaultUserAgentOverride: String
        get() = prefs.getString("default_user_agent_override", "") ?: ""
        set(value) = prefs.edit { putString("default_user_agent_override", value) }

    // [2026-06-26] 同一ホストへの最小リクエスト間隔（ミリ秒）。0 で無効（throttle なし）。
    //   Google 等の bot 検出に頻度が引っかからないようにする防御策。並列リクエストでも
    //   host 単位で順次これだけの間隔が空くよう WebViewPool 側でガードする。
    //   推奨値: 2000〜5000ms（Google 検索）/ 0（通常用途）
    var minRequestIntervalMs: Long
        get() = prefs.getLong("min_request_interval_ms", 0L)
        set(value) = prefs.edit { putLong("min_request_interval_ms", value.coerceAtLeast(0L)) }

    // [2026-06-28] UA ローテーション: WebView プール内の WebView ごとに別の User-Agent を使う。
    //   同一 IP でも UA が異なれば Google から別エンティティとして扱われる効果がある。
    //   - false (default): defaultUserAgentOverride or WebView 既定 UA (従来挙動)
    //   - true: WebView スロット index ごとに UA_POOL から固定割り当て
    //   FetchRequest.userAgent (per-request override) は引き続き最優先。
    var userAgentRotation: Boolean
        get() = prefs.getBoolean("ua_rotation", false)
        set(value) = prefs.edit { putBoolean("ua_rotation", value) }

    // [2026-06-25] Challenge 自動タップ記憶。ドメイン → 最後にユーザーがタップした座標 (x, y) を
    //   JSON で保存。次回チャレンジ検知時にその座標で自動タップを試みる。
    //   {"example.com": {"x": 100.5, "y": 200.5, "ts": 1734567890000}, ...}
    private val tapMemoryKey = "challenge_tap_memory_json"

    /** ドメイン直下の (x, y) を保存。既存値は上書き。 */
    fun saveTapMemory(domain: String, x: Float, y: Float) {
        if (domain.isBlank()) return
        val root = try {
            prefs.getString(tapMemoryKey, null)?.let { org.json.JSONObject(it) }
                ?: org.json.JSONObject()
        } catch (_: Exception) {
            // JSON 壊れていたら捨てて新規作成
            org.json.JSONObject()
        }
        root.put(domain, org.json.JSONObject().apply {
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("ts", System.currentTimeMillis())
        })
        prefs.edit { putString(tapMemoryKey, root.toString()) }
    }

    data class TapCoord(val x: Float, val y: Float, val ts: Long)

    /** ドメインの保存済タップ座標を返す。未保存なら null。 */
    fun getTapMemory(domain: String): TapCoord? {
        if (domain.isBlank()) return null
        return try {
            val raw = prefs.getString(tapMemoryKey, null) ?: return null
            val obj = org.json.JSONObject(raw).optJSONObject(domain) ?: return null
            TapCoord(
                x = obj.optDouble("x").toFloat(),
                y = obj.optDouble("y").toFloat(),
                ts = obj.optLong("ts"),
            )
        } catch (_: Exception) { null }
    }

    /** 保存済みの全エントリを最終更新時刻の降順で返す（UI 一覧表示用）。 */
    fun listTapMemoryEntries(): List<Pair<String, TapCoord>> {
        return try {
            val raw = prefs.getString(tapMemoryKey, null) ?: return emptyList()
            val root = org.json.JSONObject(raw)
            val out = mutableListOf<Pair<String, TapCoord>>()
            val it = root.keys()
            while (it.hasNext()) {
                val key = it.next()
                val obj = root.optJSONObject(key) ?: continue
                out += key to TapCoord(
                    x = obj.optDouble("x").toFloat(),
                    y = obj.optDouble("y").toFloat(),
                    ts = obj.optLong("ts"),
                )
            }
            out.sortedByDescending { it.second.ts }
        } catch (_: Exception) { emptyList() }
    }

    /** ドメイン指定で削除（null なら全消去）。 */
    fun clearTapMemory(domain: String? = null) {
        try {
            if (domain == null) {
                prefs.edit { remove(tapMemoryKey) }
                return
            }
            val raw = prefs.getString(tapMemoryKey, null) ?: return
            val root = org.json.JSONObject(raw)
            root.remove(domain)
            prefs.edit { putString(tapMemoryKey, root.toString()) }
        } catch (_: Exception) {}
    }
}
