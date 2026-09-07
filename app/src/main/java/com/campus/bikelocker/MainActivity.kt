package com.campus.bikelocker

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.campus.bikelocker.alert.AlertHelper
import com.campus.bikelocker.detector.AppUsageHelper
import com.campus.bikelocker.detector.MotionStateMachine
import com.campus.bikelocker.service.DidiAutoDetectService
import com.campus.bikelocker.service.RideMonitorService

/**
 * 📱 主界面 Activity (MainActivity)
 * 
 * 核心功能：
 * 1. 动态申请定位、计步和通知权限（Android 10-14 适配）
 * 2. 一键启动 / 停止骑行守护服务
 * 3. 实时响应服务状态，刷新卡片中的车速、步行步数和倒计时
 * 4. 提供“试听报警声音与震动”的测试入口
 * 5. 快速跳转滴滴出行 App 以及系统电量白名单设置
 */
class MainActivity : AppCompatActivity() {

    // 控件变量
    private lateinit var tvStateBadge: TextView
    private lateinit var tvStateTitle: TextView
    private lateinit var tvStateDesc: TextView
    private lateinit var tvSpeedVal: TextView
    private lateinit var tvStepsVal: TextView
    private lateinit var tvBufferCountdown: TextView
    private lateinit var btnToggleMonitor: Button
    private lateinit var btnOpenDidi: Button
    private lateinit var btnTestAlarm: Button
    private lateinit var btnOpenBatterySettings: Button
    private lateinit var switchAutoDetect: com.google.android.material.switchmaterial.SwitchMaterial

    // 独立报警测试工具
    private var testAlertHelper: AlertHelper? = null

