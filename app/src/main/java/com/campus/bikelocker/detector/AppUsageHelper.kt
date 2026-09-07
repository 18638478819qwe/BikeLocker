package com.campus.bikelocker.detector

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

/**
 * 🔍 应用使用情况检测工具 (AppUsageHelper)
 * 
 * 作用：利用 Android 系统的 UsageStatsManager 接口，精准判断用户当前前台是否打开了“滴滴出行”
 */
object AppUsageHelper {

    private const val TAG = "AppUsageHelper"

    // 滴滴相关应用包名集合（包含主应用、独立青桔单车、顺风车等）
    private val DIDI_PACKAGES = setOf(
        "com.sdu.didi.psnger",       // 滴滴出行官方主应用（涵盖青桔单车扫码）
        "com.didi.echo",             // 青桔单车独立应用
        "com.didipinche.booking"     // 滴滴出行部分定制渠道包
    )

    /**
     * 检查用户是否在系统设置中授予了「有权查看使用情况的应用」权限
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 跳转至系统「使用情况访问权限」设置界面，引导用户手动开启
     */
    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * 查询最近 4 秒内是否有滴滴出行被切换到了前台 (ACTIVITY_RESUMED)
     */
    fun isDidiInForegroundRecently(context: Context): Boolean {
        if (!hasUsageStatsPermission(context)) {
            Log.w(TAG, "未授予使用情况访问权限，无法探测前台应用")
            return false
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false

        val endTime = System.currentTimeMillis()
        val startTime = endTime - 4000L // 检查过去 4 秒内的窗口事件

        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        var latestEventPackage: String? = null
        var latestEventType: Int = -1

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // 关注 Activity 恢复到前台运行的事件 (ACTIVITY_RESUMED = 1)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestEventPackage = event.packageName
                latestEventType = event.eventType
            }
        }

        if (latestEventPackage != null && isDidiPackage(latestEventPackage)) {
            Log.i(TAG, "🔥 成功捕捉到滴滴出行前台唤起事件: $latestEventPackage")
            return true
        }

        return false
    }

    /**
     * 判断包名是否属于滴滴系应用
     */
    fun isDidiPackage(packageName: String): Boolean {
        return DIDI_PACKAGES.contains(packageName)
    }
}
