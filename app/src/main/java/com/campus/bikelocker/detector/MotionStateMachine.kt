package com.campus.bikelocker.detector

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 🚲 运动状态机 (MotionStateMachine)
 * 
 * 核心设计思路（通俗易懂版）：
 * 1. 为什么不能只看速度？
 *    - 骑车遇到红灯或行人，车速也会归零。如果立刻报警，那就是“假警报”。
 *    - 所以：速度降到 0 并不代表到达，必须有 60 秒的“观察缓冲期”。
 * 
 * 2. 怎么 100% 确定你已经到达下车了？
 *    - 只要你停好车，离开车子走去教室或宿舍，手机里的计步传感器就会连续捕捉到迈步动作。
 *    - 规则：在速度 < 2.5 km/h 的情况下，只要计步器累计走了【30步】以上，系统立即断定：你已弃车步行！
 * 
 * 3. 如果在车上坐着玩手机不动呢？
 *    - 90秒倒计时结束，车速依然为 0，系统同样会发出提醒。
 */
class MotionStateMachine(
    private val listener: StateListener,
    private val timerDispatcher: TimerDispatcher = HandlerTimerDispatcher()
) {
    companion object {
        private const val TAG = "MotionStateMachine"

        // 判定进入“骑行中”的最低速度门槛 (km/h)
        const val SPEED_RIDING_THRESHOLD = 7.5f

        // 判定“车辆停下/推车”的最高速度门槛 (km/h)
        const val SPEED_STOP_THRESHOLD = 2.5f

        // 防误判缓冲倒计时时长（秒）：等待红绿灯或礼让行人（已升级为90秒）
        const val BUFFER_DURATION_SECONDS = 90

        // 判定离开单车的最少步数：走超过30步即确信下车
        const val WALK_STEP_TRIGGER_COUNT = 30
    }

    /**
     * 定时调度器接口（抽离 Android Handler 依赖，便于脱离真机执行纯 JVM 单元测试）
     */
    interface TimerDispatcher {
        fun postDelayed(runnable: Runnable, delayMillis: Long)
        fun removeCallbacks(runnable: Runnable)
    }

    class HandlerTimerDispatcher : TimerDispatcher {
        private var handler: Handler? = null

        private fun getHandler(): Handler {
            if (handler == null) {
                handler = Handler(Looper.getMainLooper())
            }
            return handler!!
        }

        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            getHandler().postDelayed(runnable, delayMillis)
        }

        override fun removeCallbacks(runnable: Runnable) {
            getHandler().removeCallbacks(runnable)
        }
    }

    /**
     * 系统定义的四种生命周期状态
     */
    enum class State {
        IDLE,           // 待机中：尚未开启监测
        RIDING,         // 骑行中：检测到持续骑行速度
        MAYBE_STOPPED,  // 疑似停下：车速归零，正在缓冲防误判中（可能是等红灯，也可能是到达）
        ALERTING        // 强提醒中：确信已离车，正在响铃催促锁车
    }

    /**
     * 状态机回调接口，通知界面和后台服务做出相应响应
     */
    interface StateListener {
        fun onStateChanged(oldState: State, newState: State)
        fun onBufferTick(remainingSeconds: Int)
        fun onMetricsUpdate(currentSpeedKmh: Float, stepsSinceStop: Int)
        fun onTriggerAlert(reason: String)
    }

    // 当前状态，默认待机
    var currentState: State = State.IDLE
        private set

    // 疑似停下后累计的步行步数
    var stepsSinceStopped: Int = 0
        private set

    // 缓冲期剩余秒数
    var bufferRemainingSeconds: Int = BUFFER_DURATION_SECONDS
        private set

    // 90秒防误判倒计时的循环任务
    private val bufferCountdownRunnable = object : Runnable {
        override fun run() {
            if (currentState != State.MAYBE_STOPPED) return

            bufferRemainingSeconds--
            listener.onBufferTick(bufferRemainingSeconds)

            if (bufferRemainingSeconds <= 0) {
                // 倒计时结束，说明车停了整整 90 秒都没再动，触发锁车提醒
                Log.d(TAG, "缓冲时间结束，车速持续过低，触发提醒")
                transitionTo(State.ALERTING)
                listener.onTriggerAlert("单车已静止超 90 秒，如已到达请尽快还车！")
            } else {
                // 每隔 1 秒递减一次
                timerDispatcher.postDelayed(this, 1000)
            }
        }
    }

    /**
     * 开启监测
     */
    fun start() {
        reset()
        transitionTo(State.RIDING) // 开启时直接进入就绪/骑行监测
    }

    /**
     * 停止监测（用户主动锁车或退出）
     */
    fun stop() {
        reset()
        transitionTo(State.IDLE)
    }

    /**
     * 重置所有计数器与定时器
     */
    private fun reset() {
        timerDispatcher.removeCallbacks(bufferCountdownRunnable)
        stepsSinceStopped = 0
        bufferRemainingSeconds = BUFFER_DURATION_SECONDS
    }

    /**
     * 接收 GPS 定位速度更新 (单位: km/h)
     * 每次手机定位改变时由 Service 调用
     */
    fun onSpeedUpdate(speedKmh: Float) {
        if (currentState == State.IDLE || currentState == State.ALERTING) {
            return
        }

        listener.onMetricsUpdate(speedKmh, stepsSinceStopped)

        when (currentState) {
            State.RIDING -> {
                // 当前在骑行，如果速度骤降到停滞阈值以下，进入“疑似停下”观察期
                if (speedKmh < SPEED_STOP_THRESHOLD) {
                    Log.d(TAG, "速度降低至 ${speedKmh}km/h，进入疑似停下观察期")
                    startStopBuffer()
                }
            }

            State.MAYBE_STOPPED -> {
                // 正在观察期中，如果速度突然又飙升超过骑行门槛，说明刚才只是在【等红灯】或【避让行人】！
                if (speedKmh >= SPEED_RIDING_THRESHOLD) {
                    Log.d(TAG, "速度重新回升至 ${speedKmh}km/h，判定为等红灯结束，重回骑行状态")
                    cancelStopBuffer()
                    transitionTo(State.RIDING)
                }
            }

            else -> {}
        }
    }

    /**
     * 接收硬件计步传感器事件
     * 只要迈出一步，手机协处理器就会回调一次
     */
    fun onStepDetected() {
        if (currentState != State.MAYBE_STOPPED) {
            // 如果不在“疑似停下”阶段，踩踏板引起的轻微震动直接忽略
            return
        }

        stepsSinceStopped++
        listener.onMetricsUpdate(0f, stepsSinceStopped)
        Log.d(TAG, "下车后步数增加: $stepsSinceStopped / $WALK_STEP_TRIGGER_COUNT")

        // 关键判定：停下车后，连续步行超过 30 步，说明已经离开单车步行离开！
        if (stepsSinceStopped >= WALK_STEP_TRIGGER_COUNT) {
            Log.d(TAG, "检测到连续步行 $stepsSinceStopped 步，确信已离开单车，立即触发报警！")
            timerDispatcher.removeCallbacks(bufferCountdownRunnable)
            transitionTo(State.ALERTING)
            listener.onTriggerAlert("检测到您已下车步行，青桔单车锁好了吗？")
        }
    }

    /**
     * 进入疑似停下的缓冲观察期
     */
    private fun startStopBuffer() {
        stepsSinceStopped = 0
        bufferRemainingSeconds = BUFFER_DURATION_SECONDS
        transitionTo(State.MAYBE_STOPPED)
        timerDispatcher.removeCallbacks(bufferCountdownRunnable)
        timerDispatcher.postDelayed(bufferCountdownRunnable, 1000)
    }

    /**
     * 取消缓冲观察（证明刚才只是等红绿灯）
     */
    private fun cancelStopBuffer() {
        timerDispatcher.removeCallbacks(bufferCountdownRunnable)
        stepsSinceStopped = 0
        bufferRemainingSeconds = BUFFER_DURATION_SECONDS
    }

    /**
     * 切换状态并通知外界
     */
    private fun transitionTo(newState: State) {
        if (currentState == newState) return
        val oldState = currentState
        currentState = newState
        Log.i(TAG, "状态机切换: $oldState -> $newState")
        listener.onStateChanged(oldState, newState)
    }
}
