package com.cheatwazi.app

import kotlin.math.hypot

/**
 * 伪随机作弊引擎：识别旁人难以察觉的物理信号，锁定"内定"赢家。
 * 单通道互斥：同一时间只有 cfg.channel 指定的一条通道生效，运行期不存在
 * 多通道竞争；同通道内允许多目标（压力/微抬），结算时按各自规则排序占名额。
 *
 * 通道 1（压力）：触点的压力或接触面积相对自身按下初期的基线持续增长超过
 *   阈值——即用力按压屏幕。允许多人同时触发；赢家名额不足时由结算层按
 *   超阈倍率最重者取前 n。
 * 通道 2（倾斜）：手机相对就位瞬间持续倾斜，倾斜造成的"下坡方向"指向谁，
 *   谁成为内定赢家。单目标：锁定一人后本通道停止评估。
 * 通道 3（微抬）：手指快速抬起并在窗口时间内按回原位。事件驱动，允许多
 *   人各自触发。
 * 通道 4（序号）：由 GameEngine 在第 N 个创建序号的手指落下时直接触发，
 *   引擎不评估。
 *
 * 所有信号只在游戏就位（READY）与读条（SPIN）阶段产生，锁定结果不可逆
 * ——中途有人退出也不失效，仅在结算时校验目标是否仍在屏。
 */
class CheatEngine(val cfg: Config) {

    data class Config(
        val enabled: Boolean = true,
        /** 生效通道：-1 关闭 / 0 压力 / 1 倾斜 / 2 微抬（3 序号由 GameEngine 触发，不经此判断） */
        val channel: Int = CHANNEL_LIFT,
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
        const val CHANNEL_ORDINAL = 3
        const val CHANNEL_OFF = -1
    }

    /** 内定赢家 pointer id 列表（按触发顺序；压力通道由结算层按倍率重排） */
    val sealedTargetIds = ArrayList<Int>()
    /** 首个内定赢家 pointer id，-1 表示尚无信号 */
    var sealedTargetId = -1
        private set
    /** 首个触发通道，-1 无 */
    var sealedChannel = -1
        private set

    /** 压力通道：已封印触点的超阈倍率（结算时按最重排序用），持续更新 */
    private val pressureRatios = HashMap<Int, Float>()

    /** 就位瞬间锁定的重力基准（设备坐标，m/s²） */
    private var gravityRef: FloatArray? = null
    private var tiltStart = -1L
    /** 倾斜通道已锁定一人，停止评估 */
    private var tiltSealed = false

    val fired: Boolean get() = sealedTargetIds.isNotEmpty()

    /** 新一轮游戏开始时清空全部状态 */
    fun reset() {
        sealedTargetIds.clear()
        sealedTargetId = -1
        sealedChannel = -1
        pressureRatios.clear()
        gravityRef = null
        tiltStart = -1L
        tiltSealed = false
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
     *  仅微抬通道生效；允许多人各自封印（赢家名额在结算时裁剪） */
    fun onLiftReturn(p: Pointer, now: Long) {
        if (!cfg.enabled || cfg.channel != CHANNEL_LIFT) return
        if (now - p.liftTime <= cfg.liftWindowMs + 60L && p.id !in sealedTargetIds) {
            seal(p.id, CHANNEL_LIFT)
        }
    }

    /** 序号通道：GameEngine 在第 N 个创建序号的手指落下时调用 */
    fun sealOrdinal(id: Int) {
        if (!cfg.enabled || cfg.channel != CHANNEL_ORDINAL) return
        if (id !in sealedTargetIds) seal(id, CHANNEL_ORDINAL)
    }

    /** 压力通道封印者的超阈倍率，未记录返回 0 */
    fun pressureRatioOf(id: Int): Float = pressureRatios[id] ?: 0f

    /**
     * 每帧评估当前通道（仅压力/倾斜在此处理）。pointers 为在屏触点，
     * gravity 为最新重力读数（可为 null：设备无传感器）。
     */
    fun evaluate(down: List<Pointer>, now: Long, gravity: FloatArray?): Int {
        if (!cfg.enabled) return -1
        if (down.isEmpty()) return -1
        return when (cfg.channel) {
            CHANNEL_PRESSURE -> evaluatePressure(down, now)
            CHANNEL_TILT ->
                if (gravity != null && !tiltSealed) evaluateTilt(down, now, gravity) else -1
            else -> -1
        }
    }

    /**
     * 压力通道评估：所有在屏触点独立计时，持续超阈满 BOOST_HOLD_MS 者各自
     * 封印（多目标）；封印后持续记录倍率，供结算层按最重排序。
     */
    private fun evaluatePressure(down: List<Pointer>, now: Long): Int {
        for (p in down) {
            if (!p.baselineSealed) continue
            val ratioP = if (p.baselinePressure > 0f) p.pressure / p.baselinePressure else 1f
            val ratioM = if (p.baselineMajor > 0f) p.touchMajor / p.baselineMajor else 1f
            val ratio = maxOf(ratioP, ratioM)
            if (ratio >= cfg.pressureMult) {
                if (p.boostStart < 0L) p.boostStart = now
                if (now - p.boostStart >= BOOST_HOLD_MS) {
                    if (p.id !in sealedTargetIds) seal(p.id, CHANNEL_PRESSURE)
                    pressureRatios[p.id] = ratio
                }
            } else if (ratio < cfg.pressureMult * BOOST_RELEASE_FACTOR) {
                p.boostStart = -1L
            }
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
                    tiltSealed = true
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
