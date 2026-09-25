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
        e.tick(4400)     // 1600ms 读条 → RESULT
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        return 4400L
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
        e.tick(4600)                                 // 读条结束
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
        e.tick(4600)
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
        e.tick(5600)
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(4, e.teamOf.size)
        val counts = e.teamOf.values.groupingBy { it }.eachCount()
        assertEquals(2, counts[0])
        assertEquals(2, counts[1])
        assertEquals(0, e.teamOf[2])                 // 内定目标进第 1 组
    }

    @Test
    fun resultPhase_holdsUntilTap() {
        val e = engine()
        val resultAt = runToResult(e, listOf(1, 2))
        e.onUp(1, resultAt + 10)
        e.onUp(2, resultAt + 10)
        e.tick(resultAt + 500)                       // 全部抬起也不自动重置
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        e.onDown(9, 50f, 50f, resultAt + 900, 1f, 0f) // 覆盖停留 0.8s 后轻点重置
        assertEquals(GameEngine.Phase.WAITING, e.phase)
        assertTrue(e.pointers.isEmpty())
    }

    @Test
    fun resultPhase_tapDuringHoldIgnored() {
        val e = engine()
        val resultAt = runToResult(e, listOf(1, 2))
        e.onDown(9, 50f, 50f, resultAt + 200, 1f, 0f) // 结果覆盖完成前的轻点被忽略
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(1, e.winnerIds.size)
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
