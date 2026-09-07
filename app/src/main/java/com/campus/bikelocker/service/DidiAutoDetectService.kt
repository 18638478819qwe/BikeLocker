package com.campus.bikelocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.campus.bikelocker.MainActivity
import com.campus.bikelocker.detector.AppUsageHelper

/**
 * 🛰️ 滴滴 App 前台打开自动感应服务 (DidiAutoDetectService)
 * 
 * 极致低功耗设计：
 * 1. 息屏深度休眠：动态监听屏幕亮灭。息屏时完全停止轮询，允许 CPU 彻底休眠，0 电量消耗。
 * 2. 亮屏轻量探测：仅在手机屏幕亮起时，每 2.5 秒轻量检测一次当前前台应用是否为滴滴。
 * 3. 5分钟防抖冷却：扫码开车时只自动唤醒一次，防止在滴滴内反复切页面频繁弹起服务。
 */
class DidiAutoDetectService : Service() {

    companion object {
        private const val TAG = "DidiAutoDetectService"
        const val NOTIFICATION_ID = 1002
        const val CHANNEL_ID = "channel_didi_watcher"

        // 防抖冷却时间：5 分钟内不重复自动触发
        private const val TRIGGER_COOLDOWN_MS = 5 * 60 * 1000L

        var isServiceRunning = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, DidiAutoDetectService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DidiAutoDetectService::class.java)
            context.stopService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isScreenOn = true
    private var lastTriggeredTime = 0L

    // 屏幕亮灭广播接收器
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "屏幕点亮，恢复滴滴前台探测")
                    isScreenOn = true
                    startPolling()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "屏幕熄灭，进入深度休眠，停止一切后台探测")
                    isScreenOn = false
                    stopPolling()
                }
            }
        }
    }

    // 周期性探测任务（每 2.5 秒探测一次）
    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (!isScreenOn) return

            checkAndTriggerDidi()

            // 保持每 2.5 秒检查一次
            handler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DidiAutoDetectService 正在启动...")
        isServiceRunning = true

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildSilentNotification())

        // 检查当前屏幕初始状态
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = powerManager.isInteractive

        // 注册屏幕开关广播
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)

        // 若屏幕是亮的，立即开启探测
        if (isScreenOn) {
            startPolling()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // 常驻监听滴滴唤醒
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "DidiAutoDetectService 正在停止...")
        isServiceRunning = false
        stopPolling()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "注销屏幕监听失败: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        handler.removeCallbacks(pollingRunnable)
        handler.post(pollingRunnable)
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollingRunnable)
    }

    /**
     * 核心检测逻辑
     */
    private fun checkAndTriggerDidi() {
        // 如果当前已经在骑行守护中，跳过检测
        if (RideMonitorService.isServiceRunning) {
            return
        }

        // 检查是否处于 5 分钟冷却期内
        val now = System.currentTimeMillis()
        if (now - lastTriggeredTime < TRIGGER_COOLDOWN_MS) {
            return
        }

        // 探测滴滴是否在前台运行
        if (AppUsageHelper.isDidiInForegroundRecently(this)) {
            Log.i(TAG, "🚀 捕获到滴滴打开，立即自动唤醒骑行守护服务！")
            lastTriggeredTime = now

            // 自动开启骑行监测服务
            RideMonitorService.startService(this)

            Toast.makeText(this, "🚴 检测到打开滴滴，已自动为你开启锁车守护！", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSilentNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("滴滴自动感应守护已就绪")
            .setContentText("息屏深度休眠，亮屏打开滴滴时将自动启动锁车防忘监测")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "滴滴前台打开自动感应",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "用于在后台感知滴滴 App 打开并自动唤醒锁车助手"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
