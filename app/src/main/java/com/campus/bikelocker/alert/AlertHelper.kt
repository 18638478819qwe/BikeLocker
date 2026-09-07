package com.campus.bikelocker.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.campus.bikelocker.MainActivity
import com.campus.bikelocker.receiver.NotificationActionReceiver

/**
 * 📢 强提醒执行器 (AlertHelper) - 纯强震动 + 原生弹窗版
 * 
 * 针对小米手机及各品牌 Android 10~14+ 系统深度适配：
 * 1. 采用 AudioAttributes.USAGE_ALARM 闹钟级属性，穿透系统静音模式与触感限制，确保 100% 震动！
 * 2. 移除设备芯片可能不兼容的自定义振幅数组，使用高兼容性脉冲时序波形。
 * 3. 增加 30 秒超时自动停止防死锁保护，避免持续震动耗电。
 * 4. 升级通知渠道为 channel_bike_alert_v2，避免继承历史静音设置。
 * 5. 支持直接呼起 MainActivity 弹出醒目的全屏锁车提示弹窗。
 */
class AlertHelper(private val context: Context) {

    companion object {
        private const val TAG = "AlertHelper"
        const val ALERT_NOTIFICATION_ID = 2001
        // 升级为 v2 渠道，确保新安装或覆盖后不受系统旧渠道静音配置影响
        const val CHANNEL_ALERT_ID = "channel_bike_alert_v2"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // 震动服务（兼容 Android 12+ VibratorManager 和旧版 Vibrator）
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // 自动停止震动保护（最长震动 30 秒）
    private val autoStopRunnable = Runnable {
        Log.i(TAG, "已达 30 秒安全阈值，自动停止震动保护电池")
        stopVibration()
    }

    init {
        createAlertNotificationChannel()
    }

    /**
     * 触发全套锁车强提醒（唤醒屏幕 + 强震动 + 通知横幅 + 弹窗）
     */
    fun triggerBikeLockAlert(customMessage: String = "检测到你已离车步行，青桔单车记得锁车还车！") {
        Log.w(TAG, "===> 开始执行防遗忘锁车强提醒（纯震动+弹窗模式） <===")

        // 1. 点亮屏幕 10 秒
        wakeUpScreen()

        // 2. 触发强节奏震动（带循环，直到用户确认锁车）
        startStrongVibration(repeat = true)

        // 3. 发送高优先级横幅通知（锁屏或通知栏可点击）
        showAlertNotification(customMessage)

        // 4. 尝试唤醒主界面弹出全屏锁车警报对话框
        launchAlertActivity(customMessage)
    }

    /**
     * 触发高强度脉冲节奏震动
     * @param repeat 是否循环震动（直到用户点击锁车）
     */
    fun startStrongVibration(repeat: Boolean = true) {
        try {
            stopVibration()

            if (!vibrator.hasVibrator()) {
                Log.w(TAG, "检测到当前设备无振动马达硬件")
                return
            }

            // 脉冲节奏时序：停0ms -> 震500ms -> 停200ms -> 震500ms -> 停200ms -> 震800ms -> 停600ms
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 800, 600)
            val repeatIndex = if (repeat) 0 else -1

            // 核心：设置 USAGE_ALARM 属性！
            // 无论手机是否处于“静音模式”或“勿扰模式”，系统均会强制允许马达震动！
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, repeatIndex)
                vibrator.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, repeatIndex, audioAttributes)
            }

            Log.i(TAG, "🔥 强震动已成功开启 (USAGE_ALARM 闹钟级别)")

            // 设定 30 秒超时自动停止保护
            handler.removeCallbacks(autoStopRunnable)
            handler.postDelayed(autoStopRunnable, 30_000L)

        } catch (e: Exception) {
            Log.e(TAG, "触发高级震动失败: ${e.message}，尝试单次强震兜底")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(1000)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "兜底震动亦发生异常: ${ex.message}")
            }
        }
    }

    /**
     * 仅停止震动
     */
    fun stopVibration() {
        handler.removeCallbacks(autoStopRunnable)
        try {
            vibrator.cancel()
            Log.i(TAG, "震动已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止震动异常: ${e.message}")
        }
    }

    /**
     * 彻底停止所有提醒（用户确认锁车时调用）
     */
    fun stopAlert() {
        stopVibration()
        try {
            notificationManager.cancel(ALERT_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "取消通知异常: ${e.message}")
        }
    }

    /**
     * 点亮屏幕 10 秒
     */
    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BikeLocker:AlertWakeLock"
            )
            wakeLock.acquire(10 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "唤醒屏幕失败: ${e.message}")
        }
    }

    /**
     * 发送高优先级横幅通知
     */
    fun showAlertNotification(message: String) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SHOW_ALERT_DIALOG, true)
            putExtra(MainActivity.EXTRA_ALERT_MESSAGE, message)
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

        // 按钮 2：直接调起滴滴 App
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

        try {
            notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "发送警报通知失败: ${e.message}")
        }
    }

    /**
     * 唤起主界面直接展示锁车弹窗
     */
    private fun launchAlertActivity(message: String) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_SHOW_ALERT_DIALOG, true)
                putExtra(MainActivity.EXTRA_ALERT_MESSAGE, message)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "后台拉起弹窗界面受系统策略限制: ${e.message}")
        }
    }

    /**
     * 创建高优先级通知渠道
     */
    private fun createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ALERT_ID,
                "青桔单车紧急还车强提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "到达目的地步行离开时触发的高优先级强震动横幅报警"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setSound(defaultSound, audioAttributes)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun release() {
        stopAlert()
    }
}
