// Version: 1.0.0 | Updated: 2026-03-08
// [2026-03-08] server/index.js L31-46 の認証ロジックを Kotlin で再実装
package jp.salesnow.chromebridge.server

/**
 * Bearer Token 認証
 * API Key 未設定時は localhost のみ許可
 */
class AuthMiddleware(private val getApiKey: () -> String) {

    sealed class AuthResult {
        data object Ok : AuthResult()
        data class Error(val code: Int, val message: String) : AuthResult()
    }

    fun check(authHeader: String?, remoteIp: String): AuthResult {
        val apiKey = getApiKey()

        // API Key 未設定時: localhost のみ許可
        if (apiKey.isEmpty()) {
            val isLocal = remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp == "0:0:0:0:0:0:0:1"
            return if (isLocal) AuthResult.Ok
            else AuthResult.Error(403, "API キー未設定のため localhost のみ許可")
        }

        // Bearer Token 検証
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return AuthResult.Error(401, "認証に失敗しました")
        }

        val token = authHeader.removePrefix("Bearer ")
        return if (token == apiKey) AuthResult.Ok
        else AuthResult.Error(401, "認証に失敗しました")
    }
}
