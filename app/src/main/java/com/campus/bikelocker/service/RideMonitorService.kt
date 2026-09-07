package com.campus.bikelocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.campus.bikelocker.MainActivity
import com.campus.bikelocker.R
import com.campus.bikelocker.alert.AlertHelper
import com.campus.bikelocker.detector.MotionStateMachine
import com.campus.bikelocker.receiver.NotificationActionReceiver

/**
 * 🛡️ 骑行监测核心前台服务 (RideMonitorService)
 * 
 * 为什么必须是“前台服务 (Foreground Service)”？
 * - 小米（HyperOS/MIUI）对后台后台应用有极其激进的查杀机制。
 * - 只有带有常驻通知栏的前台服务，系统才允许持续获取 GPS 速度并在锁屏时保持步频传感器工作。
 * - 只要用户点击“我已锁车”，本服务会立刻自我销毁，绝不偷跑后台电量！
 */
class RideMonitorService : Service(), MotionStateMachine.StateListener, SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "RideMonitorService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "channel_bike_monitor_foreground"

        // 广播动作：通知主界面更新 UI
        const val ACTION_STATE_BROADCAST = "com.campus.bikelocker.BROADCAST_STATE"
        const val EXTRA_STATE = "extra_state"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_BUFFER_SECONDS = "extra_buffer_seconds"

        var isServiceRunning = false
            private set

        var currentState: MotionStateMachine.State = MotionStateMachine.State.IDLE
            private set
        var currentSpeed: Float = 0f
            private set
        var currentSteps: Int = 0
            private set
        var currentBufferSec: Int = 0
            private set

        fun startService(context: Context) {
            val intent = Intent(context, RideMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RideMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var stateMachine: MotionStateMachine
    private lateinit var alertHelper: AlertHelper

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var stepSensor: Sensor? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // 辅助计算速度（针对部分手机 GPS 无速度字段的备选测速方案）
    private var lastLocation: Location? = null
    private var lastLocationTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "RideMonitorService 正在启动...")
        isServiceRunning = true

        // 1. 初始化核心状态机与报警器
        stateMachine = MotionStateMachine(this)
        alertHelper = AlertHelper(this)

        // 2. 申请轻量级唤醒锁（防止小米系统在锁屏瞬间让 CPU 休眠导致计步器断流）
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BikeLocker:ServiceWakeLock")
        wakeLock?.acquire(30 * 60 * 1000L) // 最长保护 30 分钟（一次骑行通常 5-15 分钟）

        // 3. 创建前台通知渠道并启动前台模式
        createNotificationChannel()
        startForegroundWithType()

        // 4. 注册传感器与 GPS
        registerSensors()
        registerLocation()

        // 5. 启动状态机
        stateMachine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 如果服务意外被杀死，系统不自动重启（因为单车可能早就还了，避免后续乱报）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "RideMonitorService 正在停止并释放资源...")
        isServiceRunning = false
        currentState = MotionStateMachine.State.IDLE
        currentSpeed = 0f
        currentSteps = 0
        currentBufferSec = 0

        // 停止状态机并停止报警
        stateMachine.stop()
        alertHelper.stopAlert()
        alertHelper.release()

        // 注销所有监听
        sensorManager?.unregisterListener(this)
        try {
            locationManager?.removeUpdates(this)
        } catch (e: SecurityException) {
            Log.e(TAG, "注销定位监听失败: ${e.message}")
        }

        // 释放唤醒锁
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        // 广播通知主界面更新为待机
        broadcastState(MotionStateMachine.State.IDLE, 0f, 0, 0)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ==================== 传感器与 GPS 注册逻辑 ====================

    private fun registerSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // 优先使用 TYPE_STEP_DETECTOR 硬件计步器（极度省电，纯硬件中断）
        stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepSensor != null) {
            sensorManager?.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "成功绑定硬件步频传感器 (TYPE_STEP_DETECTOR)")
        } else {
            Log.w(TAG, "设备不支持硬件步频传感器，尝试回退备用传感器")
        }
    }

    private fun registerLocation() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            // 每 1.5 秒更新一次定位，移动超过 1 米触发
            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1500L,
                    1.0f,
                    this
                )
                Log.i(TAG, "已注册 GPS_PROVIDER")
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                locationManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    1.0f,
                    this
                )
                Log.i(TAG, "已注册 NETWORK_PROVIDER")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少定位权限: ${e.message}")
        }
    }

    // ==================== 传感器与定位数据回调 ====================

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            // 只要迈出一步，硬件步频传感器触发
            stateMachine.onStepDetected()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        val speedKmh = if (location.hasSpeed()) {
            // GPS 自身返回的速度（米/秒 -> 转换为 公里/小时）
            location.speed * 3.6f
        } else {
            // 若部分场景无 speed 属性，则通过两点经纬度差与时间差自主估算
            calculateSpeedFromDelta(location)
        }

        lastLocation = location
        lastLocationTime = System.currentTimeMillis()

        // 将当前车速传递给状态机裁决
        stateMachine.onSpeedUpdate(speedKmh)
    }

    /**
     * 针对部分小米机型室内/GPS信号弱时，备用的速度差计算公式
     */
    private fun calculateSpeedFromDelta(newLocation: Location): Float {
        val prev = lastLocation ?: return 0f
        val timeDiffSeconds = (System.currentTimeMillis() - lastLocationTime) / 1000f
        if (timeDiffSeconds <= 0.5f) return 0f

        val distanceMeters = prev.distanceTo(newLocation)
        val speedMs = distanceMeters / timeDiffSeconds
        val speedKmh = speedMs * 3.6f
        return if (speedKmh > 60f) 0f else speedKmh // 过滤 GPS 飘移异常跳变
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ==================== 状态机回调响应 ====================

    override fun onStateChanged(oldState: MotionStateMachine.State, newState: MotionStateMachine.State) {
        updateForegroundNotification(newState)
        broadcastState(newState, 0f, 0, 0)
    }

    override fun onBufferTick(remainingSeconds: Int) {
        broadcastState(stateMachine.currentState, 0f, 0, remainingSeconds)
    }

    override fun onMetricsUpdate(currentSpeedKmh: Float, stepsSinceStop: Int) {
        broadcastState(stateMachine.currentState, currentSpeedKmh, stepsSinceStop, 0)
    }

    override fun onTriggerAlert(reason: String) {
        Log.w(TAG, "状态机触发强警报: $reason")
        alertHelper.triggerBikeLockAlert(reason)
        broadcastState(MotionStateMachine.State.ALERTING, 0f, 0, 0)
    }

    // ==================== 前台通知与界面通信 ====================

    private fun startForegroundWithType() {
        val notification = buildForegroundNotification("🚴 正在守护青桔骑行，下车后将自动提醒")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(state: MotionStateMachine.State) {
        val text = when (state) {
            MotionStateMachine.State.RIDING -> "🚴 正在骑行中，防误扣守护中..."
            MotionStateMachine.State.MAYBE_STOPPED -> "⏳ 车速归零，正在观察是否下车（防误判缓冲）"
            MotionStateMachine.State.ALERTING -> "🚨 检测到离车步行，请立即在滴滴还车！"
            else -> "正在守护青桔骑行"
        }
        val notification = buildForegroundNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockIntent = Intent(this, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LOCK_CONFIRMED
        }
        val lockPendingIntent = PendingIntent.getBroadcast(
            this, 1, lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("青桔骑行·防忘锁车助手")
            .setContentText(contentText)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.checkbox_on_background, "✅ 我已锁车", lockPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "青桔单车守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在后台监测骑行与步行转换状态"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun broadcastState(state: MotionStateMachine.State, speed: Float, steps: Int, bufferRemaining: Int) {
        currentState = state
        currentSpeed = speed
        currentSteps = steps
        currentBufferSec = bufferRemaining

        val intent = Intent(ACTION_STATE_BROADCAST).apply {
            setPackage(packageName) // 核心：明确指定本包名，Android 14 规范要求！
            putExtra(EXTRA_STATE, state.name)
            putExtra(EXTRA_SPEED, speed)
            putExtra(EXTRA_STEPS, steps)
            putExtra(EXTRA_BUFFER_SECONDS, bufferRemaining)
        }
        sendBroadcast(intent)
    }
}
