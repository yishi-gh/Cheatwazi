package com.cheatwazi.app

import kotlin.math.hypot

/**
 * 游戏状态机：WAITING（等待手指）→ READY（触点稳定期，作弊信号窗口）
 * → SPIN（洗牌动画，作弊信号窗口仍开启）→ RESULT（揭晓）。
 * 纯 Kotlin，不依赖 Android API，时间由调用方注入以便单测。
 */
class GameEngine(
    val cfg: Config,
    val cheat: CheatEngine,
    /** 微抬按回的位置容差（px），由 UI 层按屏幕密度换算 */
    private val returnDistPx: Float,
    /** 随机源，可注入固定种子用于确定性模拟测试 */
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    data class Config(
        val mode: Int = MODE_WINNERS,
        val winnerCount: Int = 1,
        val teamCount: Int = 2,
    )

    interface Events {
        fun onFingerDown(p: Pointer) {}
        fun onFingerLift(p: Pointer) {}
        fun onResult() {}
    }

    enum class Phase { WAITING, READY, SPIN, RESULT }

    companion object {
        const val MODE_WINNERS = 0
        const val MODE_TEAMS = 1
        const val MIN_POINTERS = 2
        const val MAX_WINNERS = 8            // 赢家数量上限
        const val STABLE_MS = 1500L         // 稳定期=色环张开时长：合拢后立即开始读条（两圈连续）
        const val SPIN_SWEEP_MS = 1500L     // 读条弧单向张开一圈的时长（张开 241°/s=旋转速度，尾端惯性静止，对齐原版）
        const val SPIN_RECede_MS = 1500L    // 读条中途成员变动后，读条弧尾端缩回的时长（与新手指第一圈同速同止）
        const val LIFT_REJOIN_MS = 350L     // 抬起后等待按回的时长，超时视为离场
        const val LOSER_DIE_DELAY_MS = 1200L // 结果公布后输家保持此时长再同时缩小消失（对齐原版）
        const val RESULT_EXIT_DELAY_MS = 1200L // 全部松手后延迟此时长才开始消失动画（须大于 UI 合拢时长 300ms，避免半途跳变）
        const val RESULT_EXIT_MS = 600L     // 消失动画：赢家圆收缩 300ms + 黑色扩散 300ms
    }

    /** 结果揭晓瞬间的触点快照：RESULT 画面完全由快照驱动，与手指是否仍在屏无关 */
    class ResultSpot(
        val id: Int,
        val x: Float,
        val y: Float,
        val colorIndex: Int,
        val winner: Boolean,
        /** 淘汰动画起始偏移（相对 resultAt，ms）；-1 表示不淘汰 */
        val eliminateOffset: Long,
        /** 分队组号，选赢家模式无意义 */
        val team: Int,
    )

    var phase = Phase.WAITING
        private set
    val pointers = ArrayList<Pointer>()
    private var nextColor = 0
    private var nextOrdinal = 0
    private var stableSince = 0L
    private var spinStart = 0L
    /** 本轮读条的弧退回时长：正常进读条为 0（两圈连续），中途成员变动为 SPIN_RECede_MS */
    var spinRecedeMs = 0L
        private set
    var events: Events? = null

    /** 传感器侧写入的最新重力读数（设备坐标，m/s²），空表示不可用 */
    @Volatile
    var latestGravity: FloatArray? = null

    /** 序号内定（一次性）：本轮第 N 个（1 起，按创建序号）放手指的人内定获胜；
     *  命中才置 ordinalApplied（UI 层据此清除持久化设置），一局结束清空 */
    var ordinalTargets: Set<Int> = emptySet()
    var ordinalApplied: Boolean = false
        private set

    /** UI 层在序号内定已同步清除持久化设置后调用 */
    fun clearOrdinalApplied() {
        ordinalApplied = false
    }

    // —— RESULT 数据 ——
    /** 选赢家模式：入选赢家，按淘汰剩余顺序 */
    var winnerIds: List<Int> = emptyList()
        private set
    /** 分队模式：pointer id -> 组序号 */
    var teamOf: Map<Int, Int> = emptyMap()
        private set
    /** 被淘汰触点的动画起始偏移（相对 resultAt，ms） */
    var eliminateAt: Map<Int, Long> = emptyMap()
        private set
    var resultAt = 0L
        private set
    /** 退出动画起始时刻，-1 表示尚未开始退出；期间轻点不响应（对齐原版） */
    var exitingAt = -1L
        private set
    /** 全部手指离开屏幕的时刻，-1 表示仍有人按着 */
    private var allLiftAt = -1L
    /** 揭晓瞬间的触点快照，RESULT 绘制专用 */
    var resultSpots: List<ResultSpot> = emptyList()
        private set

    private fun newPointer(id: Int, x: Float, y: Float, time: Long): Pointer =
        Pointer(id, time, x, y).also { it.colorIndex = nextColor++ % 10 }

    val downCount: Int get() = pointers.count { it.isDown }

    fun onDown(id: Int, x: Float, y: Float, time: Long, pressure: Float, major: Float) {
        if (phase == Phase.RESULT) {
            // 对齐原版：结果期间新触点不响应，也不重置
            return
        }
        val p = tryRevive(id, x, y, time, pressure, major)
            ?: newPointer(id, x, y, time).also {
                it.creationOrdinal = ++nextOrdinal
                pointers.add(it)
                when (phase) {
                    Phase.READY -> stableSince = time    // 新成员加入：稳定期重来
                    Phase.SPIN -> {
                        spinStart = time                 // 新成员加入：重新读条
                        spinRecedeMs = SPIN_RECede_MS    // 已扫的弧先慢速退回再重扫
                        it.joinSpinAt = time             // UI 层播快速第一圈
                    }
                    else -> {}
                }
            }
        p.pressure = pressure
        p.touchMajor = major
        p.addSample(pressure, major)
        p.sealBaselineIfNeeded(time, CheatEngine.BASELINE_WINDOW_MS)
        // 序号通道：第 N 个创建序号的手指落下瞬间封印（命中才消费设置）
        if (p.creationOrdinal in ordinalTargets) {
            cheat.sealOrdinal(p.id)
            if (p.id in cheat.sealedTargetIds) ordinalApplied = true
        }
        events?.onFingerDown(p)
    }

    fun onMove(id: Int, x: Float, y: Float, time: Long, pressure: Float, major: Float) {
        val p = pointers.firstOrNull { it.id == id && it.isDown } ?: return
        p.x = x
        p.y = y
        p.pressure = pressure
        p.touchMajor = major
        p.addSample(pressure, major)
        p.sealBaselineIfNeeded(time, CheatEngine.BASELINE_WINDOW_MS)
    }

    fun onUp(id: Int, time: Long) {
        val p = pointers.firstOrNull { it.id == id && it.isDown } ?: return
        p.liftTime = time
        p.boostStart = -1L
        events?.onFingerLift(p)
        // 不立刻回退：给微抬按回留 LIFT_REJOIN_MS 窗口，由 tick 裁决
    }

    fun reset() {
        phase = Phase.WAITING
        pointers.clear()
        nextColor = 0
        nextOrdinal = 0
        stableSince = 0L
        winnerIds = emptyList()
        teamOf = emptyMap()
        eliminateAt = emptyMap()
        resultSpots = emptyList()
        exitingAt = -1L
        allLiftAt = -1L
        cheat.reset()
    }

    /** 每帧驱动：清理离场触点、推进状态转换、评估作弊信号 */
    fun tick(now: Long) {
        if (phase == Phase.RESULT) {
            if (exitingAt < 0) {
                // 只有全部松手才触发消失，且松手后延迟一段再播（不松手一直保持结果画面）
                if (downCount == 0) {
                    if (allLiftAt < 0) allLiftAt = now
                    if (now - allLiftAt >= RESULT_EXIT_DELAY_MS) exitingAt = now
                }
                pointers.removeAll { !it.isDown && now - it.liftTime > LIFT_REJOIN_MS }
            } else if (now - exitingAt >= RESULT_EXIT_MS) {
                reset()
                return
            }
            return
        }

        // 超时未按回的触点视为真正离场
        val gone = pointers.filter { !it.isDown && now - it.liftTime > LIFT_REJOIN_MS }
        if (gone.isNotEmpty()) {
            pointers.removeAll { it in gone }
            if (phase == Phase.SPIN && downCount >= MIN_POINTERS) {
                spinStart = now     // 有人离场但人数仍够：重新读条
                spinRecedeMs = SPIN_RECede_MS
            } else {
                toWaiting()
            }
        }

        val down = pointers.filter { it.isDown }
        // 基线封板不能只依赖 onMove：手指完全静止时不再产生触摸事件，
        // 必须由每帧 tick 推进封板，否则"按住不动"的压力信号永远无法检测
        down.forEach { it.sealBaselineIfNeeded(now, CheatEngine.BASELINE_WINDOW_MS) }

        when (phase) {
            Phase.WAITING -> {
                if (down.size >= MIN_POINTERS) {
                    phase = Phase.READY
                    stableSince = now
                    latestGravity?.let { cheat.lockGravity(it[0], it[1], it[2]) }
                }
            }
            Phase.READY -> {
                if (down.size >= MIN_POINTERS && now - stableSince >= STABLE_MS) {
                    phase = Phase.SPIN
                    spinStart = now
                    spinRecedeMs = 0L    // 正常进读条：无退回，两圈连续
                }
            }
            Phase.SPIN -> {
                if (down.size >= MIN_POINTERS &&
                    now - spinStart >= spinRecedeMs + SPIN_SWEEP_MS) {
                    settle(down, now)
                }
            }
            else -> {}
        }

        // 作弊信号窗口：稳定期与读条全程
        if (phase == Phase.READY || phase == Phase.SPIN) {
            cheat.evaluate(down, now, latestGravity)
        }
    }

    /** 读条进度 0..1，仅读条阶段有效 */
    fun readoutProgress(now: Long): Float =
        if (phase != Phase.SPIN) 0f
        else ((now - spinStart).toFloat() / (spinRecedeMs + SPIN_SWEEP_MS)).coerceIn(0f, 1f)

    /** 当前读条轮次的起始时刻，UI 层用它对齐读条弧的退回/扫入动画 */
    val spinStartAt: Long get() = spinStart

    private fun toWaiting() {
        if (phase != Phase.WAITING) {
            phase = Phase.WAITING
            stableSince = 0L
            // 姿态基准与压力超阈计时跨轮失效，下次就位重新锁定
            cheat.onRoundReset()
            pointers.forEach { it.boostStart = -1L }
        }
    }

    private fun tryRevive(
        id: Int, x: Float, y: Float, time: Long, pressure: Float, major: Float,
    ): Pointer? {
        if (!cheat.cfg.enabled || cheat.cfg.channel != CheatEngine.CHANNEL_LIFT) return null
        // 微抬只在就位与读条阶段判定；等待阶段的快速点击/双击是正常操作
        if (phase != Phase.READY && phase != Phase.SPIN) return null
        // 多个触点同时落在按回窗口内时，取距离最近者，避免复活错对象
        var best: Pointer? = null
        var bestDist = Float.MAX_VALUE
        for (cand in pointers) {
            if (cand.isDown) continue
            val dt = time - cand.liftTime
            if (dt in 0..cheat.cfg.liftWindowMs) {
                val dist = cand.distanceTo(x, y)
                if (dist <= returnDistPx && dist < bestDist) {
                    bestDist = dist
                    best = cand
                }
            }
        }
        best?.let {
            // 先在 liftTime 尚未清除时接受信号，再做复活
            cheat.onLiftReturn(it, time)
            it.liftTime = -1L
            return it
        }
        return null
    }

    /** SPIN 结束：应用作弊结果并编排淘汰/分组 */
    private fun settle(down: List<Pointer>, now: Long) {
        resultAt = now
        val ids = down.map { it.id }
        // 内定目标：统一来自通道封印（序号在手指落下时已封印），按触发时间序；
        // 压力通道按超阈倍率最重排序；名额不足时截断，其余随机补足/均分
        val rigged = cheat.sealedTargetIds
            .filter { it in ids }
            .let { list ->
                if (cheat.cfg.channel == CheatEngine.CHANNEL_PRESSURE) {
                    list.sortedByDescending { cheat.pressureRatioOf(it) }
                } else {
                    list
                }
            }
        ordinalTargets = emptySet()                    // 一次性：一局结束即消费

        if (cfg.mode == MODE_WINNERS) {
            val k = cfg.winnerCount.coerceIn(1, minOf(ids.size, MAX_WINNERS))
            val winners = rigged.take(k).toMutableList()
            val pool = ids.filter { it !in winners }.toMutableList()
            pool.shuffle(random)
            while (winners.size < k && pool.isNotEmpty()) winners.add(pool.removeAt(0))
            winnerIds = winners
            // 对齐原版：输家在结果公布后保持 1.2s 再同时缩小消失
            eliminateAt = ids.filter { it !in winners }.associateWith { LOSER_DIE_DELAY_MS }
        } else {
            // 分队：内定目标固定进第 1 组（组 0 容量自动扩到容纳全部内定者），
            // 其余人随机均匀分到各组
            val g = cfg.teamCount.coerceIn(2, 5)
            val n = ids.size
            val others = ids.filter { it !in rigged }.shuffled(random)
            val regular0 = n / g + if (n % g > 0) 1 else 0
            val team0 = rigged + others.take((regular0 - rigged.size).coerceAtLeast(0))
            val rest = others.drop((regular0 - rigged.size).coerceAtLeast(0))
            val map = HashMap<Int, Int>()
            team0.forEach { map[it] = 0 }
            // 剩余人均分到组 1..g-1：前 (rest % (g-1)) 组多一人
            val m = rest.size
            val div = g - 1
            val base = m / div
            val extra = m % div
            var idx = 0
            for (t in 1 until g) {
                val size = base + if (t - 1 < extra) 1 else 0
                repeat(size) {
                    if (idx < m) map[rest[idx++]] = t
                }
            }
            teamOf = map
        }
        // 固化揭晓瞬间的触点快照，此后手指抬起/清理均不影响结果画面
        val byId = down.associateBy { it.id }
        resultSpots = ids.map { id ->
            val p = byId.getValue(id)
            ResultSpot(
                id = id,
                x = p.x,
                y = p.y,
                colorIndex = p.colorIndex,
                winner = id in winnerIds,
                eliminateOffset = eliminateAt[id] ?: -1L,
                team = teamOf[id] ?: -1,
            )
        }
        phase = Phase.RESULT
        events?.onResult()
    }
}
