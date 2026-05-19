// Version: 1.0.0 | Updated: 2026-05-20
// [2026-05-20] 端末再起動後に BridgeForegroundService を自動起動する Receiver
package jp.salesnow.chromebridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import jp.salesnow.chromebridge.service.BridgeForegroundService

/**
 * 端末の起動完了を検知して BridgeForegroundService を自動起動する。
 *
 * 対応する Intent:
 * - ACTION_BOOT_COMPLETED       … 標準の起動完了
 * - QUICKBOOT_POWERON           … HTC/Lenovo 系の高速起動
 * - LOCKED_BOOT_COMPLETED       … direct boot 環境下の早期起動（API 24+）
 *
 * Android 12+ では通常 BroadcastReceiver から FGS を起動できないが、
 * BOOT_COMPLETED は公式に許可された例外。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        android.util.Log.i("ChromeBridge", "起動完了検知 ($action): BridgeForegroundService を起動します")

        val svcIntent = Intent(context, BridgeForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("ChromeBridge", "BootReceiver から Service 起動に失敗: ${e.message}")
        }
    }
}
