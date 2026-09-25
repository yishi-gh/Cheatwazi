package com.cheatwazi.app

import kotlin.math.hypot

/**
 * 一个手指触点的完整状态。除常规触控数据外，还携带压力/接触面积
 * 通道的基线采样与超阈计时。纯数据结构，不依赖 Android API，便于单测。
 */
class Pointer(
    val id: Int,
    val downTime: Long,
    val downX: Float,
    val downY: Float,
) {
    var x: Float = downX
    var y: Float = downY
    var pressure: Float = 0f
    var touchMajor: Float = 0f

    // —— 压力 / 接触面积通道 ——
    var baselineSealed = false
        private set
    var baselinePressure = 0f
        private set
    var baselineMajor = 0f
        private set
    private val pressureSamples = ArrayList<Float>()
    private val majorSamples = ArrayList<Float>()

    /** 压力或面积相对基线持续超阈的起始时刻，-1 表示未超 */
    var boostStart = -1L

    // —— 微抬通道 ——
    /** >= 0 表示已抬起、正在等待按回；-1 表示在屏 */
    var liftTime = -1L

    var colorIndex = 0

    val isDown: Boolean get() = liftTime < 0

    /** 基线封板前持续采样；手指不动时只有 down 时的一次样本 */
    fun addSample(pressure: Float, major: Float) {
        if (baselineSealed) return
        if (pressure > 0f) pressureSamples.add(pressure)
        if (major > 0f) majorSamples.add(major)
    }

    /** 按下 BASELINE_WINDOW_MS 后封板，取中位数抗瞬时噪声 */
    fun sealBaselineIfNeeded(now: Long, windowMs: Long) {
        if (baselineSealed || now - downTime < windowMs) return
        baselinePressure = median(pressureSamples)
        baselineMajor = median(majorSamples)
        baselineSealed = true
    }

    private fun median(list: List<Float>): Float {
        if (list.isEmpty()) return 0f
        val s = list.sorted()
        return if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }

    fun distanceTo(x: Float, y: Float): Float = hypot(x - this.x, y - this.y)
}
