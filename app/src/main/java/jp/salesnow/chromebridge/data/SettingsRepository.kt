// Version: 1.2.0 | Updated: 2026-03-11
// [2026-03-08] Tunnel トークン設定を追加
// [2026-03-11] 並列処理・キュー・タイムアウト設定を追加
package jp.salesnow.chromebridge.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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

    // [2026-03-11] 並列処理数（WebView プール数）1-8、デフォルト 2
    var concurrency: Int
        get() = prefs.getInt("concurrency", 2).coerceIn(1, 8)
        set(value) = prefs.edit { putInt("concurrency", value.coerceIn(1, 8)) }

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
}
