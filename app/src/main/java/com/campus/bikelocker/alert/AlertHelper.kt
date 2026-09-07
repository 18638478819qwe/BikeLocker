package com.campus.bikelocker.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.campus.bikelocker.MainActivity
import com.campus.bikelocker.receiver.NotificationActionReceiver

/**
 * 📢 强提醒执行器 (AlertHelper) - 纯震动与高优先级弹窗版
 * 
 * 用户需求定制：
 * 1. 取消语音播报：彻底静音，避免在校园大厅、教学楼走廊或图书馆外尴尬
 * 2. 强力节奏震动：采用多段脉冲强震（在裤兜里可清晰感知）
 * 3. 亮屏与全屏横幅强提醒：点亮屏幕并在锁屏界面弹出醒目横幅，附带一键还车按钮
 */
class AlertHelper(private val context: Context) {

    companion object {
        private const val TAG = "AlertHelper"
        const val ALERT_NOTIFICATION_ID = 2001
        const val CHANNEL_ALERT_ID = "channel_bike_alert_vibrate"
    }

    // 震动服务
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // 电源管理（用于唤醒屏幕点亮）
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createAlertNotificationChannel()
    }

    /**
     * 触发全套锁车强提醒（强震动 + 亮屏 + 锁屏弹窗，无声音扰民）
     */
    fun triggerBikeLockAlert(customMessage: String = "检测到你已离车步行，青桔单车记得锁车还车！") {
        Log.w(TAG, "===> 开始执行防遗忘锁车强提醒（纯震动+弹窗模式） <===")

        // 1. 唤醒点亮屏幕 10 秒
        wakeUpScreen()

        // 2. 触发节奏强震动 (停 0ms, 震 600ms, 停 200ms, 震 600ms, 停 200ms, 震 1000ms)
        startStrongVibration()

        // 3. 发送高优先级横幅通知（可直接点击“打开滴滴”或“我已锁车”）
        showAlertNotification(customMessage)
    }

    /**
     * 触发强节奏震动
     */
    fun startStrongVibration() {
        val pattern = longArrayOf(0, 600, 200, 600, 200, 1000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 设置最大震动强度 255
            val effect = VibrationEffect.createWaveform(pattern, intArrayOf(0, 255, 0, 255, 0, 255), -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    /**
     * 停止所有震动（用户确认锁车时调用）
     */
    fun stopAlert() {
        vibrator.cancel()
        notificationManager.cancel(ALERT_NOTIFICATION_ID)
    }

    /**
     * 亮屏 10 秒
     */
    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BikeLocker:AlertWakeLock"
            )
            wakeLock.acquire(10 * 1000L) // 10秒后自动释放
        } catch (e: Exception) {
            Log.e(TAG, "唤醒屏幕失败: ${e.message}")
        }
    }

    /**
     * 弹出全屏高优先级横幅通知
     */
    private fun showAlertNotification(message: String) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 按钮 1：我已锁车（停止监测与警报）
        val lockIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LOCK_CONFIRMED
        }
        val lockPendingIntent = PendingIntent.getBroadcast(
            context, 1, lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 按钮 2：直接调起滴滴出行 App
        val didiIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_OPEN_DIDI
        }
        val didiPendingIntent = PendingIntent.getBroadcast(
            context, 2, didiIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 青桔单车锁车了吗？")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .setFullScreenIntent(openAppPendingIntent, true) // 锁屏时直接作为横幅弹出
            .addAction(android.R.drawable.checkbox_on_background, "✅ 我已锁车", lockPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "🚀 查滴滴", didiPendingIntent)
            .build()

        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    /**
     * 注册高优先级通知渠道（仅震动，无系统通知声音）
     */
    private fun createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ALERT_ID,
                "青桔单车紧急还车提醒(纯震动)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "到达目的地步行离开时触发的高优先级强震动横幅报警"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 1000)
                setSound(null, null) // 无系统提示音，纯震动
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        vibrator.cancel()
    }
}
