package com.cheatwazi.app

import kotlin.math.hypot

/**
 * 伪随机作弊引擎：识别三种旁人难以察觉的物理信号，锁定"内定"赢家。
 * 信号只在游戏就位（READY）与洗牌（SPIN）阶段评估，触发一次即锁定，
 * 且锁定结果不可逆——中途有人退出也不失效，仅在结果编排时校验目标
 * 是否仍在屏。
 *
 * 通道 1（压力）：某触点的压力或接触面积相对自身按下初期的基线
 *   持续增长超过阈值——即用力按压屏幕——该触点成为内定赢家。
 *   用相对基线的倍率做阈值，天然免疫不同设备数值单位与手指大小差异。
 * 通道 2（姿态）：手机相对就位瞬间发生持续倾斜，倾斜造成的"下坡方向"
 *   指向谁，谁成为内定赢家。想指定某人，把手机朝他那侧轻轻压低即可。
 *   用重力增量在屏幕平面的投影判定方向，因此对手机初始摆放角度不敏感。
 * 通道 3（微抬）：手指快速抬起并在窗口时间内按回原位——视觉上
 *   几乎不可见的动作——该触点成为内定赢家。回按匹配在 GameEngine
 *   的 onDown 中完成，这里只负责接受结果。
 */
class CheatEngine(val cfg: Config) {

    data class Config(
        val enabled: Boolean = true,
        val pressureOn: Boolean = true,
        val tiltOn: Boolean = true,
        val liftOn: Boolean = true,
        /** 0 隐蔽（阈值高） 1 标准 2 灵敏（阈值低） */
        val sensitivity: Int = 1,
    ) {
        val pressureMult: Float
            get() = floatArrayOf(1.55f, 1.32f, 1.18f)[sensitivity.coerceIn(0, 2)]
        /** 重力投影幅值下限，m/s²；1.5 ≈ 倾斜 8.6° */
        val tiltMinMs2: Float
            get() = floatArrayOf(2.2f, 1.5f, 0.9f)[sensitivity.coerceIn(0, 2)]
        val liftWindowMs: Long
            get() = longArrayOf(280, 350, 450)[sensitivity.coerceIn(0, 2)]
    }

    companion object {
        const val BASELINE_WINDOW_MS = 350L    // 按下后采样基线的时长
        const val BOOST_HOLD_MS = 300L         // 压力超阈需持续的时长
        const val BOOST_RELEASE_FACTOR = 0.92f // 回落到该倍率以下才重置计时
        const val TILT_HOLD_MS = 500L          // 倾斜需持续的时长
        const val TILT_RELEASE_FACTOR = 0.8f
        const val CHANNEL_PRESSURE = 0
        const val CHANNEL_TILT = 1
        const val CHANNEL_LIFT = 2
    }

    /** 内定赢家 pointer id 列表（按触发顺序）；微抬通道允许多人各自触发 */
    val sealedTargetIds = ArrayList<Int>()
    /** 首个内定赢家 pointer id，-1 表示尚无信号 */
    var sealedTargetId = -1
        private set
    /** 首个触发通道，-1 无 */
    var sealedChannel = -1
        private set

    /** 就位瞬间锁定的重力基准（设备坐标，m/s²） */
    private var gravityRef: FloatArray? = null
    private var tiltStart = -1L

    val fired: Boolean get() = sealedTargetIds.isNotEmpty()

    /** 新一轮游戏开始时清空全部状态 */
    fun reset() {
        sealedTargetIds.clear()
        sealedTargetId = -1
        sealedChannel = -1
        gravityRef = null
        tiltStart = -1L
    }

    /** 游戏就位（进入 READY）时锁定姿态基准；每轮重新锁定，避免跨轮基准过期 */
    fun lockGravity(gx: Float, gy: Float, gz: Float) {
        gravityRef = floatArrayOf(gx, gy, gz)
        tiltStart = -1L
    }

    /**
     * 一轮中途回退（有人离场回等待）时清理姿态基准与倾斜计时；
     * 已锁定的内定目标不受影响（fire 不可逆语义）
     */
    fun onRoundReset() {
        gravityRef = null
        tiltStart = -1L
    }

