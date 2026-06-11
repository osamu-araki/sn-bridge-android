// Version: 1.0.0 | Updated: 2026-06-10
// [2026-06-10] PackageInstaller の commit() コールバック受信。失敗時のみ通知でログ。
package jp.salesnow.chromebridge.update

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import jp.salesnow.chromebridge.service.BridgeForegroundService

class PackageInstallerReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_RESULT = "jp.salesnow.chromebridge.OTA_INSTALL_RESULT"
        const val NOTIFICATION_ID = 100
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 未署名や設定不足でユーザー操作が必要なケース
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        notify(context, "OTA: ユーザー操作要求の起動失敗: ${e.message}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                android.util.Log.i("ChromeBridge.OTA", "インストール成功")
                // 成功時は新バイナリで自動再起動されるため通知不要
            }
            else -> {
                notify(context, "OTA: インストール失敗 ($status) $message")
            }
        }
    }

    private fun notify(context: Context, text: String) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            val notif = NotificationCompat.Builder(context, BridgeForegroundService.CHANNEL_ID)
                .setContentTitle("SalesNow Bridge OTA")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notif)
        } catch (e: Exception) {
            android.util.Log.w("ChromeBridge.OTA", "通知失敗: ${e.message}")
        }
    }
}
