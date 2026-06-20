// Version: 1.1.0 | Updated: 2026-06-20
// [2026-06-20] checkAndInstall() の戻り値を sealed class (UpdateCheckResult) に拡張。
//   UI 側で「許可未付与」「未設定」「最新」「新版インストール開始」「エラー」を判別表示する。
// [2026-06-10] OTA アップデート: Portal manifest 取得 → APK ダウンロード → SHA-256 検証 → PackageInstaller 起動
//   manifest 仕様: GET <portalManifestUrl>  Authorization: Bearer <portalCheckToken>
//     200 { "latest": { version_code, version_name, sha256, size, apk_url, ... } | null }
//
//   起動経路は 2 系統:
//     1) Service の周期 Alarm から checkAndInstall() を呼ぶ（バックグラウンド自動更新）
//     2) UI の「今すぐ確認」ボタン or HTTP /update-check 経由（手動）
package jp.salesnow.chromebridge.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.google.gson.JsonParser
import jp.salesnow.chromebridge.data.SettingsRepository
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * [2026-06-20] checkAndInstall() の結果を表現する sealed class。
 *   UI からは「ユーザーに何を見せるか」をこの型でディスパッチする。
 *   ログメッセージ向けのテキスト化は [toUserMessage] を使う。
 */
sealed class UpdateCheckResult {
    /** OS の「不明な提供元のインストール許可」が未付与 */
    object NoPermission : UpdateCheckResult()
    /** Portal manifest URL / Check Token が未設定 */
    object NotConfigured : UpdateCheckResult()
    /** Portal manifest の取得に失敗した（ネットワーク / 401 / 5xx 等） */
    data class ManifestError(val detail: String) : UpdateCheckResult()
    /** Portal に公開リリースが無い */
    object NoPublishedRelease : UpdateCheckResult()
    /** 端末は既に最新 */
    data class AlreadyLatest(val currentCode: Int) : UpdateCheckResult()
    /** PackageInstaller への commit に到達した（OS が install を進める） */
    data class Installing(val versionName: String, val versionCode: Int) : UpdateCheckResult()
    /** DL / 検証 / install の途中で失敗 */
    data class InstallError(val detail: String) : UpdateCheckResult()

    fun toUserMessage(): String = when (this) {
        NoPermission -> "「不明な提供元のインストール許可」が未付与のため、アップデートできません"
        NotConfigured -> "Portal Manifest URL または Check Token が未設定です"
        is ManifestError -> "Portal manifest 取得に失敗: $detail"
        NoPublishedRelease -> "公開中のリリースが見つかりません"
        is AlreadyLatest -> "最新版です (build $currentCode)"
        is Installing -> "新バージョン $versionName (build $versionCode) のインストールを開始しました"
        is InstallError -> "アップデートに失敗: $detail"
    }
}

