// Version: 1.1.0 | Updated: 2026-06-20
// [2026-06-20] STATUS_SUCCESS で Service を明示起動する処理を追加。OEM 別の挙動で OS が
//   自動再起動してくれないケース（OPPO ColorOS / Lenovo 等で観測）の保険。
// [2026-06-10] PackageInstaller の commit() コールバック受信。失敗時のみ通知でログ。
package jp.salesnow.chromebridge.update

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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
                android.util.Log.i("ChromeBridge.OTA", "インストール成功 → BridgeForegroundService を明示起動")
                // [2026-06-20] OPPO / Lenovo 等で OS が新 APK を自動再起動しないケースの保険。
                //   Android 12+ や OEM のバックグラウンド開始制限で蹴られる可能性もあるため
                //   「起動を試みる」位置づけ。失敗しても次の OTA Alarm（1h）/ 端末再起動で復活する。
                //   将来的により堅くするなら ACTION_MY_PACKAGE_REPLACED Receiver の追加も検討。
                try {
                    val svcIntent = Intent(context, BridgeForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ChromeBridge.OTA", "Service 自動起動失敗: ${e.message}")
                    // 起動失敗は通知しない（次の Alarm or 端末再起動で復活する）
                }
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
