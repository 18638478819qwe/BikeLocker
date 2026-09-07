package com.campus.bikelocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.campus.bikelocker.service.RideMonitorService

/**
 * 📲 通知栏动作接收器 (NotificationActionReceiver)
 * 
 * 作用：处理通知栏上点击的“我已锁车”、“去滴滴还车”等按钮
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotificationAction"
        const val ACTION_LOCK_CONFIRMED = "com.campus.bikelocker.ACTION_LOCK_CONFIRMED"
        const val ACTION_OPEN_DIDI = "com.campus.bikelocker.ACTION_OPEN_DIDI"
        const val ACTION_DISMISS_ALERT = "com.campus.bikelocker.ACTION_DISMISS_ALERT"

        // 滴滴出行包名
        private const val DIDI_PACKAGE_NAME = "com.sdu.didi.psnger"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LOCK_CONFIRMED -> {
                Log.i(TAG, "用户在通知栏点击【我已锁车】，停止监测服务")
                Toast.makeText(context, "🎉 锁车成功！监测已停止，已为你守住院内扣费！", Toast.LENGTH_LONG).show()
                // 彻底停止后台服务，不留任何后台电量消耗
                RideMonitorService.stopService(context)
            }

            ACTION_OPEN_DIDI -> {
                Log.i(TAG, "用户在通知栏点击【打开滴滴】")
                openDidiApp(context)
            }

            ACTION_DISMISS_ALERT -> {
                Log.i(TAG, "关闭警报")
                RideMonitorService.stopService(context)
            }
        }
    }

    /**
     * 调起滴滴出行 App，如果手机没装则尝试打开网页版或提示
     */
    private fun openDidiApp(context: Context) {
        val pm = context.packageManager
        try {
            val launchIntent = pm.getLaunchIntentForPackage(DIDI_PACKAGE_NAME)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                // 尝试通过 URI Scheme 打开
                val schemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("didipasnger://"))
                schemeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (schemeIntent.resolveActivity(pm) != null) {
                    context.startActivity(schemeIntent)
                } else {
                    Toast.makeText(context, "未找到滴滴出行 App，请在微信/支付宝中查看青桔单车", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开滴滴失败: ${e.message}")
            Toast.makeText(context, "打开滴滴失败，请手动切到滴滴/微信", Toast.LENGTH_SHORT).show()
        }
    }
}
