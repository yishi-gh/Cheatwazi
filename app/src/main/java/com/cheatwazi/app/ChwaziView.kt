package com.cheatwazi.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 全屏自定义 View，承载多人手指挑选器的触控采集与动画绘制。
 * 视觉风格对齐原版 Chwazi：纯黑底 + 高饱和彩色触点圆。
 */
class ChwaziView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // —— 颜色资源 ——
    private val bgColor = ContextCompat.getColor(context, R.color.bg)
    private val hintTextColor = ContextCompat.getColor(context, R.color.hint_text)
    private val winnerRingColor = ContextCompat.getColor(context, R.color.winner_ring)

    private val pointerColorResIds = intArrayOf(
        R.color.p0, R.color.p1, R.color.p2, R.color.p3, R.color.p4,
        R.color.p5, R.color.p6, R.color.p7, R.color.p8, R.color.p9,
    )
    private val pointerColors = IntArray(pointerColorResIds.size) {
        ContextCompat.getColor(context, pointerColorResIds[it])
    }
    private val pointerCenterColors = IntArray(pointerColors.size) {
        brightenColor(pointerColors[it], 0.08f)
    }

    private val teamColorResIds = intArrayOf(
        R.color.t0, R.color.t1, R.color.t2, R.color.t3, R.color.t4,
    )
    private val teamColors = IntArray(teamColorResIds.size) {
        ContextCompat.getColor(context, teamColorResIds[it])
    }
    private val teamCenterColors = IntArray(teamColors.size) {
        brightenColor(teamColors[it], 0.08f)
    }

    // —— 画笔 ——
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val winnerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = winnerRingColor
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintTextColor
        textSize = 16f * scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val teamNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // —— 引擎、音效与震动 ——
    private lateinit var engine: GameEngine
    private var cachedGravity: FloatArray? = null
    private var soundOn = true
    private var hapticsOn = true

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    /** 五声音阶触点音，按下序号取模映射（C5 D5 E5 G5 A5） */
    private val popSoundIds = intArrayOf(
        R.raw.pop_c5, R.raw.pop_d5, R.raw.pop_e5, R.raw.pop_g5, R.raw.pop_a5,
    ).map { soundPool.load(context, it, 1) }.toIntArray()
    private val elimSoundId = soundPool.load(context, R.raw.elim, 1)
    private val winSoundId = soundPool.load(context, R.raw.win, 1)

    /** 本轮已触发淘汰音的触点，RESULT 结束时清空 */
    private val elimFiredIds = HashSet<Int>()

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // —— 传感器 ——
    private val sensorManager: SensorManager? by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private var isSensorRegistered = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values
            if (values.size < 3) return
            // 一阶低通：滤掉加速度计高频抖动，避免倾斜保持计时被噪声打断
            val prev = cachedGravity
            cachedGravity = if (prev == null) {
                floatArrayOf(values[0], values[1], values[2])
            } else {
                floatArrayOf(
                    prev[0] + LPF_ALPHA * (values[0] - prev[0]),
                    prev[1] + LPF_ALPHA * (values[1] - prev[1]),
                    prev[2] + LPF_ALPHA * (values[2] - prev[2]),
                )
            }
            if (::engine.isInitialized) {
                engine.latestGravity = cachedGravity
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // —— 隐蔽设置入口：等待界面用手指画一个小三角 ——
    private val gesturePoints = ArrayList<Pair<Float, Float>>()

    init {
        (context as? LifecycleOwner)?.lifecycle?.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                Lifecycle.Event.ON_DESTROY -> unregisterSensor()
                else -> {}
            }
        })
        reloadConfig()
    }

    // ==========================================
    // 配置与传感器生命周期
    // ==========================================

    fun reloadConfig() {
        val snapshot = Prefs.load(context)
        val returnDistPx = 60f * density
        soundOn = snapshot.soundOn
        hapticsOn = snapshot.hapticsOn

        val cheatCfg = CheatEngine.Config(
            enabled = snapshot.cheatEnabled,
            pressureOn = snapshot.pressureOn,
            tiltOn = snapshot.tiltOn,
            liftOn = snapshot.liftOn,
            sensitivity = snapshot.sensitivity,
        )
        val gameCfg = GameEngine.Config(
            mode = snapshot.mode,
            winnerCount = snapshot.winnerCount,
            teamCount = snapshot.teamCount,
        )

        val cheatEngine = CheatEngine(cheatCfg)
        val newEngine = GameEngine(gameCfg, cheatEngine, returnDistPx)
        newEngine.latestGravity = cachedGravity

        newEngine.events = object : GameEngine.Events {
            override fun onFingerDown(p: Pointer) {
                if (snapshot.hapticsOn) vibrate(10L)
                if (snapshot.soundOn) playPop(p.colorIndex)
            }

            override fun onResult() {
                if (snapshot.hapticsOn) vibrateResult()
                if (snapshot.soundOn) playWin()
            }
        }

        engine = newEngine
        registerSensor()
        invalidate()
    }

    fun onResume() {
        registerSensor()
    }

    fun onPause() {
        unregisterSensor()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerSensor()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterSensor()
        soundPool.release()
    }

    private fun registerSensor() {
        if (isSensorRegistered) return
        val sm = sensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor != null) {
            sm.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            isSensorRegistered = true
        }
    }

    private fun unregisterSensor() {
        if (!isSensorRegistered) return
        sensorManager?.unregisterListener(sensorListener)
        isSensorRegistered = false
    }

    private fun vibrate(durationMs: Long) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
        }
    }

    private fun vibrateResult() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val pattern = longArrayOf(0, 50, 60, 90)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(pattern, -1)
        }
    }

    private fun playPop(colorIndex: Int) {
        val id = popSoundIds[colorIndex.mod(popSoundIds.size)]
        soundPool.play(id, 0.6f, 0.6f, 1, 0, 1f)
    }

    private fun playElim() {
        soundPool.play(elimSoundId, 0.6f, 0.6f, 1, 0, 1f)
    }

    private fun playWin() {
        soundPool.play(winSoundId, 0.8f, 0.8f, 1, 0, 1f)
    }

    // ==========================================
    // 触控处理
    // ==========================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::engine.isInitialized) return true
        val now = SystemClock.uptimeMillis()
        val actionIndex = event.actionIndex

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val id = event.getPointerId(actionIndex)
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                // 等待界面按下即开始记录轨迹（画小三角手势）
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    engine.phase == GameEngine.Phase.WAITING
                ) {
                    gesturePoints.clear()
                    gesturePoints.add(x to y)
                }
                val pressure = event.getPressure(actionIndex)
                val major = event.getTouchMajor(actionIndex)
                engine.onDown(id, x, y, now, pressure, major)
            }
            MotionEvent.ACTION_MOVE -> {
                // 滑出屏幕边界视为离场，防止触点在屏外挂死（原版社区的常见反馈）
                val margin = 24f * density
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    if (x < -margin || x > width + margin || y < -margin || y > height + margin) {
                        engine.onUp(id, now)
                    } else {
                        val pressure = event.getPressure(i)
                        val major = event.getTouchMajor(i)
                        engine.onMove(id, x, y, now, pressure, major)
                    }
                }
                // 单指移动时按间距采样轨迹
                if (event.pointerCount == 1 && engine.phase == GameEngine.Phase.WAITING) {
                    val gx = event.getX(0)
                    val gy = event.getY(0)
                    val last = gesturePoints.lastOrNull()
                    if (last == null || hypot(gx - last.first, gy - last.second) > 8f * density) {
                        gesturePoints.add(gx to gy)
                        if (gesturePoints.size > 256) gesturePoints.removeAt(0)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(actionIndex)
                engine.onUp(id, now)
            }
            MotionEvent.ACTION_UP -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                engine.onUp(event.getPointerId(actionIndex), now)
                // 等待界面抬手时判定轨迹是否为小三角
                if (engine.phase == GameEngine.Phase.WAITING && gesturePoints.isNotEmpty()) {
                    gesturePoints.add(x to y)
                    if (isTriangleGesture(gesturePoints)) {
                        val intent = Intent(context, SettingsActivity::class.java).apply {
                            if (context !is Activity) {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                        context.startActivity(intent)
                    }
                    gesturePoints.clear()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                gesturePoints.clear()
                engine.reset()
            }
        }
        // 空闲停刷后由触摸事件唤醒渲染循环
        postInvalidateOnAnimation()
        return true
    }

    // ==========================================
    // 主循环与绘制
    // ==========================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::engine.isInitialized) return
        val now = SystemClock.uptimeMillis()
        engine.tick(now)
        if (engine.phase != GameEngine.Phase.RESULT) elimFiredIds.clear()

        // 背景 fill bg
        canvas.drawColor(bgColor)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            postInvalidateOnAnimation()
            return
        }

        // 触点基础半径：min(w,h)*0.085，人数>5 时再乘 (5f/n)^0.35
        val minDim = minOf(w, h)
        var baseRadius = minDim * 0.085f
        // RESULT 用揭晓瞬间的快照人数，避免手指离场后半径漂移
        val count = if (engine.phase == GameEngine.Phase.RESULT) {
            engine.resultSpots.size
        } else {
            engine.downCount
        }
        if (count > 5) {
            baseRadius *= (5f / count.toFloat()).toDouble().pow(0.35).toFloat()
        }

        val topHintY = 60f * density

        when (engine.phase) {
            GameEngine.Phase.WAITING -> {
                // 等待：触点按下弹入 scale 0→1（150ms ease-out-back，基准 downTime）
                for (p in engine.pointers) {
                    if (!p.isDown) continue
                    val dt = now - p.downTime
                    val scale = if (dt <= 0L) {
                        0f
                    } else if (dt >= 150L) {
                        1f
                    } else {
                        easeOutBack(dt / 150f)
                    }
                    val r = baseRadius * scale
                    val cIndex = p.colorIndex % pointerColors.size
                    drawTouchCircle(
                        canvas, p.x, p.y, r,
                        pointerCenterColors[cIndex], pointerColors[cIndex], 1f
                    )
                }
            }

            GameEngine.Phase.READY -> {
                // 稳定期：触点呼吸
                val breatheScale = 1f + 0.10f * sin(now / 400.0).toFloat()
                val r = baseRadius * breatheScale
                for (p in engine.pointers) {
                    if (!p.isDown) continue
                    val cIndex = p.colorIndex % pointerColors.size
                    drawTouchCircle(
                        canvas, p.x, p.y, r,
                        pointerCenterColors[cIndex], pointerColors[cIndex], 1f
                    )
                }
            }

            GameEngine.Phase.SPIN -> {
                // 读条：触点保持呼吸，外围读条进度弧，读满出结果
                val breatheScale = 1f + 0.10f * sin(now / 400.0).toFloat()
                val r = baseRadius * breatheScale
                val progress = engine.readoutProgress(now)
                val arcGap = 7f * density
                for (p in engine.pointers) {
                    if (!p.isDown) continue
                    val cIndex = p.colorIndex % pointerColors.size
                    drawTouchCircle(
                        canvas, p.x, p.y, r,
                        pointerCenterColors[cIndex], pointerColors[cIndex], 1f
                    )
                    arcPaint.color = pointerColors[cIndex]
                    canvas.drawArc(
                        p.x - r - arcGap, p.y - r - arcGap,
                        p.x + r + arcGap, p.y + r + arcGap,
                        -90f, 360f * progress, false, arcPaint
                    )
                }
            }

            GameEngine.Phase.RESULT -> {
                // 全部基于揭晓瞬间的快照绘制，手指是否仍在屏不影响画面
                val singleWinner = engine.cfg.mode == GameEngine.MODE_WINNERS &&
                        engine.winnerIds.size == 1
                if (singleWinner) {
                    // 单赢家：赢家色从触点向外扩散覆盖全屏并停留，轻点重置
                    // 满屏色 + 白圈大圆已是完整表达，不再叠加文字
                    val spot = engine.resultSpots.first { it.winner }
                    val cIndex = spot.colorIndex % pointerColors.size
                    val spread = ((now - engine.resultAt).toFloat() / GameEngine.SPREAD_MS)
                        .coerceIn(0f, 1f)
                    val coverR = hypot(maxOf(spot.x, w - spot.x), maxOf(spot.y, h - spot.y)) +
                            baseRadius

                    circlePaint.shader = null
                    circlePaint.color = pointerColors[cIndex]
                    circlePaint.alpha = 255
                    canvas.drawCircle(spot.x, spot.y, coverR * easeOutQuad(spread), circlePaint)

                    // 赢家圆点 + 白圈呼吸
                    val breathe = 1f + 0.10f * sin(now / 400.0).toFloat()
                    val wr = baseRadius * 1.25f * breathe
                    drawTouchCircle(
                        canvas, spot.x, spot.y, wr,
                        pointerCenterColors[cIndex], pointerColors[cIndex], 1f
                    )
                    canvas.drawCircle(spot.x, spot.y, wr, winnerRingPaint)
                } else if (engine.cfg.mode == GameEngine.MODE_WINNERS) {
                    // 多赢家：逐个淘汰后剩余高亮
                    val dtResult = maxOf(0L, now - engine.resultAt)
                    val winnerPopProgress = (dtResult / 250f).coerceIn(0f, 1f)
                    val winnerPop = 1f + 0.25f * easeOutBack(winnerPopProgress)
                    val winnerBreathe = 0.10f * sin(now / 400.0).toFloat()
                    val winnerScale = winnerPop + winnerBreathe
                    val winnerRadius = baseRadius * winnerScale

                    for (spot in engine.resultSpots) {
                        if (spot.winner) {
                            val cIndex = spot.colorIndex % pointerColors.size
                            drawTouchCircle(
                                canvas, spot.x, spot.y, winnerRadius,
                                pointerCenterColors[cIndex], pointerColors[cIndex], 1f
                            )
                            canvas.drawCircle(spot.x, spot.y, winnerRadius, winnerRingPaint)
                        } else if (spot.eliminateOffset >= 0) {
                            // 淘汰触点在 resultAt+偏移 起 260ms 内 scale 1→0 且 alpha 1→0
                            val startTime = engine.resultAt + spot.eliminateOffset
                            if (now >= startTime && elimFiredIds.add(spot.id)) {
                                if (soundOn) playElim()
                                if (hapticsOn) vibrate(30L)
                            }
                            if (now < startTime) {
                                val cIndex = spot.colorIndex % pointerColors.size
                                drawTouchCircle(
                                    canvas, spot.x, spot.y, baseRadius,
                                    pointerCenterColors[cIndex], pointerColors[cIndex], 1f
                                )
                            } else if (now < startTime + 260L) {
                                val progress = (now - startTime).toFloat() / 260f
                                val scale = (1f - progress).coerceIn(0f, 1f)
                                val alpha = (1f - progress).coerceIn(0f, 1f)
                                val r = baseRadius * scale
                                val cIndex = spot.colorIndex % pointerColors.size
                                drawTouchCircle(
                                    canvas, spot.x, spot.y, r,
                                    pointerCenterColors[cIndex], pointerColors[cIndex], alpha
                                )
                            }
                        }
                    }
                    canvas.drawText(
                        context.getString(R.string.hint_winner), w / 2f, topHintY, hintPaint
                    )
                } else {
                    // 分队：全员按组号画组色
                    teamNumPaint.textSize = baseRadius * 0.9f
                    val fm = teamNumPaint.fontMetrics
                    val textYOffset = (fm.top + fm.bottom) / 2f

                    for (spot in engine.resultSpots) {
                        val ti = spot.team.coerceIn(0, teamColors.size - 1)
                        drawTouchCircle(
                            canvas, spot.x, spot.y, baseRadius,
                            teamCenterColors[ti], teamColors[ti], 1f
                        )
                        canvas.drawText((ti + 1).toString(), spot.x, spot.y - textYOffset, teamNumPaint)
                    }
                    canvas.drawText(
                        context.getString(R.string.hint_team), w / 2f, topHintY, hintPaint
                    )
                }
            }
        }

        // 空闲停刷：等待且无触点时没有动画可播，停止持续重绘省电
        val idle = engine.phase == GameEngine.Phase.WAITING && engine.pointers.isEmpty()
        if (!idle) postInvalidateOnAnimation()
    }

    // ==========================================
    // 绘图辅助函数
    // ==========================================

    private fun drawTouchCircle(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        centerColor: Int,
        edgeColor: Int,
        alpha: Float,
    ) {
        if (radius <= 0.001f || alpha <= 0.001f) return
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        val actualCenter = applyAlpha(centerColor, clampedAlpha)
        val actualEdge = applyAlpha(edgeColor, clampedAlpha)

        circlePaint.alpha = (clampedAlpha * 255).toInt()
        circlePaint.shader = RadialGradient(
            x, y, radius,
            actualCenter, actualEdge,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, circlePaint)
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun brightenColor(color: Int, amount: Float = 0.08f): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = (hsl[2] + amount).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun easeOutBack(t: Float, overshoot: Float = 1.70158f): Float {
        val x = t - 1f
        return 1f + (overshoot + 1f) * x * x * x + overshoot * x * x
    }

    private fun easeOutQuad(t: Float): Float = t * (2f - t)

    /**
     * 三角形手势判定：轨迹重采样为等弧长方向序列，恰好两次大转向（≥40°）
     * 且接近闭合即认为是画了一个三角。直线、圆（渐进转向）与不闭合的
     * Z/N 形均不满足。
     */
    private fun isTriangleGesture(pts: List<Pair<Float, Float>>): Boolean {
        if (pts.size < 10) return false
        val dirStep = 24f * density
        var perimeter = 0f
        var acc = 0f
        var lx = pts[0].first
        var ly = pts[0].second
        val dirs = ArrayList<Float>()
        for (i in 1 until pts.size) {
            val dx = pts[i].first - lx
            val dy = pts[i].second - ly
            val d = hypot(dx, dy)
            if (d <= 0f) continue
            perimeter += d
            acc += d
            if (acc >= dirStep) {
                dirs.add(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
                acc = 0f
                lx = pts[i].first
                ly = pts[i].second
            }
        }
        if (dirs.size < 4) return false
        // 起终点接近闭合
        val closeDist = hypot(
            pts.last().first - pts.first().first,
            pts.last().second - pts.first().second
        )
        if (closeDist > perimeter * 0.45f) return false
        // 恰好两次大转向（同一拐点不重复计数）
        var corners = 0
        var lastCorner = -3
        for (i in 1 until dirs.size) {
            var diff = dirs[i] - dirs[i - 1]
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            if (abs(diff) > 40f && i - lastCorner >= 2) {
                corners++
                lastCorner = i
            }
        }
        return corners == 2
    }

    companion object {
        /** 重力一阶低通系数：50Hz 采样下时间常数约 130ms，保留半秒级倾斜信号 */
        private const val LPF_ALPHA = 0.15f
    }
}
