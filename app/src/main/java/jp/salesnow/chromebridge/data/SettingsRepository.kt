// Version: 1.4.0 | Updated: 2026-06-10
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
}
