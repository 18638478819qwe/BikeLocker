package com.campus.bikelocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.campus.bikelocker.service.RideMonitorService
import com.campus.bikelocker.util.AppLauncher

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
                AppLauncher.openDidi(context)
            }

            ACTION_DISMISS_ALERT -> {
                Log.i(TAG, "关闭警报")
                RideMonitorService.stopService(context)
            }
        }
    }
}
