package com.cheatwazi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 模拟测试：大样本蒙特卡洛与传感器噪声注入，背书两个核心性质——
 * 无信号时选中严格均匀（社区曾对同类 app 的随机性有实测质疑，
 * 见 BoardGameGeek "Just how random is Chwazi?"），有噪声时不误触发。
 */
class SimulationTest {

    private fun engine(
        players: Int = 4,
        winnerCount: Int = 1,
        seed: Long = 20260925L,
    ): GameEngine = GameEngine(
        GameEngine.Config(winnerCount = winnerCount),
        CheatEngine(CheatEngine.Config(enabled = false)),
        returnDistPx = 100f,
        random = Random(seed),
    )

    /** 模拟一局：players 人依次按下（间隔 50ms）并推进到 RESULT */
    private fun playRound(
        e: GameEngine,
        players: Int,
        startAt: Long = 1000L,
    ): Long {
        var t = startAt
        for (i in 1..players) {
            e.onDown(i, i * 100f, 0f, t, 1f, 0f)
            t += 50L
        }
        t += 10
        e.tick(t)          // → READY
        t += 800
        e.tick(t)          // → 读条
        t += 1900
        e.tick(t)          // → RESULT
        return t
    }

    private fun calibrate(p: Pointer, pressure: Float, major: Float) {
        var t = 0L
        while (t < CheatEngine.BASELINE_WINDOW_MS) {
            p.addSample(pressure, major)
            t += 16
            p.sealBaselineIfNeeded(t, CheatEngine.BASELINE_WINDOW_MS)
        }
        p.sealBaselineIfNeeded(400, CheatEngine.BASELINE_WINDOW_MS)
    }

    @Test
    fun noSignal_selectionIsUniformAcrossPlayers() {
        val players = 4
        val rounds = 20000
        val counts = IntArray(players)
        repeat(rounds) {
            val e = engine(players = players, seed = it.toLong())
            playRound(e, players)
            counts[e.winnerIds[0] - 1]++
        }
        // 期望各 5000；固定种子下确定性运行，容差 ±3% 为实现正确性留裕量
        val expected = rounds / players
        val tolerance = (rounds * 0.03).toInt()
        counts.forEachIndexed { i, c ->
            assertTrue(
                "玩家 ${i + 1} 获胜 $c 次，期望 $expected±$tolerance",
                c in (expected - tolerance)..(expected + tolerance),
            )
        }
    }

    @Test
    fun noSignal_multiWinner_selectionStillUniform() {
        val players = 4
        val rounds = 10000
        val picked = IntArray(players)
        repeat(rounds) {
            val e = engine(players = players, winnerCount = 2, seed = it.toLong())
            playRound(e, players)
            e.winnerIds.forEach { w -> picked[w - 1]++ }
        }
        // 2 名赢家时各玩家入选率期望 0.5（5000 次）
        val expected = rounds / 2
        val tolerance = (expected * 0.03).toInt()
        picked.forEachIndexed { i, c ->
            assertTrue(
                "玩家 ${i + 1} 入选 $c 次，期望 $expected±$tolerance",
                c in (expected - tolerance)..(expected + tolerance),
            )
        }
    }

    @Test
    fun pressureChannel_sensorNoise_doesNotMisfire() {
        val e = CheatEngine(CheatEngine.Config())
        val p = Pointer(1, 0L, 0f, 0f)
        calibrate(p, 1.0f, 0f)

        // ±8% 压力抖动（电容屏典型噪声的放大值），连续 3 秒不得触发
        val rnd = Random(42)
        var t = 500L
        repeat(3000) {
            p.pressure = 1.0f + (rnd.nextFloat() - 0.5f) * 0.16f
            assertEquals(-1, e.evaluate(listOf(p), t, null))
            t += 16
        }
        assertFalse(e.fired)
    }

    @Test
    fun tiltChannel_sensorNoise_doesNotMisfire() {
        val e = CheatEngine(CheatEngine.Config())
        e.lockGravity(0f, 0f, 9.8f)
        val left = Pointer(1, 0L, -100f, 0f)
        val right = Pointer(2, 0L, 100f, 0f)

        // ±0.4 m/s² 重力抖动，投影幅值远低于标准档 1.5，连续 3 秒不得触发
        val rnd = Random(7)
        var t = 0L
        repeat(3000) {
            val g = floatArrayOf(
                (rnd.nextFloat() - 0.5f) * 0.8f,
                (rnd.nextFloat() - 0.5f) * 0.8f,
                9.8f,
            )
            e.evaluate(listOf(left, right), t, g)
            t += 20
        }
        assertFalse(e.fired)
    }

    @Test
    fun fullSession_leaveAndRejoin_flowsCorrectly() {
        val e = engine()
        var t = 1000L
        for (i in 1..4) {
            e.onDown(i, i * 100f, 0f, t, 1f, 0f)
            t += 50L
        }
        t += 10
        e.tick(t)
        assertEquals(GameEngine.Phase.READY, e.phase)

        // 玩家 3 手滑离场：剩 3 人 ≥2，剩余玩家立即重新进入稳定期继续（原版语义）
        e.onUp(3, t)
        t += 500
        e.tick(t)
        assertEquals(GameEngine.Phase.READY, e.phase)
        assertEquals(3, e.pointers.size)

        // 玩家 3 重新按下，稳定计时重置，四人再次推进至结果
        e.onDown(3, 300f, 0f, t, 1f, 0f)
        t += 10
        e.tick(t)
        assertEquals(GameEngine.Phase.READY, e.phase)
        t += 800
        e.tick(t)
        assertEquals(GameEngine.Phase.SPIN, e.phase)
        t += 1900
        e.tick(t)
        assertEquals(GameEngine.Phase.RESULT, e.phase)
        assertEquals(1, e.winnerIds.size)
        assertTrue(e.winnerIds[0] in 1..4)
    }
}
