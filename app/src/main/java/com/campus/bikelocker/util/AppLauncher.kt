package com.campus.bikelocker.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * 🚀 应用跳转工具类 (AppLauncher)
 * 
 * 专为「滴滴」App（原“滴滴出行”）优化设计的精准调起逻辑：
 * 1. 优先调用滴滴官方客户端包名 (com.sdu.didi.psnger)
 * 2. 备选支持青桔单车/滴滴相关包名及深度链接 Scheme (didipasnger://)
 * 3. 完美适配 Android 11+ 包可见性规范，杜绝找不到应用的情况
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    // 滴滴官方主流包名列表
    private val CANDIDATE_PACKAGES = listOf(
        "com.sdu.didi.psnger",        // 「滴滴」官方客户端（青桔单车主入口）
        "com.didi.echo",              // 青桔单车独立版 / 滴滴单车
        "com.didipinche.booking",      // 滴滴渠道包
        "com.didiglobal.passenger"    // 滴滴定制版
    )

    // 滴滴官方唤起 Scheme
    private val CANDIDATE_SCHEMES = listOf(
        "didipasnger://",
        "didi://"
    )

    /**
     * 一键唤起手机上的「滴滴」软件
     */
    fun openDidi(context: Context) {
        val pm = context.packageManager

        // 1. 优先使用已安装包名启动（最稳定，直接拉起滴滴前台主界面）
        for (pkg in CANDIDATE_PACKAGES) {
            try {
                val launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(launchIntent)
                    Log.i(TAG, "已成功启动「滴滴」: $pkg")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "尝试通过包名 $pkg 启动失败: ${e.message}")
            }
        }

        // 2. 尝试通过滴滴专属协议 Scheme 呼起
        for (scheme in CANDIDATE_SCHEMES) {
            try {
                val schemeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (schemeIntent.resolveActivity(pm) != null) {
                    context.startActivity(schemeIntent)
                    Log.i(TAG, "已通过 Scheme 唤起「滴滴」: $scheme")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "尝试通过 Scheme $scheme 启动失败: ${e.message}")
            }
        }

        // 3. 兜底提示：如果未检测到，提示用户检查
        Log.e(TAG, "未能找到滴滴软件")
        Toast.makeText(context, "未找到手机上的「滴滴」软件，请确认是否已安装或授予应用关联启动权限", Toast.LENGTH_LONG).show()
    }
}
