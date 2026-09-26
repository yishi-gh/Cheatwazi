package com.cheatwazi.app

import android.content.Context

/**
 * 全局配置存取。SettingsActivity 写入，ChwaziView 在 onResume 读取。
 * 字段即配置契约，两侧都以 Snapshot 为准。
 */
object Prefs {

    const val NAME = "cheatwazi"

    data class Snapshot(
        val mode: Int = GameEngine.MODE_WINNERS,
        val winnerCount: Int = 1,
        val teamCount: Int = 2,
        val soundOn: Boolean = true,
        val hapticsOn: Boolean = true,
        val cheatEnabled: Boolean = true,
        val pressureOn: Boolean = true,
        val tiltOn: Boolean = true,
        val liftOn: Boolean = true,
        val sensitivity: Int = 1,
        /** 序号内定（一次性）：下一局第 N 个放手指的人获胜，用后清除 */
        val ordinalTargets: Set<Int> = emptySet(),
    )

    /** 读取序号内定设置 */
    fun ordinalTargets(ctx: Context): Set<Int> =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("ordinalTargets", "")
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in 1..GameEngine.MAX_WINNERS }
            ?.toSet()
            ?: emptySet()

    /** 写入/清除（传空集合清除）序号内定设置 */
    fun setOrdinalTargets(ctx: Context, targets: Set<Int>) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("ordinalTargets", targets.sorted().joinToString(","))
            .apply()
    }

    fun load(ctx: Context): Snapshot {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return Snapshot(
            mode = p.getInt("mode", GameEngine.MODE_WINNERS),
            winnerCount = p.getInt("winnerCount", 1),
            teamCount = p.getInt("teamCount", 2),
            soundOn = p.getBoolean("soundOn", true),
            hapticsOn = p.getBoolean("hapticsOn", true),
            cheatEnabled = p.getBoolean("cheatEnabled", true),
            pressureOn = p.getBoolean("pressureOn", true),
            tiltOn = p.getBoolean("tiltOn", true),
            liftOn = p.getBoolean("liftOn", true),
            sensitivity = p.getInt("sensitivity", 1),
            ordinalTargets = ordinalTargets(ctx),
        )
    }

    /** 压力通道硬件支持检测结果（自检采样写入） */
    const val PRESSURE_UNKNOWN = 0
    const val PRESSURE_OK = 1
    const val PRESSURE_DEAD = 2

    fun pressureSupport(ctx: Context): Int =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("pressureSupport", PRESSURE_UNKNOWN)

    fun setPressureSupport(ctx: Context, value: Int) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("pressureSupport", value)
            .apply()
    }

    fun save(ctx: Context, s: Snapshot) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("mode", s.mode)
            .putInt("winnerCount", s.winnerCount)
            .putInt("teamCount", s.teamCount)
            .putBoolean("soundOn", s.soundOn)
            .putBoolean("hapticsOn", s.hapticsOn)
            .putBoolean("cheatEnabled", s.cheatEnabled)
            .putBoolean("pressureOn", s.pressureOn)
            .putBoolean("tiltOn", s.tiltOn)
            .putBoolean("liftOn", s.liftOn)
            .putInt("sensitivity", s.sensitivity)
            .putString("ordinalTargets", s.ordinalTargets.sorted().joinToString(","))
            .apply()
    }
}
