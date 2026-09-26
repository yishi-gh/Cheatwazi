package com.cheatwazi.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
 * 视觉与动画逐项对齐原版 Chwazi（经反编译观察其绘制行为，未复制任何代码/资产）：
 *  - 触点 = 大色盘（0.73R）+ 细缝 + 粗外环带（0.20R），落下时整体从 0.3 过冲弹入、
 *    色环自 0 扫入一圈，之后持续呼吸（±6.25%、约 0.95s 周期、随机相位）；
 *  - 决策 = 浅色弧带沿外环带扫入一圈（人一变动即快速退回露出色环，全员重新画圈）；
 *  - 结果 = 赢家色从屏幕四边向赢家位置合拢，只留约 2 倍圆大的孔露出其呼吸的圆；
 *    输家保持 1.2s 后同时缩小消失；退出时赢家圆收缩到圆心，黑色自圆心向四周扩散；
 *  - 顶栏 = 原版样式的模式下拉（FINGER / FINGERS / GROUPS 胶囊 + 数量圆钮）。
 */
class ChwaziView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    // —— 颜色资源（每色三档：主体 / 描边 / 浅色读条弧） ——
    private val bgColor = ContextCompat.getColor(context, R.color.bg)

    private val pointerColorResIds = intArrayOf(
        R.color.p0, R.color.p1, R.color.p2, R.color.p3, R.color.p4,
        R.color.p5, R.color.p6, R.color.p7, R.color.p8, R.color.p9,
    )
    private val pointerRingResIds = intArrayOf(
        R.color.p0r, R.color.p1r, R.color.p2r, R.color.p3r, R.color.p4r,
        R.color.p5r, R.color.p6r, R.color.p7r, R.color.p8r, R.color.p9r,
    )
    private val pointerClearResIds = intArrayOf(
        R.color.p0c, R.color.p1c, R.color.p2c, R.color.p3c, R.color.p4c,
        R.color.p5c, R.color.p6c, R.color.p7c, R.color.p8c, R.color.p9c,
    )
    private val mainColors = IntArray(pointerColorResIds.size) {
        ContextCompat.getColor(context, pointerColorResIds[it])
    }
    private val ringColors = IntArray(pointerRingResIds.size) {
        ContextCompat.getColor(context, pointerRingResIds[it])
    }
    private val clearColors = IntArray(pointerClearResIds.size) {
        ContextCompat.getColor(context, pointerClearResIds[it])
    }

    private val teamMainResIds = intArrayOf(R.color.t0, R.color.t1, R.color.t2, R.color.t3, R.color.t4)
    private val teamRingResIds = intArrayOf(R.color.t0r, R.color.t1r, R.color.t2r, R.color.t3r, R.color.t4r)
    private val teamClearResIds = intArrayOf(R.color.t0c, R.color.t1c, R.color.t2c, R.color.t3c, R.color.t4c)
    private val teamMainColors = IntArray(teamMainResIds.size) { ContextCompat.getColor(context, teamMainResIds[it]) }
    private val teamRingColors = IntArray(teamRingResIds.size) { ContextCompat.getColor(context, teamRingResIds[it]) }
    private val teamClearColors = IntArray(teamClearResIds.size) { ContextCompat.getColor(context, teamClearResIds[it]) }

    // —— 画笔 ——
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val darkDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // —— 顶栏菜单画笔 ——
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PILL_BG
    }
    private val pillSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PILL_SEL
    }
    private val pillLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MENU_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 15f * scaledDensity
    }
    private val menuNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MENU_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 18f * scaledDensity
    }
    private val menuRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MENU_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 14f * scaledDensity
    }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MENU_TEXT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = 14f * scaledDensity
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

    // —— 成员变动与读条锚点：全员色环重扫（成员变动）/ 读条弧退回+扫入（锚点重置） ——
    private var lastMembership = ""
    private var membershipChangedAt = 0L
    private var lastSpinStart = -1L
    private var loaderResetAt = -1L
    private var loaderPrevValue = 0f

    // —— 顶栏模式下拉 ——
    private var menuOpen = false
    private var panelMode = GameEngine.MODE_WINNERS
    private val pillRect = RectF()
    private val numRect = RectF()
    private val panelRect = RectF()
    private val modeRows = ArrayList<Pair<RectF, Int>>()
    private val chipRects = ArrayList<Pair<RectF, Int>>()

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
        newEngine.ordinalTargets = snapshot.ordinalTargets

        newEngine.events = object : GameEngine.Events {
            override fun onFingerDown(p: Pointer) {
                // 对齐原版：按压不振动，只有音效
                if (snapshot.soundOn) playPop(p.colorIndex)
            }

            override fun onResult() {
                if (snapshot.hapticsOn) vibrateResult()
                if (snapshot.soundOn) playWin()
                // 序号内定一次性：本局已消费则清除持久化设置
                if (engine.ordinalApplied) {
                    Prefs.setOrdinalTargets(context, emptySet())
                    engine.clearOrdinalApplied()
                }
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

    private fun menuVisible(): Boolean =
        ::engine.isInitialized &&
            engine.phase == GameEngine.Phase.WAITING &&
            engine.pointers.isEmpty()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::engine.isInitialized) return true
        val now = SystemClock.uptimeMillis()
        val actionIndex = event.actionIndex

        // —— 顶栏模式下拉：仅等待界面无触点时可交互，打开期间吞掉全部触摸 ——
        if (menuOpen) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                handleMenuTouch(event.getX(actionIndex), event.getY(actionIndex))
            }
            postInvalidateOnAnimation()
            return true
        }
        if (menuVisible() && event.actionMasked == MotionEvent.ACTION_DOWN) {
            val mx = event.getX(actionIndex)
            val my = event.getY(actionIndex)
            if (pillRect.contains(mx, my) || numRect.contains(mx, my)) {
                handleMenuTouch(mx, my)
                postInvalidateOnAnimation()
                return true
            }
        }

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
    // 顶栏模式下拉
    // ==========================================

    private fun handleMenuTouch(x: Float, y: Float) {
        if (!menuOpen) {
            if (pillRect.contains(x, y) || numRect.contains(x, y)) {
                menuOpen = true
                panelMode = engine.cfg.mode
            }
            return
        }
        for ((r, code) in modeRows) {
            if (!r.contains(x, y)) continue
            when (code) {
                ROW_MULTI -> {
                    // FINGERS 统一涵盖 1..8 名赢家，切换模式不改动赢家数量
                    panelMode = GameEngine.MODE_WINNERS
                    applyMenuSelection(GameEngine.MODE_WINNERS)
                }
                else -> {
                    panelMode = GameEngine.MODE_TEAMS
                    applyMenuSelection(GameEngine.MODE_TEAMS)
                }
            }
            return
        }
        for ((r, value) in chipRects) {
            if (!r.contains(x, y)) continue
            if (panelMode == GameEngine.MODE_WINNERS) {
                applyMenuSelection(GameEngine.MODE_WINNERS, winnerCount = value)
            } else {
                applyMenuSelection(GameEngine.MODE_TEAMS, teamCount = value)
            }
            menuOpen = false
            return
        }
        menuOpen = false   // 点在面板外：收起
    }

    private fun applyMenuSelection(mode: Int, winnerCount: Int? = null, teamCount: Int? = null) {
        val current = Prefs.load(context)
        Prefs.save(
            context,
            current.copy(
                mode = mode,
                winnerCount = winnerCount ?: current.winnerCount,
                teamCount = teamCount ?: current.teamCount,
            )
        )
        reloadConfig()
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

        // 触点基础半径：屏短边约 25%（直径），人数>5 时再乘 (5f/n)^0.35
        val minDim = minOf(w, h)
        var baseRadius = minDim * 0.125f
        val count = if (engine.phase == GameEngine.Phase.RESULT) {
            engine.resultSpots.size
        } else {
            engine.downCount
        }
        if (count > 5) {
            baseRadius *= (5f / count.toFloat()).toDouble().pow(0.35).toFloat()
        }

        // 成员变动锚点：色环全员重扫；读条锚点变化（读条重启）触发读条弧退回
        val membershipKey = engine.pointers.joinToString(",") { "${it.id}:${it.isDown}" }
        if (membershipKey != lastMembership) {
            loaderPrevValue = currentLoaderValue(now)
            lastMembership = membershipKey
            membershipChangedAt = now
            loaderResetAt = now
        }
        if (engine.spinStartAt != lastSpinStart) {
            if (loaderResetAt < 0 || now - loaderResetAt > 50) {
                loaderPrevValue = currentLoaderValue(now)
                loaderResetAt = now
            }
            lastSpinStart = engine.spinStartAt
        }

        when (engine.phase) {
            GameEngine.Phase.WAITING, GameEngine.Phase.READY -> {
                drawFingers(canvas, now, baseRadius, withLoader = false)
            }

            GameEngine.Phase.SPIN -> {
                drawFingers(canvas, now, baseRadius, withLoader = true)
            }

            GameEngine.Phase.RESULT -> {
                drawResult(canvas, now, baseRadius, w, h)
            }
        }

        // 顶栏模式下拉：仅等待且无触点时显示（原版同款布局）
        if (menuVisible()) {
            drawTopMenu(canvas)
        }

        // 空闲停刷：等待且无触点时没有动画可播，停止持续重绘省电
        val idle = engine.phase == GameEngine.Phase.WAITING && engine.pointers.isEmpty()
        if (!idle) postInvalidateOnAnimation()
    }

    /** 读条弧当前值 0..1：退回段（锚点重置后 300ms）从上次值回落，之后重新扫入 */
    private fun currentLoaderValue(now: Long): Float {
        if (engine.phase != GameEngine.Phase.SPIN || loaderResetAt < 0) return 0f
        val sinceReset = now - loaderResetAt
        if (sinceReset < LOADER_RECEDE_MS) {
            return loaderPrevValue * (1f - sinceReset.toFloat() / LOADER_RECEDE_MS)
        }
        val linear = ((sinceReset - LOADER_RECEDE_MS).toFloat() / LOADER_SWEEP_MS).coerceIn(0f, 1f)
        return accelDecel(linear)
    }

    /** 在屏触点：各自弹入（按下起 450ms 过冲）+ 色环全员重扫 + 持续呼吸；读条阶段叠加浅色读条弧 */
    private fun drawFingers(canvas: Canvas, now: Long, baseRadius: Float, withLoader: Boolean) {
        val sinceChange = now - membershipChangedAt
        val ringSweep = 360f * accelDecel((sinceChange.toFloat() / SWEEP_MS).coerceIn(0f, 1f))
        val loaderValue = currentLoaderValue(now)

        for (p in engine.pointers) {
            val idx = p.colorIndex % 10
            if (p.isDown) {
                // 弹入按各自按下时刻（对齐原版 per-player grow）；复活按回不重播
                val growT = ((now - p.downTime).toFloat() / GROW_MS).coerceIn(0f, 1f)
                val grow = 0.3f + 0.7f * easeOutBack(growT)
                val r = baseRadius * grow * breath(now, p.id)
                drawFingerCircle(canvas, p.x, p.y, r, mainColors[idx], ringColors[idx], ringSweep)
                if (withLoader) {
                    drawArcBand(canvas, p.x, p.y, r, clearColors[idx], 360f * loaderValue)
                }
            } else {
                // 按回等待窗口内的离场触点：整体缩小消失（对齐原版 dying）
                val dt = now - p.liftTime
                if (dt < DIE_MS) {
                    drawFingerCircle(
                        canvas, p.x, p.y, baseRadius * (1f - dt.toFloat() / DIE_MS),
                        mainColors[idx], ringColors[idx], ringSweep
                    )
                }
            }
        }
    }

    /** 单赢家结果：赢家色自四边合拢只留呼吸的赢家圆；退出时圆缩到圆心、黑色自圆心扩散 */
    private fun drawResult(canvas: Canvas, now: Long, baseRadius: Float, w: Float, h: Float) {
        val singleWinner = engine.cfg.mode == GameEngine.MODE_WINNERS && engine.winnerIds.size == 1
        val exitT = if (engine.exitingAt >= 0) now - engine.exitingAt else -1L

        // —— 底层：结果快照里的各触点圆 ——
        for (spot in engine.resultSpots) {
            val idx = spot.colorIndex % 10
            val main: Int
            val ring: Int
            if (engine.cfg.mode == GameEngine.MODE_TEAMS) {
                val ti = spot.team.coerceIn(0, 4)
                main = teamMainColors[ti]
                ring = teamRingColors[ti]
            } else {
                main = mainColors[idx]
                ring = ringColors[idx]
            }
            val scale: Float = when {
                singleWinner && spot.winner -> 0f          // 赢家圆在挖孔层单独绘制
                engine.cfg.mode == GameEngine.MODE_WINNERS && !spot.winner -> {
                    // 输家：保持 1.2s 后同时缩小消失
                    val dt = now - engine.resultAt - GameEngine.LOSER_DIE_DELAY_MS
                    when {
                        dt < 0f -> 1f
                        dt < DIE_MS -> {
                            if (elimFiredIds.add(spot.id)) playElim()
                            1f - dt / DIE_MS.toFloat()
                        }
                        else -> 0f
                    }
                }
                exitT >= 0 -> (1f - (exitT.toFloat() / DIE_MS).coerceIn(0f, 1f)) // 退出：同时缩小
                else -> 1f
            }
            if (scale > 0f) {
                drawFingerCircle(
                    canvas, spot.x, spot.y, baseRadius * scale * breath(now, spot.id),
                    main, ring, 360f
                )
            }
        }
        if (!singleWinner) return

        // —— 单赢家：反向遮罩 flood（孔在赢家位置，孔外全部为赢家色） ——
        val spot = engine.resultSpots.first { it.winner }
        val idx = spot.colorIndex % 10
        val winnerColor = mainColors[idx]
        val holeFrom = hypot(maxOf(spot.x, w - spot.x), maxOf(spot.y, h - spot.y)) + baseRadius
        val holeTo = baseRadius * HOLE_TO_RATIO

        // 退出动画（全部松手延迟 RESULT_EXIT_DELAY_MS 后触发，此时合拢早已完成）：
        // 赢家圆 300ms 缩到圆心消失 → 黑色 300ms 自圆心向四周扩散
        val shrink = if (exitT >= 0) 1f - (exitT.toFloat() / DIE_MS).coerceIn(0f, 1f) else 1f
        val holeR = if (exitT < 0) {
            val floodT = ((now - engine.resultAt).toFloat() / FLOOD_MS).coerceIn(0f, 1f)
            holeFrom - (holeFrom - holeTo) * decelerate(floodT)
        } else if (exitT < DIE_MS) {
            holeTo
        } else {
            holeTo + (holeFrom - holeTo) *
                decelerate(((exitT - DIE_MS).toFloat() / HOLE_OPEN_MS).coerceIn(0f, 1f))
        }

        canvas.drawColor(winnerColor)
        darkDiscPaint.color = bgColor
        canvas.drawCircle(spot.x, spot.y, holeR, darkDiscPaint)
        // 孔内露出呼吸中的赢家圆；退出时随收缩同步缩小到圆心消失
        if (shrink > 0f) {
            drawFingerCircle(
                canvas, spot.x, spot.y, baseRadius * shrink * breath(now, spot.id),
                mainColors[idx], ringColors[idx], 360f
            )
        }
    }

    /** 原版触点样式：大色盘（0.73R）+ 细缝 + 粗外环带（0.20R），环带可部分扫入 */
    private fun drawFingerCircle(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        mainColor: Int,
        ringColor: Int,
        ringSweepDeg: Float,
    ) {
        if (radius <= 0.001f) return
        circlePaint.color = mainColor
        canvas.drawCircle(x, y, radius * DISC_RATIO, circlePaint)
        drawArcBand(canvas, x, y, radius, ringColor, ringSweepDeg)
    }

    /** 环带弧：外径 R、带宽 0.20R，从 -90° 起扫 ringSweepDeg */
    private fun drawArcBand(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, sweepDeg: Float) {
        if (radius <= 0.001f || sweepDeg <= 0f) return
        ringPaint.color = color
        ringPaint.strokeWidth = radius * RING_WIDTH
        val rr = radius * RING_MID_RATIO
        canvas.drawArc(x - rr, y - rr, x + rr, y + rr, -90f, sweepDeg.coerceAtMost(360f), false, ringPaint)
    }

    /** 呼吸：±6.25%、约 0.95s 周期，相位随触点 id 错开（对齐原版随机相位） */
    private fun breath(now: Long, id: Int): Float =
        1f + sin(now / 152f + id * 2.399963f) / 16f

    private fun drawTopMenu(canvas: Canvas) {
        val w = width.toFloat()
        val pad = dp(16f)
        pillRect.set(pad, pad, pad + dp(140f), pad + dp(45f))
        numRect.set(w - pad - dp(45f), pad, w - pad, pad + dp(45f))

        pillPaint.color = PILL_BG
        canvas.drawRoundRect(pillRect, dp(22.5f), dp(22.5f), pillPaint)
        canvas.drawCircle(numRect.centerX(), numRect.centerY(), dp(22.5f), pillPaint)

        pillLabelPaint.color = MENU_TEXT
        drawCenteredText(
            canvas, pillLabelPaint,
            when {
                engine.cfg.mode == GameEngine.MODE_TEAMS -> "GROUPS"
                else -> "FINGERS"
            },
            pillRect.centerX(), pillRect.centerY()
        )
        menuNumPaint.color = MENU_TEXT
        val shown = if (engine.cfg.mode == GameEngine.MODE_TEAMS) {
            engine.cfg.teamCount
        } else {
            engine.cfg.winnerCount
        }
        drawCenteredText(canvas, menuNumPaint, shown.toString(), numRect.centerX(), numRect.centerY())

        if (!menuOpen) {
            modeRows.clear()
            chipRects.clear()
            return
        }

        // 下拉面板：模式行 + 数量圆片
        modeRows.clear()
        chipRects.clear()
        val rowH = dp(40f)
        val rowGap = dp(4f)
        val values = if (panelMode == GameEngine.MODE_TEAMS) (2..5).toList() else (1..8).toList()
        val chip = dp(36f)
        val chipGap = dp(6f)
        val perRow = 4
        val chipRows = (values.size + perRow - 1) / perRow
        val panelH = dp(10f) + 2 * rowH + rowGap + dp(8f) +
                chipRows * chip + (chipRows - 1) * chipGap + dp(10f)
        panelRect.set(dp(16f), pad + dp(45f) + dp(8f), dp(16f) + dp(176f), 0f)
        panelRect.bottom = panelRect.top + panelH
        pillPaint.color = PILL_BG
        canvas.drawRoundRect(panelRect, dp(12f), dp(12f), pillPaint)

        val rows = listOf(
            "FINGERS" to ROW_MULTI,
            "GROUPS" to ROW_TEAMS,
        )
        var y = panelRect.top + dp(10f)
        for ((text, code) in rows) {
            val r = RectF(panelRect.left + dp(10f), y, panelRect.right - dp(10f), y + rowH)
            val selected = when (code) {
                ROW_MULTI -> engine.cfg.mode == GameEngine.MODE_WINNERS
                else -> engine.cfg.mode == GameEngine.MODE_TEAMS
            }
            if (selected) {
                canvas.drawRoundRect(r, dp(20f), dp(20f), pillSelPaint)
            }
            menuRowPaint.color = MENU_TEXT
            drawCenteredText(canvas, menuRowPaint, text, r.centerX(), r.centerY())
            modeRows.add(r to code)
            y += rowH + rowGap
        }
        y += dp(4f)

        for (i in values.indices) {
            val row = i / perRow
            val col = i % perRow
            val cx = panelRect.left + dp(10f) + chip / 2 + col * (chip + chipGap)
            val cy = y + chip / 2 + row * (chip + chipGap)
            val value = values[i]
            val active = if (panelMode == GameEngine.MODE_WINNERS) {
                engine.cfg.mode == GameEngine.MODE_WINNERS && engine.cfg.winnerCount == value
            } else {
                engine.cfg.mode == GameEngine.MODE_TEAMS && engine.cfg.teamCount == value
            }
            if (active) {
                pillPaint.color = MENU_TEXT
                canvas.drawCircle(cx, cy, chip / 2f, pillPaint)
                chipTextPaint.color = Color.WHITE
            } else {
                pillPaint.color = CHIP_BG
                canvas.drawCircle(cx, cy, chip / 2f, pillPaint)
                chipTextPaint.color = MENU_TEXT
            }
            drawCenteredText(canvas, chipTextPaint, value.toString(), cx, cy)
            chipRects.add(RectF(cx - chip / 2, cy - chip / 2, cx + chip / 2, cy + chip / 2) to value)
        }
    }

    private fun drawCenteredText(canvas: Canvas, paint: Paint, text: String, cx: Float, cy: Float) {
        val fm = paint.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, paint)
    }

    // ==========================================
    // 插值器与辅助
    // ==========================================

    /** 减速：1-(1-t)^2（对应原版 DecelerateInterpolator） */
    private fun decelerate(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x)
    }

    /** 先加速后减速（对应原版 AccelerateDecelerateInterpolator） */
    private fun accelDecel(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).pow(2) / 2f
    }

    /** 过冲弹入（对应原版 OvershootInterpolator） */
    private fun easeOutBack(t: Float, overshoot: Float = 1.70158f): Float {
        val x = t - 1f
        return 1f + (overshoot + 1f) * x * x * x + overshoot * x * x
    }

    private fun dp(value: Float): Float = value * density

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

        // —— 触点几何（相对半径 R 的比例，对齐原版） ——
        private const val DISC_RATIO = 0.66f     // 中心色盘半径（与环带间留 0.14R 缝隙）
        private const val RING_WIDTH = 0.20f     // 外环带宽
        private const val RING_MID_RATIO = 0.90f // 环带中线半径
        private const val HOLE_TO_RATIO = 2.0f   // 结果孔半径（约 2 倍触点外径）

        // —— 动画时长（ms） ——
        private const val GROW_MS = 450L          // 落下弹入（0.3 → 过冲 → 1）
        private const val SWEEP_MS = 1000L        // 色环扫入一圈
        private const val LOADER_RECEDE_MS = 300L // 成员变动后读条弧退回
        private const val LOADER_SWEEP_MS = 1600L // 读条弧扫入一圈（引擎 SPIN_MS = 两者之和）
        private const val DIE_MS = 300L           // 离场/消失收缩
        private const val FLOOD_MS = 300L         // 结果赢家色合拢
        private const val HOLE_OPEN_MS = 300L     // 消失时黑色扩散

        // —— 顶栏菜单 ——
        private const val PILL_BG = 0xFFE9ECEC.toInt()
        private const val PILL_SEL = 0xFFD3DADC.toInt()
        private const val CHIP_BG = 0xFFFDFDFD.toInt()
        private const val MENU_TEXT = 0xFF5F6E6E.toInt()
        private const val ROW_MULTI = 1
        private const val ROW_TEAMS = 2
    }
}