    /** GameEngine 检测到"抬起-按回"成立时回调；调用方须在清除 p.liftTime 之前调用。
     *  微抬通道支持多目标：每个按回的触点各自封印（赢家名额在结算时裁剪） */
    fun onLiftReturn(p: Pointer, now: Long) {
        if (!cfg.enabled || !cfg.liftOn) return
        if (now - p.liftTime <= cfg.liftWindowMs + 60L && p.id !in sealedTargetIds) {
            seal(p.id, CHANNEL_LIFT)
        }
    }

    /**
     * 每帧评估压力与姿态通道。pointers 为在屏触点，gravity 为最新重力
     * 读数（可为 null：设备无传感器）。返回本次新锁定的目标 id，无则 -1。
     */
    fun evaluate(down: List<Pointer>, now: Long, gravity: FloatArray?): Int {
        if (!cfg.enabled || fired) return -1
        if (down.isEmpty()) return -1

        if (cfg.pressureOn) {
            val hit = evaluatePressure(down, now)
            if (hit >= 0) return hit
        }
        if (cfg.tiltOn && gravity != null) {
            val hit = evaluateTilt(down, now, gravity)
            if (hit >= 0) return hit
        }
        return -1
    }

    /**
     * 压力通道评估：所有在屏触点独立计时，仅当"恰好一个"触点持续超阈满
     * BOOST_HOLD_MS 时触发。多指同时超阈视为信号混乱（旁人无意重按），
     * 一律不采信。
     */
    private fun evaluatePressure(down: List<Pointer>, now: Long): Int {
        var boostedCount = 0
        var candidate: Pointer? = null
        for (p in down) {
            if (!p.baselineSealed) continue
            val ratioP = if (p.baselinePressure > 0f) p.pressure / p.baselinePressure else 1f
            val ratioM = if (p.baselineMajor > 0f) p.touchMajor / p.baselineMajor else 1f
            val ratio = maxOf(ratioP, ratioM)
            if (ratio >= cfg.pressureMult) {
                boostedCount++
                if (p.boostStart < 0L) p.boostStart = now
                if (now - p.boostStart >= BOOST_HOLD_MS) candidate = p
            } else if (ratio < cfg.pressureMult * BOOST_RELEASE_FACTOR) {
                p.boostStart = -1L
            }
        }
        if (boostedCount > 1) return -1
        candidate?.let {
            seal(it.id, CHANNEL_PRESSURE)
            return sealedTargetId
        }
        return -1
    }

    private fun evaluateTilt(down: List<Pointer>, now: Long, gravity: FloatArray): Int {
        val ref = gravityRef ?: return -1
        // TYPE_GRAVITY 读数指向天空（高侧），下坡方向取其反向；设备 y 向上而屏幕 y 向下，
        // 两个坐标系在此处各差一个符号，恰好相抵为同一负号
        val sx = -(gravity[0] - ref[0])
        val sy = gravity[1] - ref[1]
        val mag = hypot(sx, sy)
        if (mag >= cfg.tiltMinMs2) {
            if (tiltStart < 0L) {
                tiltStart = now
            } else if (now - tiltStart >= TILT_HOLD_MS) {
                // 内定赢家 = 投影方向（下坡方向）上最近的触点
                val cx = down.map { it.x }.average().toFloat()
                val cy = down.map { it.y }.average().toFloat()
                var best: Pointer? = null
                var bestCos = -2f
                for (p in down) {
                    val px = p.x - cx
                    val py = p.y - cy
                    val pr = hypot(px, py)
                    // 位于几何中心的触点到任意方向等距，给次高权重即可参与候选
                    val cos = if (pr < 1f) 0.99f else (px * sx + py * sy) / (pr * mag)
                    if (cos > bestCos) {
                        bestCos = cos
                        best = p
                    }
                }
                best?.let {
                    seal(it.id, CHANNEL_TILT)
                    return sealedTargetId
                }
            }
        } else if (mag < cfg.tiltMinMs2 * TILT_RELEASE_FACTOR) {
            tiltStart = -1L
        }
        return -1
    }

    private fun seal(id: Int, channel: Int) {
        if (sealedTargetIds.contains(id)) return
        sealedTargetIds.add(id)
        if (sealedTargetIds.size == 1) {
            sealedTargetId = id
            sealedChannel = channel
        }
    }
}
