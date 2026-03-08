// Version: 1.1.0 | Updated: 2026-03-08
// [2026-03-08] Tunnel トークン設定を追加
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
}