    // 接收来自后台 Service 的状态同步广播
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RideMonitorService.ACTION_STATE_BROADCAST) {
                val stateName = intent.getStringExtra(RideMonitorService.EXTRA_STATE) ?: return
                val speed = intent.getFloatExtra(RideMonitorService.EXTRA_SPEED, 0f)
                val steps = intent.getIntExtra(RideMonitorService.EXTRA_STEPS, 0)
                val bufferSec = intent.getIntExtra(RideMonitorService.EXTRA_BUFFER_SECONDS, 0)

                val state = try {
                    MotionStateMachine.State.valueOf(stateName)
                } catch (e: Exception) {
                    MotionStateMachine.State.IDLE
                }

                updateUiForState(state, speed, steps, bufferSec)
            }
        }
    }

    // 权限请求启动器
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val stepGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACTIVITY_RECOGNITION] == true
        } else true

        if (locationGranted && stepGranted) {
            Toast.makeText(this, "权限已就绪，正在开启守护", Toast.LENGTH_SHORT).show()
            startMonitoringService()
        } else {
            showPermissionExplanationDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        testAlertHelper = AlertHelper(this)
    }

    override fun onResume() {
        super.onResume()
        // 注册广播监听服务动态
        val filter = IntentFilter(RideMonitorService.ACTION_STATE_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }

        // 根据服务当前是否在运行，更新按钮显示
        if (RideMonitorService.isServiceRunning) {
            btnToggleMonitor.text = getString(R.string.btn_stop_monitor)
            btnToggleMonitor.setBackgroundResource(R.drawable.bg_button_stop)
        } else {
            btnToggleMonitor.text = getString(R.string.btn_start_monitor)
            btnToggleMonitor.setBackgroundResource(R.drawable.bg_button_start)
            updateUiForState(MotionStateMachine.State.IDLE, 0f, 0, 0)
        }

        // 同步滴滴自动感应开关状态
        switchAutoDetect.isChecked = DidiAutoDetectService.isServiceRunning
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        testAlertHelper?.release()
    }

    private fun initViews() {
        tvStateBadge = findViewById(R.id.tvStateBadge)
        tvStateTitle = findViewById(R.id.tvStateTitle)
        tvStateDesc = findViewById(R.id.tvStateDesc)
        tvSpeedVal = findViewById(R.id.tvSpeedVal)
        tvStepsVal = findViewById(R.id.tvStepsVal)
        tvBufferCountdown = findViewById(R.id.tvBufferCountdown)
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor)
        btnOpenDidi = findViewById(R.id.btnOpenDidi)
        btnTestAlarm = findViewById(R.id.btnTestAlarm)
        btnOpenBatterySettings = findViewById(R.id.btnOpenBatterySettings)
        switchAutoDetect = findViewById(R.id.switchAutoDetect)
    }

    private fun setupListeners() {
        // 点击切换开启/停止
        btnToggleMonitor.setOnClickListener {
            if (RideMonitorService.isServiceRunning) {
                // 当前在运行，用户点击“我已锁车”
                RideMonitorService.stopService(this)
                btnToggleMonitor.text = getString(R.string.btn_start_monitor)
                btnToggleMonitor.setBackgroundResource(R.drawable.bg_button_start)
                Toast.makeText(this, "🎉 监测已结束！记得在滴滴确认关锁", Toast.LENGTH_SHORT).show()
                updateUiForState(MotionStateMachine.State.IDLE, 0f, 0, 0)
            } else {
                // 检查并申请必要权限后启动服务
                checkAndRequestPermissions()
            }
        }

        // 快速跳转滴滴出行
        btnOpenDidi.setOnClickListener {
            openDidiOrPrompt()
        }

        // 模拟测试强震动与弹窗提醒（方便用户在宿舍/办公室预先体验）
        btnTestAlarm.setOnClickListener {
            Toast.makeText(this, "📢 正在测试强震动与全屏弹窗提醒...", Toast.LENGTH_SHORT).show()
            testAlertHelper?.triggerBikeLockAlert("测试提醒：同学，青桔单车记得锁车！")
        }

        // 跳转小米应用详情页设置自启动与无限制省电
        btnOpenBatterySettings.setOnClickListener {
            openAppSettingDetails()
        }

        // 滴滴前台打开自动感应开关切换
        switchAutoDetect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!AppUsageHelper.hasUsageStatsPermission(this)) {
                    switchAutoDetect.isChecked = false
                    showUsageAccessPermissionDialog()
                } else {
                    DidiAutoDetectService.startService(this)
                    Toast.makeText(this, "✨ 滴滴自动感应已启动！打开滴滴出行将自动开始守护", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (DidiAutoDetectService.isServiceRunning) {
                    DidiAutoDetectService.stopService(this)
                    Toast.makeText(this, "已关闭滴滴自动感应", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 检查并请求必须权限
     */
    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // Android 10+ 需计步传感器权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        // Android 13+ 需通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startMonitoringService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startMonitoringService() {
        RideMonitorService.startService(this)
        btnToggleMonitor.text = getString(R.string.btn_stop_monitor)
        btnToggleMonitor.setBackgroundResource(R.drawable.bg_button_stop)
        Toast.makeText(this, "🚀 守护已开启！放入口袋即可，停下步行超30步将自动强震动提醒", Toast.LENGTH_LONG).show()
    }

    /**
     * 根据状态刷新卡片界面
     */
    @SuppressLint("SetTextI18n")
    private fun updateUiForState(
        state: MotionStateMachine.State,
        speed: Float,
        steps: Int,
        bufferRemaining: Int
    ) {
        if (speed > 0f) {
            tvSpeedVal.text = String.format("%.1f", speed)
        }
        if (steps > 0) {
            tvStepsVal.text = steps.toString()
        }
        if (bufferRemaining > 0) {
            tvBufferCountdown.text = "${bufferRemaining}s"
        } else {
            tvBufferCountdown.text = "--"
        }

        when (state) {
            MotionStateMachine.State.IDLE -> {
                tvStateBadge.text = "待机中"
                tvStateBadge.setTextColor(Color.parseColor("#8C8C8C"))
                tvStateBadge.setBackgroundColor(Color.parseColor("#1A8C8C8C"))
                tvStateTitle.text = getString(R.string.status_idle)
                tvStateDesc.text = "扫码上车后点击下方按钮开启守护；下车步行超30步自动强震动与弹窗提醒锁车。"
                tvSpeedVal.text = "0.0"
                tvStepsVal.text = "0"
            }

            MotionStateMachine.State.RIDING -> {
                tvStateBadge.text = "骑行中"
                tvStateBadge.setTextColor(Color.parseColor("#00C48C"))
                tvStateBadge.setBackgroundColor(Color.parseColor("#2600C48C"))
                tvStateTitle.text = "🚴 正在骑行守护中"
                tvStateDesc.text = "当前处于正常骑车状态，传感器正在检测减速停靠与离车行为。"
            }

            MotionStateMachine.State.MAYBE_STOPPED -> {
                tvStateBadge.text = "疑似停下"
                tvStateBadge.setTextColor(Color.parseColor("#FF9800"))
                tvStateBadge.setBackgroundColor(Color.parseColor("#26FF9800"))
                tvStateTitle.text = "⏳ 车速归零，防误判观察中"
                tvStateDesc.text = "若在等红灯，加速后自动恢复；若下车步行累计超30步将立刻强震动报警。"
            }

            MotionStateMachine.State.ALERTING -> {
                tvStateBadge.text = "🚨 报警中"
                tvStateBadge.setTextColor(Color.parseColor("#FF4D4F"))
                tvStateBadge.setBackgroundColor(Color.parseColor("#26FF4D4F"))
                tvStateTitle.text = "⚠️ 请立即确认青桔单车锁好！"
                tvStateDesc.text = "已检测到离开单车步行！请点击下方按钮打开滴滴还车，或点击“我已锁车”。"
            }
        }
    }

    private fun openDidiOrPrompt() {
        val pm = packageManager
        try {
            val intent = pm.getLaunchIntentForPackage("com.sdu.didi.psnger")
            if (intent != null) {
                startActivity(intent)
            } else {
                val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("didipasnger://"))
                startActivity(uriIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "未找到滴滴出行 App，请手动打开滴滴/微信小程序", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettingDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
        Toast.makeText(this, "请在设置中找到【自启动】设为允许，并将【省电策略】设为无限制", Toast.LENGTH_LONG).show()
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要运动与定位权限")
            .setMessage("为了在校园内不依赖固定停车点自动识别下车动作，BikeLocker 需要定位测速与计步器权限。数据仅在手机本地计算，绝不上传。")
            .setPositiveButton("前往授予") { _, _ -> openAppSettingDetails() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUsageAccessPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_detect_permission_title)
            .setMessage(R.string.auto_detect_permission_desc)
            .setPositiveButton("前往开启") { _, _ ->
                AppUsageHelper.openUsageAccessSettings(this)
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }
}