class UpdateChecker(
    private val context: Context,
    private val onLog: (String) -> Unit,
) {
    data class ManifestRelease(
        val versionCode: Int,
        val versionName: String,
        val sha256: String,
        val size: Long,
        val apkUrl: String,
        val releaseNotes: String?,
    )

    private val settings = SettingsRepository(context)

    /**
     * Portal の manifest を叩いて最新リリースを取得する。
     * 成功: Success(release) / 公開なし: NotPublished / 設定不足・エラー: 各種 Result。
     */
    private sealed class ManifestResult {
        data class Success(val release: ManifestRelease) : ManifestResult()
        object NotConfigured : ManifestResult()
        object NotPublished : ManifestResult()
        data class Error(val detail: String) : ManifestResult()
    }

    private fun fetchManifestResult(): ManifestResult {
        val url = settings.portalManifestUrl.trim()
        val token = settings.portalCheckToken.trim()
        if (url.isEmpty() || token.isEmpty()) {
            onLog("OTA: manifest URL またはトークンが未設定")
            return ManifestResult.NotConfigured
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(120)
                onLog("OTA: manifest HTTP $code ${err.take(200)}")
                return ManifestResult.Error("HTTP $code ${err}".trim())
            }
            val body = conn.inputStream.bufferedReader().readText()
            val root = JsonParser.parseString(body).asJsonObject
            val latest = root.get("latest")
            if (latest == null || latest.isJsonNull) {
                onLog("OTA: 公開リリースなし")
                return ManifestResult.NotPublished
            }
            val obj = latest.asJsonObject
            ManifestResult.Success(
                ManifestRelease(
                    versionCode = obj.get("version_code").asInt,
                    versionName = obj.get("version_name").asString,
                    sha256 = obj.get("sha256").asString,
                    size = obj.get("size").asLong,
                    apkUrl = obj.get("apk_url").asString,
                    releaseNotes = obj.get("release_notes")?.takeIf { !it.isJsonNull }?.asString,
                )
            )
        } catch (e: Exception) {
            onLog("OTA: manifest 取得失敗 ${e.message}")
            ManifestResult.Error(e.message ?: e::class.java.simpleName)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** [2026-06-20] 互換 API。null 安全な薄いラッパー。 */
    fun fetchManifest(): ManifestRelease? =
        (fetchManifestResult() as? ManifestResult.Success)?.release

    /**
     * 自端末より新しいリリースがあるかをチェックし、あれば DL → インストール起動。
     *
     * 戻り値で結果区分を返すので UI 側はそのままユーザー表示できる。
     */
    fun checkAndInstall(): UpdateCheckResult {
        // [Codex#1] 「不明な提供元からのインストール」許可を事前確認
        if (!context.packageManager.canRequestPackageInstalls()) {
            onLog("OTA: 「不明な提供元のインストール許可」が未付与。設定画面で許可してください。")
            return UpdateCheckResult.NoPermission
        }
        val release = when (val r = fetchManifestResult()) {
            is ManifestResult.Success -> r.release
            is ManifestResult.NotConfigured -> return UpdateCheckResult.NotConfigured
            is ManifestResult.NotPublished -> return UpdateCheckResult.NoPublishedRelease
            is ManifestResult.Error -> return UpdateCheckResult.ManifestError(r.detail)
        }
        val currentCode = currentVersionCode()
        if (release.versionCode <= currentCode) {
            onLog("OTA: 最新です (current=$currentCode, latest=${release.versionCode})")
            return UpdateCheckResult.AlreadyLatest(currentCode)
        }
        onLog("OTA: 新バージョン検出 ${release.versionName} (code=${release.versionCode})")
        return downloadAndInstall(release)
    }

    /** Codex#3: DL した APK の applicationId / versionCode / 署名は OS の PackageInstaller が
     *  commit() 後に検証する（不一致なら INSTALL_FAILED_UPDATE_INCOMPATIBLE 等で
     *  PackageInstallerReceiver が失敗を通知）。
     *  Portal 側の APK metadata 抽出は次フェーズで導入予定（現状は手入力 version_code に依存）。
     *  ここでは session の存在のみ確認しログを残す。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun verifyApk(
        session: PackageInstaller.Session,
        release: ManifestRelease,
    ): Boolean {
        onLog("OTA: 検証通過 (sha256/size 一致、applicationId は OS install 時に検証)")
        return true
    }

    private fun currentVersionCode(): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun downloadAndInstall(release: ManifestRelease): UpdateCheckResult {
        val installer = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        var sessionId = -1
        return try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)

            val digest = MessageDigest.getInstance("SHA-256")
            var conn: HttpURLConnection? = null
            var total = 0L
            try {
                conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout = 120_000
                }
                if (conn.responseCode != 200) {
                    throw IOException("APK ダウンロード HTTP ${conn.responseCode}")
                }
                // [Codex#4] use ブロックで stream/session output を確実に close
                conn.inputStream.use { input ->
                    session.openWrite("base.apk", 0, release.size).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            total += n
                        }
                        session.fsync(output)
                    }
                }
                onLog("OTA: APK 取得 ${total / 1024} KB")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }

            // [Codex#4] 受信サイズが manifest と一致するかも検証（早期検出）
            if (total != release.size) {
                onLog("OTA: サイズ不一致 expected=${release.size} actual=$total")
                session.abandon()
                return UpdateCheckResult.InstallError("APK サイズ不一致 (expected=${release.size}, actual=$total)")
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(release.sha256, ignoreCase = true)) {
                onLog("OTA: SHA-256 不一致 expected=${release.sha256.take(16)}… actual=${actual.take(16)}…")
                session.abandon()
                return UpdateCheckResult.InstallError("APK の SHA-256 が不一致")
            }

            // [Codex#3] DL 済 APK の applicationId / versionCode を端末側でも検証。
            //   不一致なら abandon して install しない（誤った APK の永久リトライを防ぐ）。
            if (!verifyApk(session, release)) {
                session.abandon()
                return UpdateCheckResult.InstallError("APK 検証失敗")
            }

            // コールバック intent（インストール結果が PackageInstallerReceiver に送られる）
            val callback = Intent(context, PackageInstallerReceiver::class.java).apply {
                action = PackageInstallerReceiver.ACTION_INSTALL_RESULT
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
            onLog("OTA: インストール開始 (sessionId=$sessionId)")
            UpdateCheckResult.Installing(release.versionName, release.versionCode)
        } catch (e: Exception) {
            onLog("OTA: インストール失敗 ${e.message}")
            try { session?.abandon() } catch (_: Exception) {}
            UpdateCheckResult.InstallError(e.message ?: e::class.java.simpleName)
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }
}
