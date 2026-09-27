package com.cheatwazi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatEngineTest {

    private fun cfg(
        enabled: Boolean = true,
        channel: Int = CheatEngine.CHANNEL_LIFT,
        sens: Int = 1,
    ) = CheatEngine.Config(enabled, channel, sens)

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

    // —— 通道 1：压力（多目标：所有持续超阈者各自封印） ——
    @Test
    fun pressure_sustainedBoost_firesOnSelf() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE))
        val p = pointer(7)
        calibrate(p, 1.0f, 0f)
        assertEquals(1.0f, p.baselinePressure, 1e-6f)

        p.pressure = 1.4f
        e.evaluate(listOf(p), 500, null)   // 记录超阈起点
        assertFalse(e.fired)               // 持续不足 300ms
        e.evaluate(listOf(p), 600, null)
        assertFalse(e.fired)
        e.evaluate(listOf(p), 801, null)   // 持续满 300ms → 封印
        assertTrue(e.fired)
        assertEquals(7, e.sealedTargetId)
        assertEquals(CheatEngine.CHANNEL_PRESSURE, e.sealedChannel)
    }

    @Test
    fun pressure_transientSpikeThenRelease_doesNotFire() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE))
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
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE))
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 1.2f                       // 低于标准档 1.32 倍
        assertEquals(-1, e.evaluate(listOf(p), 500, null))
        assertEquals(-1, e.evaluate(listOf(p), 2000, null))
        assertFalse(e.fired)
    }

    @Test
    fun touchMajor_growth_firesWhenPressureFlat() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE))
        val p = pointer(3)
        calibrate(p, 1.0f, 20f)                 // pressure 恒 1.0 的设备，靠面积信号

        p.touchMajor = 27f                      // 1.35 倍，越过标准档 1.32
        e.evaluate(listOf(p), 500, null)
        assertFalse(e.fired)
        e.evaluate(listOf(p), 801, null)
        assertTrue(e.fired)
        assertEquals(3, e.sealedTargetId)
        assertEquals(CheatEngine.CHANNEL_PRESSURE, e.sealedChannel)
    }

    @Test
    fun sensitivity_hidden_requiresBiggerBoost() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE, sens = 0))
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 1.4f
        assertEquals(-1, e.evaluate(listOf(p), 500, null))
        assertEquals(-1, e.evaluate(listOf(p), 5000, null))
        assertFalse(e.fired)
    }

    // —— 压力通道多目标：多人重按各自封印，记录倍率供结算按最重排序 ——
    @Test
    fun pressure_twoPointersBoosting_bothSeal() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE))
        val a = pointer(1)
        val b = pointer(2)
        calibrate(a, 1.0f, 0f)
        calibrate(b, 1.0f, 0f)
        a.pressure = 1.4f
        b.pressure = 1.5f                       // 双人同时重按：都封印
        assertEquals(-1, e.evaluate(listOf(a, b), 500, null))
        assertEquals(-1, e.evaluate(listOf(a, b), 5000, null))
        assertEquals(listOf(1, 2), e.sealedTargetIds)
        assertEquals(1.4f, e.pressureRatioOf(1), 1e-6f)
        assertEquals(1.5f, e.pressureRatioOf(2), 1e-6f)
    }

    @Test
    fun pressure_ratioUpdatedWhileBoosting() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE))
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 1.4f
        e.evaluate(listOf(p), 500, null)
        p.pressure = 1.6f                       // 持续加力：倍率跟随更新
        e.evaluate(listOf(p), 5000, null)
        assertEquals(1.6f, e.pressureRatioOf(1), 1e-6f)
    }

    // —— 通道 2：姿态（语义：把目标那一侧压低，赢家 = 下坡方向最近者；单目标） ——
    @Test
    fun tilt_loweringRightSide_picksRightmostPointer() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_TILT))
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
    fun tilt_sealed_stopsEvaluating() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_TILT))
        e.lockGravity(0f, 0f, 9.8f)
        val left = pointer(1, -100f, 0f)
        val right = pointer(2, 100f, 0f)
        val g = floatArrayOf(-2f, 0f, 9.5f)
        e.evaluate(listOf(left, right), 0, g)
        e.evaluate(listOf(left, right), 501, g)
        assertEquals(listOf(2), e.sealedTargetIds)
        // 锁定一人后，即使倾斜持续也不改写目标
        e.evaluate(listOf(left, right), 5000, g)
        assertEquals(listOf(2), e.sealedTargetIds)
    }

    @Test
    fun tilt_releaseBeforeHold_resetsTimer() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_TILT))
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

    // —— 通道 3：微抬（多目标） ——
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

    @Test
    fun liftReturn_multiplePointers_eachSeals() {
        val e = CheatEngine(cfg())
        val a = pointer(1)
        val b = pointer(2)
        a.liftTime = 1000L
        e.onLiftReturn(a, 1100L)
        b.liftTime = 1200L
        e.onLiftReturn(b, 1300L)                // 第二个微抬者同样封印
        assertTrue(e.fired)
        assertEquals(listOf(1, 2), e.sealedTargetIds)
        assertEquals(1, e.sealedTargetId)       // 首个内定目标与通道保持不变
        assertEquals(CheatEngine.CHANNEL_LIFT, e.sealedChannel)
    }

    @Test
    fun liftReturn_samePointerTwice_singleSeal() {
        val e = CheatEngine(cfg())
        val p = pointer(1)
        p.liftTime = 1000L
        e.onLiftReturn(p, 1100L)
        p.liftTime = 2000L
        e.onLiftReturn(p, 2100L)
        assertEquals(listOf(1), e.sealedTargetIds)
    }

    // —— 通道 4：序号（由 GameEngine 在手指落下时触发） ——
    @Test
    fun sealOrdinal_onlyInOrdinalChannel() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_LIFT))
        e.sealOrdinal(7)
        assertFalse(e.fired)                    // 非序号通道时忽略

        val o = CheatEngine(cfg(channel = CheatEngine.CHANNEL_ORDINAL))
        o.sealOrdinal(7)
        assertTrue(o.fired)
        assertEquals(listOf(7), o.sealedTargetIds)
    }

    @Test
    fun sealOrdinal_samePointerTwice_singleSeal() {
        val e = CheatEngine(cfg(channel = CheatEngine.CHANNEL_ORDINAL))
        e.sealOrdinal(3)
        e.sealOrdinal(3)
        assertEquals(listOf(3), e.sealedTargetIds)
    }

    // —— 通道互斥：评估与回调只对选中通道生效 ——
    @Test
    fun channelMismatch_ignored() {
        val lift = CheatEngine(cfg())           // 微抬通道
        val p = pointer(1)
        calibrate(p, 1.0f, 0f)
        p.pressure = 1.4f
        assertEquals(-1, lift.evaluate(listOf(p), 5000, null))  // 压力信号不评
        assertFalse(lift.fired)

        val pressure = CheatEngine(cfg(channel = CheatEngine.CHANNEL_PRESSURE))
        p.liftTime = 1000L
        pressure.onLiftReturn(p, 1100L)         // 微抬回调不生效
        assertFalse(pressure.fired)
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
        e.sealOrdinal(9)
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
        p.liftTime = 1000L
        e.onLiftReturn(p, 1100L)
        assertTrue(e.fired)
        // 触发后继续评估不得改写目标
        assertEquals(-1, e.evaluate(listOf(p), 5000, null))
        assertEquals(1, e.sealedTargetId)
    }
}
