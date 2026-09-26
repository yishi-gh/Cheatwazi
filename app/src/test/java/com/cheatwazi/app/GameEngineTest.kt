package com.cheatwazi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    private fun engine(
        winnerCount: Int = 1,
        teamCount: Int = 2,
        mode: Int = GameEngine.MODE_WINNERS,
        cheat: CheatEngine = CheatEngine(CheatEngine.Config()),
    ) = GameEngine(GameEngine.Config(mode, winnerCount, teamCount), cheat, returnDistPx = 100f)

    /** 两指就位并推进到 RESULT，返回引擎与就位时间基准 */
    private fun runToResult(e: GameEngine, ids: List<Int>): Long {
        ids.forEachIndexed { i, id -> e.onDown(id, i * 100f, 0f, i * 50L, 1f, 0f) }
        e.tick(2000)     // READY（stableSince=2000）
        e.tick(2800)     // 800ms 稳定 → 读条（spinStart=2800）
        e.tick(4700)     // 1900ms 读条 → RESULT
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        return 4700L
    }

    @Test
    fun fullFlow_twoPointers_settlesWithOneWinner() {
        val e = engine()
        runToResult(e, listOf(1, 2))
        assertEquals(1, e.winnerIds.size)
        assertTrue(e.winnerIds[0] in listOf(1, 2))
    }

    @Test
    fun fullFlow_kWinners() {
        val e = engine(winnerCount = 2)
        runToResult(e, listOf(1, 2, 3, 4))
        assertEquals(2, e.winnerIds.size)
        assertEquals(2, e.eliminateAt.size)
    }

    @Test
    fun winnerCount_cappedAtMaxWinners() {
        val e = engine(winnerCount = 10)          // 超出上限 8 时截断
        runToResult(e, (1..10).toList())
        assertEquals(GameEngine.MAX_WINNERS, e.winnerIds.size)
        assertEquals(2, e.eliminateAt.size)
    }

    @Test
    fun newPointerDuringReady_resetsStableTimer() {
        val e = engine()
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.tick(100)                                  // READY，stableSince=100
        e.onDown(3, 200f, 0f, 500, 1f, 0f)           // 新手指加入
        e.tick(890)                                  // 距重置仅 390ms
        assertEquals(GameEngine.Phase.READY, e.phase)
        e.tick(1300)                                 // 距重置 800ms
        assertEquals(GameEngine.Phase.SPIN, e.phase)
    }

    @Test
    fun pointerLeavesBeyondRejoinWindow_backToWaiting() {
        val e = engine()
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.tick(100)                                  // READY
        e.onUp(1, 1000)
        e.tick(1200)                                 // 窗口内，仍挂起
        assertEquals(GameEngine.Phase.READY, e.phase)
        e.tick(1400)                                 // 400ms 未按回 → 离场
        assertEquals(GameEngine.Phase.WAITING, e.phase)
        assertEquals(1, e.pointers.size)
    }

    @Test
    fun liftReturn_keepsPhaseAndSealsCheat() {
        val cheat = CheatEngine(CheatEngine.Config())
        val e = engine(cheat = cheat)
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.tick(100)                                  // READY
        e.onUp(1, 1000)
        e.onDown(1, 10f, 0f, 1150, 1f, 0f)           // 350ms 内按回 10px 处
        assertTrue(cheat.fired)
        assertEquals(1, cheat.sealedTargetId)
        assertEquals(GameEngine.Phase.READY, e.phase)
        e.tick(3000)                                 // 稳定期重新计时后读条
        e.tick(4900)                                 // 读条结束
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(listOf(1), e.winnerIds)         // 内定目标保证入选
    }

    @Test
    fun sealedTargetGuaranteedInWinners() {
        val cheat = CheatEngine(CheatEngine.Config())
        val e = engine(cheat = cheat)
        // 通过微抬让 id=2 成为内定目标
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.onDown(3, 200f, 0f, 200, 1f, 0f)
        e.tick(2000)
        e.onUp(2, 2100)
        e.onDown(2, 100f, 0f, 2200, 1f, 0f)
        assertTrue(cheat.fired)
        e.tick(3000)
        e.tick(4900)
        assertEquals(2, e.winnerIds.first())
    }

    @Test
    fun teams_evenSplit_withSealedTargetInTeam0() {
        val cheat = CheatEngine(CheatEngine.Config())
        val e = engine(mode = GameEngine.MODE_TEAMS, teamCount = 2, cheat = cheat)
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.tick(2000)
        e.onUp(2, 2100)
        e.onDown(2, 100f, 0f, 2200, 1f, 0f)          // 内定 id=2
        e.onDown(3, 200f, 0f, 2300, 1f, 0f)
        e.onDown(4, 300f, 0f, 2400, 1f, 0f)
        e.tick(4000)
        e.tick(5900)
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(4, e.teamOf.size)
        val counts = e.teamOf.values.groupingBy { it }.eachCount()
        assertEquals(2, counts[0])
        assertEquals(2, counts[1])
        assertEquals(0, e.teamOf[2])                 // 内定目标进第 1 组
    }

    @Test
    fun resultPhase_tapsIgnored() {
        val e = engine()
        val resultAt = runToResult(e, listOf(1, 2))
        e.onUp(1, resultAt + 10)
        e.onUp(2, resultAt + 10)
        // 对齐原版：结果期间轻点既不重置也不加入（保护期内外一致）
        e.onDown(9, 50f, 50f, resultAt + 200, 1f, 0f)
        e.onDown(9, 50f, 50f, resultAt + 3000, 1f, 0f)
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(1, e.winnerIds.size)
        assertTrue(e.pointers.none { it.id == 9 })
    }

    @Test
    fun resultPhase_noExitWithoutLift() {
        val e = engine()
        val resultAt = runToResult(e, listOf(1, 2))
        // 手指一直按着不抬：结果画面常驻，不进入消失动画
        e.tick(resultAt + 30000)
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(-1L, e.exitingAt)
    }

    @Test
    fun resultPhase_oneFingerHolds_noExit() {
        val e = engine()
        val resultAt = runToResult(e, listOf(1, 2))
        e.onUp(1, resultAt + 10)                   // 一人抬手，另一人仍按着
        e.tick(resultAt + 30000)
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(-1L, e.exitingAt)
    }

    @Test
    fun resultPhase_exitDelayStartsAfterAllLift() {
        val e = engine()
        val resultAt = runToResult(e, listOf(1, 2))
        e.onUp(1, resultAt + 10)
        e.onUp(2, resultAt + 10)
        val liftAt = resultAt + 20                 // 首个 tick 看到全部离场的时刻
        e.tick(liftAt)
        e.tick(liftAt + GameEngine.RESULT_EXIT_DELAY_MS - 1)
        assertEquals(-1L, e.exitingAt)             // 延迟未到不开始消失
        e.tick(liftAt + GameEngine.RESULT_EXIT_DELAY_MS)
        assertEquals(liftAt + GameEngine.RESULT_EXIT_DELAY_MS, e.exitingAt)
    }

    @Test
    fun resultPhase_exitAnimationRunsAfterDelay() {
        val e = engine()
        val resultAt = runToResult(e, listOf(1, 2))
        e.onUp(1, resultAt + 10)
        e.onUp(2, resultAt + 10)
        e.tick(resultAt + 20)                      // 松手开始计时
        val exitStart = resultAt + 20 + GameEngine.RESULT_EXIT_DELAY_MS
        e.tick(exitStart)
        e.tick(exitStart + GameEngine.RESULT_EXIT_MS - 1)
        assertEquals(GameEngine.Phase.RESULT, e.phase)   // 消失动画进行中
        e.tick(exitStart + GameEngine.RESULT_EXIT_MS)
        assertEquals(GameEngine.Phase.WAITING, e.phase)  // 收缩+扩散完成后回等待
        assertTrue(e.pointers.isEmpty())
    }

    @Test
    fun liftReturn_multiLifters_allSealedWin() {
        val cheat = CheatEngine(CheatEngine.Config())
        val e = engine(winnerCount = 2, cheat = cheat)
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 50, 1f, 0f)
        e.onDown(3, 200f, 0f, 100, 1f, 0f)
        e.onDown(4, 300f, 0f, 150, 1f, 0f)
        e.tick(300)                                  // READY
        e.onUp(1, 1000)
        e.onDown(1, 0f, 0f, 1100, 1f, 0f)            // 1 号微抬按回
        e.onUp(3, 1200)
        e.onDown(3, 200f, 0f, 1300, 1f, 0f)          // 3 号微抬按回
        assertEquals(listOf(1, 3), cheat.sealedTargetIds)
        e.tick(2000)                                 // → SPIN
        e.tick(3900)                                 // → RESULT
        assertEquals(listOf(1, 3), e.winnerIds)      // 两个微抬者都入选
        assertEquals(2, e.eliminateAt.size)
    }

    @Test
    fun ordinalTargets_nthFingerWins() {
        val e = engine(winnerCount = 1)
        e.ordinalTargets = setOf(2)
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 50, 1f, 0f)
        e.onDown(3, 200f, 0f, 100, 1f, 0f)
        e.onDown(4, 300f, 0f, 150, 1f, 0f)
        e.tick(2000)
        e.tick(2800)
        e.tick(4700)
        assertEquals(listOf(2), e.winnerIds)         // 第 2 个放手指的人获胜
        assertTrue(e.ordinalApplied)
        assertTrue(e.ordinalTargets.isEmpty())       // 一次性：结算即消费
    }

    @Test
    fun ordinalTargets_multiOrdinalCappedByWinnerCount() {
        val e = engine(winnerCount = 2)
        e.ordinalTargets = setOf(1, 3, 4)
        runToResult(e, listOf(1, 2, 3, 4))
        assertEquals(listOf(1, 3), e.winnerIds)      // 按放手指顺序取前两名
        assertTrue(e.ordinalApplied)
    }

    @Test
    fun ordinalTargets_teams_nthFingerInFirstTeam() {
        val e = engine(mode = GameEngine.MODE_TEAMS, teamCount = 2)
        e.ordinalTargets = setOf(3)
        runToResult(e, listOf(1, 2, 3, 4))
        assertEquals(0, e.teamOf[3])                 // 序号内定进第 1 组
        assertEquals(2, e.teamOf.values.count { it == 0 })
    }

    @Test
    fun singlePointer_neverStarts() {
        val e = engine()
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.tick(5000)
        assertEquals(GameEngine.Phase.WAITING, e.phase)
    }

    // —— 微抬按回位置容差边界 ——
    @Test
    fun liftReturn_withinDistanceBound_revives() {
        val cheat = CheatEngine(CheatEngine.Config())
        val e = engine(cheat = cheat)
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.tick(100)
        e.onUp(1, 1000)
        e.onDown(1, 99f, 0f, 1200, 1f, 0f)      // 99f < returnDistPx(100f)
        assertTrue(cheat.fired)
    }

    @Test
    fun liftReturn_beyondDistanceBound_startsFresh() {
        val cheat = CheatEngine(CheatEngine.Config())
        val e = engine(cheat = cheat)
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.tick(100)
        e.onUp(1, 1000)
        e.onDown(1, 150f, 0f, 1200, 1f, 0f)     // 150f > returnDistPx(100f)
        assertFalse(cheat.fired)                 // 不复活跃旧信号
        assertEquals(3, e.pointers.size)         // 作为新触点加入
    }

    @Test
    fun liftReturn_multipleCandidates_picksNearest() {
        val cheat = CheatEngine(CheatEngine.Config())
        val e = engine(cheat = cheat)
        e.onDown(1, 0f, 0f, 0, 1f, 0f)
        e.onDown(2, 100f, 0f, 100, 1f, 0f)
        e.tick(100)
        e.onUp(1, 1000)
        e.onUp(2, 1000)
        // 按回位置距触点 1 为 70f、距触点 2 为 30f，双候选都满足窗口
        e.onDown(1, 70f, 0f, 1200, 1f, 0f)
        assertTrue(cheat.fired)
        assertEquals(2, cheat.sealedTargetId)    // 复活距离更近的触点 2
        assertTrue(e.pointers.first { it.id == 2 }.isDown)   // 触点 2 被复活在屏
        assertFalse(e.pointers.first { it.id == 1 }.isDown)  // 触点 1 未被误复活
    }
}
