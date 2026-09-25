package com.cheatwazi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatEngineTest {

    private fun cfg(
        enabled: Boolean = true,
        pressure: Boolean = true,
        tilt: Boolean = true,
        lift: Boolean = true,
        sens: Int = 1,
    ) = CheatEngine.Config(enabled, pressure, tilt, lift, sens)

    private fun pointer(id: Int, x: Float = 0f, y: Float = 0f, t: Long = 0) =
        Pointer(id, t, x, y)

    /** 模拟按下后 BASELINE_WINDOW 内的均匀采样并封板 */
    private fun calibrate(p: Pointer, pressure: Float, major: Float, windowEnd: Long = 400) {
        var t = 0L
        while (t < CheatEngine.BASELINE_WINDOW_MS) {
            p.addSample(pressure, major)
            t += 16
            p.sealBaselineIfNeeded(t, CheatEngine.BASELINE_WINDOW_MS)
        }
        p.sealBaselineIfNeeded(windowEnd, CheatEngine.BASELINE_WINDOW_MS)
        assertTrue(p.baselineSealed)
    }

    // —— 通道 1：压力 ——
    @Test
    fun pressure_sustainedBoost_firesOnSelf() {
        val e = CheatEngine(cfg())
        val p = pointer(7)
        calibrate(p, 1.0f, 0f)
        assertEquals(1.0f, p.baselinePressure, 1e-6f)

        p.pressure = 1.4f
        assertEquals(-1, e.evaluate(listOf(p), 500, null)) // 记录超阈起点
        assertEquals(-1, e.evaluate(listOf(p), 600, null)) // 持续不足 300ms
        assertEquals(7, e.evaluate(listOf(p), 801, null))  // 持续满 300ms
        assertTrue(e.fired)
        assertEquals(7, e.sealedTargetId)
        assertEquals(CheatEngine.CHANNEL_PRESSURE, e.sealedChannel)
    }

    @Test
    fun pressure_transientSpikeThenRelease_doesNotFire() {
        val e = CheatEngine(cfg())
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)

        p.pressure = 1.4f
        e.evaluate(listOf(p), 500, null)
        p.pressure = 1.0f                       // 回落，重置计时
        e.evaluate(listOf(p), 600, null)
        p.pressure = 1.4f                       // 再次超阈，重新计时
        assertEquals(-1, e.evaluate(listOf(p), 700, null))
        assertEquals(-1, e.evaluate(listOf(p), 799, null))
        assertFalse(e.fired)
    }

    @Test
    fun pressure_belowThreshold_neverFires() {
        val e = CheatEngine(cfg())
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 1.2f                       // 低于标准档 1.32 倍
        assertEquals(-1, e.evaluate(listOf(p), 500, null))
        assertEquals(-1, e.evaluate(listOf(p), 2000, null))
        assertFalse(e.fired)
    }

    @Test
    fun touchMajor_growth_firesWhenPressureFlat() {
        val e = CheatEngine(cfg())
        val p = pointer(3)
        calibrate(p, 1.0f, 20f)                 // pressure 恒 1.0 的设备，靠面积信号

        p.touchMajor = 27f                      // 1.35 倍，越过标准档 1.32
        assertEquals(-1, e.evaluate(listOf(p), 500, null))
        assertEquals(3, e.evaluate(listOf(p), 801, null))
        assertEquals(CheatEngine.CHANNEL_PRESSURE, e.sealedChannel)
    }

    @Test
    fun sensitivity_hidden_requiresBiggerBoost() {
        val e = CheatEngine(cfg(sens = 0))      // 隐蔽档 1.55 倍
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 1.4f
        assertEquals(-1, e.evaluate(listOf(p), 500, null))
        assertEquals(-1, e.evaluate(listOf(p), 5000, null))
        assertFalse(e.fired)
    }

    // —— 压力通道多指消歧 ——
    @Test
    fun pressure_twoPointersBoosting_noOneFires() {
        val e = CheatEngine(cfg())
        val a = pointer(1)
        val b = pointer(2)
        calibrate(a, 1.0f, 0f)
        calibrate(b, 1.0f, 0f)
        a.pressure = 1.4f
        b.pressure = 1.4f                       // 双人同时重按，视为信号混乱
        assertEquals(-1, e.evaluate(listOf(a, b), 500, null))
        assertEquals(-1, e.evaluate(listOf(a, b), 5000, null))
        assertFalse(e.fired)
    }

    @Test
    fun pressure_singleBoosterAmongCalmPointers_fires() {
        val e = CheatEngine(cfg())
        val a = pointer(1)
        val b = pointer(2)
        calibrate(a, 1.0f, 0f)
        calibrate(b, 1.0f, 0f)
        a.pressure = 1.4f                       // 仅 a 重按，b 正常
        assertEquals(-1, e.evaluate(listOf(a, b), 500, null))
        assertEquals(1, e.evaluate(listOf(a, b), 801, null))
    }

    // —— 通道 2：姿态（语义：把目标那一侧压低，赢家 = 下坡方向最近者）——
    @Test
    fun tilt_loweringRightSide_picksRightmostPointer() {
        val e = CheatEngine(cfg())
        e.lockGravity(0f, 0f, 9.8f)
        val left = pointer(1, -100f, 0f)
        val right = pointer(2, 100f, 0f)
        // 压低右侧：天空方向偏向左（设备 -x），gravity 增量为负
        val g = floatArrayOf(-2f, 0f, 9.5f)

        assertEquals(-1, e.evaluate(listOf(left, right), 0, g))   // tiltStart 记录
        assertEquals(-1, e.evaluate(listOf(left, right), 400, g)) // 不足 500ms
        assertEquals(2, e.evaluate(listOf(left, right), 501, g))  // 下坡方向 = 右
        assertEquals(CheatEngine.CHANNEL_TILT, e.sealedChannel)
    }

    @Test
    fun tilt_loweringBottom_picksBottomPointer() {
        val e = CheatEngine(cfg())
        e.lockGravity(0f, 0f, 9.8f)
        val bottom = pointer(1, 0f, 100f)
        val top = pointer(2, 0f, -100f)
        // 压低底部：顶部翘起，天空方向偏向设备 +y（顶部）
        val g = floatArrayOf(0f, 2f, 9.5f)
        e.evaluate(listOf(bottom, top), 0, g)
        assertEquals(1, e.evaluate(listOf(bottom, top), 600, g))
    }

    @Test
    fun tilt_releaseBeforeHold_resetsTimer() {
        val e = CheatEngine(cfg())
        e.lockGravity(0f, 0f, 9.8f)
        val p = pointer(1, 100f, 0f)
        val tilted = floatArrayOf(2f, 0f, 9.5f)
        val flat = floatArrayOf(0f, 0f, 9.8f)

        e.evaluate(listOf(p), 0, tilted)
        e.evaluate(listOf(p), 300, flat)        // 回平，重置
        e.evaluate(listOf(p), 400, tilted)      // 重新计时
        assertEquals(-1, e.evaluate(listOf(p), 700, tilted))   // 仅 300ms
        assertEquals(1, e.evaluate(listOf(p), 901, tilted))    // 满 500ms
    }

    // —— 通道 3：微抬 ——
    @Test
    fun liftReturn_withinWindow_firesOnSelf() {
        val e = CheatEngine(cfg())
        val p = pointer(5)
        p.liftTime = 1000L
        e.onLiftReturn(p, 1100L)
        assertTrue(e.fired)
        assertEquals(5, e.sealedTargetId)
        assertEquals(CheatEngine.CHANNEL_LIFT, e.sealedChannel)
    }

    @Test
    fun liftReturn_beyondWindow_ignored() {
        val e = CheatEngine(cfg())
        val p = pointer(5)
        p.liftTime = 1000L
        e.onLiftReturn(p, 1500L)                // 超出 350+60ms 容差
        assertFalse(e.fired)
    }

    // —— 总开关 ——
    @Test
    fun disabled_engineNeverFires() {
        val e = CheatEngine(cfg(enabled = false))
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 2f
        assertEquals(-1, e.evaluate(listOf(p), 5000, null))
        p.liftTime = 0L
        e.onLiftReturn(p, 100L)
        assertFalse(e.fired)
    }

    @Test
    fun reset_clearsSealedTarget() {
        val e = CheatEngine(cfg())
        val p = pointer(1)
        p.liftTime = 1000L
        e.onLiftReturn(p, 1100L)
        assertTrue(e.fired)
        e.reset()
        assertFalse(e.fired)
        assertEquals(-1, e.sealedTargetId)
    }

    @Test
    fun fired_isIrreversibleWithinRound() {
        val e = CheatEngine(cfg())
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 1.4f
        e.evaluate(listOf(p), 500, null)
        e.evaluate(listOf(p), 900, null)
        assertTrue(e.fired)
        // 触发后继续评估不得改写目标
        assertEquals(-1, e.evaluate(listOf(p), 5000, null))
        assertEquals(1, e.sealedTargetId)
    }
}
