package com.campus.bikelocker.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 🧪 状态机核心算法逻辑单元测试
 * 
 * 验证目标：
 * 1. 正常下车步行：加速骑行 -> 降速停下 -> 连续走满 15 步 -> 立即触发报警
 * 2. 等待红绿灯场景：加速骑行 -> 降速停下 (0km/h) -> 未走动 -> 重新加速 (12km/h) -> 自动恢复骑行，不触发误报
 * 3. 停下车原地发呆场景：加速骑行 -> 降速停下 (0km/h) -> 静止 60 秒倒计时归零 -> 触发提醒
 */
class MotionStateMachineTest {

    private lateinit var stateMachine: MotionStateMachine
    private val stateTransitions = mutableListOf<MotionStateMachine.State>()
    private val alertReasons = mutableListOf<String>()
    private var scheduledRunnable: Runnable? = null

    // 模拟测试调度器，无需 Android Looper
    private val testDispatcher = object : MotionStateMachine.TimerDispatcher {
        override fun postDelayed(runnable: Runnable, delayMillis: Long) {
            scheduledRunnable = runnable
        }

        override fun removeCallbacks(runnable: Runnable) {
            if (scheduledRunnable == runnable) {
                scheduledRunnable = null
            }
        }
    }

    private val testListener = object : MotionStateMachine.StateListener {
        override fun onStateChanged(oldState: MotionStateMachine.State, newState: MotionStateMachine.State) {
            stateTransitions.add(newState)
        }

        override fun onBufferTick(remainingSeconds: Int) {}

        override fun onMetricsUpdate(currentSpeedKmh: Float, stepsSinceStop: Int) {}

        override fun onTriggerAlert(reason: String) {
            alertReasons.add(reason)
        }
    }

    @Before
    fun setUp() {
        stateTransitions.clear()
        alertReasons.clear()
        scheduledRunnable = null
        stateMachine = MotionStateMachine(testListener, testDispatcher)
    }

    @Test
    fun testRidingToStopAndWalkTriggersAlert() {
        // 1. 开启守护
        stateMachine.start()
        assertEquals(MotionStateMachine.State.RIDING, stateMachine.currentState)

        // 2. 模拟骑行速度 15 km/h
        stateMachine.onSpeedUpdate(15.0f)
        assertEquals(MotionStateMachine.State.RIDING, stateMachine.currentState)

        // 3. 模拟到达停车点，车速骤降到 0 km/h
        stateMachine.onSpeedUpdate(0.0f)
        assertEquals(MotionStateMachine.State.MAYBE_STOPPED, stateMachine.currentState)
        assertTrue("必须安排缓冲倒计时任务", scheduledRunnable != null)

        // 4. 模拟步行走了 29 步（尚未达到 30 步阈值，不应报警）
        for (i in 1..29) {
            stateMachine.onStepDetected()
        }
        assertEquals(MotionStateMachine.State.MAYBE_STOPPED, stateMachine.currentState)
        assertTrue(alertReasons.isEmpty())

        // 5. 迈出第 30 步！
        stateMachine.onStepDetected()
        assertEquals(MotionStateMachine.State.ALERTING, stateMachine.currentState)
        assertEquals(1, alertReasons.size)
        assertTrue("报警原因必须包含步行提醒", alertReasons[0].contains("下车步行"))
    }

    @Test
    fun testTrafficLightBufferRecovery() {
        // 1. 开启守护并正常骑行
        stateMachine.start()
        stateMachine.onSpeedUpdate(16.0f)
        assertEquals(MotionStateMachine.State.RIDING, stateMachine.currentState)

        // 2. 遇到红绿灯，减速停下
        stateMachine.onSpeedUpdate(0.0f)
        assertEquals(MotionStateMachine.State.MAYBE_STOPPED, stateMachine.currentState)

        // 3. 等红灯期间脚撑地未走动（或只微微晃动 2 步）
        stateMachine.onStepDetected()
        stateMachine.onStepDetected()
        assertEquals(MotionStateMachine.State.MAYBE_STOPPED, stateMachine.currentState)

        // 4. 绿灯亮起，重新加速到 12 km/h
        stateMachine.onSpeedUpdate(12.0f)

        // 验证：必须自动恢复到 RIDING 状态，防误判生效，且绝不报警
        assertEquals(MotionStateMachine.State.RIDING, stateMachine.currentState)
        assertTrue("等红灯不应触发任何警报", alertReasons.isEmpty())
    }
}
