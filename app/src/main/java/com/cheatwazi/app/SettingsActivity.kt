package com.cheatwazi.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import kotlin.math.asin
import kotlin.math.hypot

class SettingsActivity : AppCompatActivity() {

    // —— 游戏节控件 ——
    private lateinit var rgMode: RadioGroup
    private lateinit var rbWinners: AppCompatRadioButton
    private lateinit var rbTeams: AppCompatRadioButton
    private lateinit var winnerCountContainer: LinearLayout
    private lateinit var tvWinnerCountLabel: TextView
    private lateinit var sbWinnerCount: SeekBar
    private lateinit var teamCountContainer: LinearLayout
    private lateinit var tvTeamCountLabel: TextView
    private lateinit var sbTeamCount: SeekBar
    private lateinit var swHaptics: SwitchCompat
    private lateinit var swSound: SwitchCompat

    // —— 作弊引擎节控件 ——
    private lateinit var swCheatEnabled: SwitchCompat
    private lateinit var tvCheatSub: TextView
    private lateinit var cheatChannelsContainer: LinearLayout
    private lateinit var swPressure: SwitchCompat
    private lateinit var tvPressureSub: TextView
    private lateinit var swTilt: SwitchCompat
    private lateinit var tvTiltSub: TextView
    private lateinit var swLift: SwitchCompat
    private lateinit var tvLiftSub: TextView
    private lateinit var tvSensitivityLabel: TextView
    private lateinit var sbSensitivity: SeekBar
    private lateinit var tvThresholds: TextView

    /** 压力通道硬件支持：0 未检测 1 可用 2 不可用（自检采样写入并持久化） */
    private var pressureSupport = Prefs.PRESSURE_UNKNOWN

    // —— 设备自检节控件与状态 ——
    private lateinit var tvReadout: TextView
    private lateinit var tvPressureStatus: TextView
    private lateinit var tvTiltStatus: TextView

    private var sensorManager: SensorManager? = null
    private var tiltSensor: Sensor? = null
    private var currentTiltDeg = 0f
    private var isTouchingSelftest = false
    private var lastPressure = 0f
    private var lastMajor = 0f

    private var sampleCount = 0
    private var minPressure = Float.MAX_VALUE
    private var maxPressure = 0f
    private var minMajor = Float.MAX_VALUE
    private var maxMajor = 0f

    // 状态标记：初始化完成后才允许触发 saveCurrentPrefs
    private var isInitialized = false

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val gx = event.values[0]
            val gy = event.values[1]
            // 重力屏幕平面（xy 平面）投影幅值
            val proj = hypot(gx.toDouble(), gy.toDouble())
            val ratio = (proj / 9.8).coerceIn(0.0, 1.0)
            val deg = Math.toDegrees(asin(ratio)).toFloat()

            runOnUiThread {
                currentTiltDeg = deg
                if (isTouchingSelftest) {
                    tvReadout.text = getString(
                        R.string.selftest_readout,
                        lastPressure,
                        lastMajor,
                        currentTiltDeg
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 返回键直接 finish
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        initSensors()
        buildUi()
        loadInitialPrefs()
        bindListeners()

        isInitialized = true
        // 初始化完成后按硬件检测结果修正开关状态（禁用时的关闭会触发持久化）
        pressureSupport = Prefs.pressureSupport(this)
        applyChannelAvailability()
        applyCheatMasterSwitch()
    }

    override fun onResume() {
        super.onResume()
        tiltSensor?.let { sensor ->
            sensorManager?.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(sensorListener)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    // —— 传感器初始化 ——
    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        tiltSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    // —— 构建页面 UI ——
    private fun buildUi() {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }
        scrollView.addView(rootLayout)

        // 1. 标题 settings_title（20sp 粗体白）
        val tvTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
            setText(R.string.settings_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ContextCompat.getColor(context, R.color.winner_ring))
            typeface = Typeface.DEFAULT_BOLD
        }
        rootLayout.addView(tvTitle)

        // 2. 「游戏」节
        rootLayout.addView(createSectionTitle(R.string.game_section))

        // 模式单选 RadioGroup
        rgMode = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        rbWinners = AppCompatRadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.mode_winners)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rbTeams = AppCompatRadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.mode_teams)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rgMode.addView(rbWinners)
        rgMode.addView(rbTeams)
        rootLayout.addView(rgMode)

        // 赢家数量 (1..10)
        winnerCountContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        tvWinnerCountLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFFFFFFF.toInt())
        }
        sbWinnerCount = SeekBar(this).apply {
            max = 9 // 0..9 -> 1..10
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        winnerCountContainer.addView(tvWinnerCountLabel)
        winnerCountContainer.addView(sbWinnerCount)
        rootLayout.addView(winnerCountContainer)

        // 队伍数量 (2..5)
        teamCountContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        }
        tvTeamCountLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFFFFFFF.toInt())
        }
        sbTeamCount = SeekBar(this).apply {
            max = 3 // 0..3 -> 2..5
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        teamCountContainer.addView(tvTeamCountLabel)
        teamCountContainer.addView(sbTeamCount)
        rootLayout.addView(teamCountContainer)

        // 震动反馈 Switch
        swHaptics = SwitchCompat(this)
        rootLayout.addView(createChannelRow(getString(R.string.haptics_label), null, swHaptics))

        // 音效 Switch
        swSound = SwitchCompat(this)
        rootLayout.addView(createChannelRow(getString(R.string.sound_label), null, swSound))

        // 分隔线
        rootLayout.addView(createDivider())

        // 3. 「设备自检」节（前置：未完成检测不能启用作弊）
        rootLayout.addView(createSectionTitle(R.string.selftest_label))

        // 说明行 selftest_hint
        val tvSelftestHint = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
            setText(R.string.selftest_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
        }
        rootLayout.addView(tvSelftestHint)

        // 120dp 高自检触摸区（背景 #14FFFFFF 圆角 12dp）
        val selftestBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            ).apply {
                bottomMargin = dp(10)
            }
            background = GradientDrawable().apply {
                setColor(0x14FFFFFF)
                cornerRadius = dp(12).toFloat()
            }
        }

        tvReadout = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            gravity = Gravity.CENTER
            text = getString(R.string.selftest_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
        }
        selftestBox.addView(tvReadout)

        selftestBox.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    isTouchingSelftest = true
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    lastPressure = event.pressure
                    lastMajor = event.touchMajor
                    updateSampling(lastPressure, lastMajor)
                    tvReadout.text = getString(
                        R.string.selftest_readout,
                        lastPressure,
                        lastMajor,
                        currentTiltDeg
                    )
                    tvReadout.setTextColor(0xFFFFFFFF.toInt())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isTouchingSelftest = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> false
            }
        }
        rootLayout.addView(selftestBox)

        // 状态说明区（压力通道与姿态通道）
        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
        }
        tvPressureStatus = TextView(this).apply {
            text = "压力通道：待采样"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
            setPadding(0, dp(2), 0, dp(2))
        }
        tvTiltStatus = TextView(this).apply {
            text = if (tiltSensor == null) {
                getString(R.string.selftest_tilt_dead)
            } else {
                getString(R.string.selftest_tilt_ok)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(
                if (tiltSensor == null) 0xFFFF5252.toInt()
                else ContextCompat.getColor(context, R.color.hint_text)
            )
            setPadding(0, dp(2), 0, dp(2))
        }
        statusContainer.addView(tvPressureStatus)
        statusContainer.addView(tvTiltStatus)

        // 权限说明：本应用无运行时权限，震动为 normal 级安装即授予
        val tvPermStatus = TextView(this).apply {
            text = "所需权限：无（震动为系统安装时自动授予，传感器免权限）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
            setPadding(0, dp(2), 0, dp(2))
        }
        statusContainer.addView(tvPermStatus)
        rootLayout.addView(statusContainer)

        // 分隔线
        rootLayout.addView(createDivider())

        // 4. 「作弊引擎」节
        rootLayout.addView(createSectionTitle(R.string.cheat_section))

        // 总开关（未完成自检时禁用，副标题联动）
        swCheatEnabled = SwitchCompat(this)
        tvCheatSub = TextView(this)
        rootLayout.addView(
            createChannelRow(getString(R.string.cheat_enabled_label), tvCheatSub, swCheatEnabled)
        )

        // 通道区容器
        cheatChannelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 压力通道（副标题由硬件检测结果联动）
        swPressure = SwitchCompat(this)
        tvPressureSub = TextView(this)
        cheatChannelsContainer.addView(
            createChannelRow(getString(R.string.channel_pressure), tvPressureSub, swPressure)
        )

        // 姿态通道
        swTilt = SwitchCompat(this)
        tvTiltSub = TextView(this)
        cheatChannelsContainer.addView(
            createChannelRow(getString(R.string.channel_tilt), tvTiltSub, swTilt)
        )

        // 微抬通道（纯软件逻辑，恒可用）
        swLift = SwitchCompat(this)
        tvLiftSub = TextView(this)
        cheatChannelsContainer.addView(
            createChannelRow(getString(R.string.channel_lift), tvLiftSub, swLift)
        )

        // 灵敏度 SeekBar
        val sensitivityContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
        }
        tvSensitivityLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFFFFFFF.toInt())
        }
        sbSensitivity = SeekBar(this).apply {
            max = 2 // 0 隐蔽, 1 标准, 2 灵敏
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        sensitivityContainer.addView(tvSensitivityLabel)
        sensitivityContainer.addView(sbSensitivity)

        // 当前灵敏度下三通道的触发阈值（随灵敏度实时刷新）
        tvThresholds = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
            setPadding(0, dp(6), 0, 0)
        }
        sensitivityContainer.addView(tvThresholds)
        refreshThresholdLabels()

        cheatChannelsContainer.addView(sensitivityContainer)

        rootLayout.addView(cheatChannelsContainer)

        // 分隔线
        rootLayout.addView(createDivider())

        // 5. 「使用说明」节
        rootLayout.addView(createSectionTitle(R.string.howto_title))

        val tvHowto = TextView(this).apply {
            setText(R.string.howto_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
            setLineSpacing(0f, 1.3f)
        }
        rootLayout.addView(tvHowto)

        // 项目 GitHub 地址（点击打开浏览器）
        val tvGithub = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
            }
            text = "GitHub：github.com/yishi-gh/Cheatwazi"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF4D8DFF.toInt())
        }
        tvGithub.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yishi-gh/Cheatwazi"))
                )
            }
        }
        rootLayout.addView(tvGithub)

        setContentView(scrollView)
    }

    // —— 动态采样判定 ——
    private fun updateSampling(p: Float, m: Float) {
        sampleCount++
        if (p > 0f) {
            if (p < minPressure) minPressure = p
            if (p > maxPressure) maxPressure = p
        }
        if (m > 0f) {
            if (m < minMajor) minMajor = m
            if (m > maxMajor) maxMajor = m
        }

        val ratioP = if (minPressure > 0f && maxPressure > 0f) maxPressure / minPressure else 1f
        val ratioM = if (minMajor > 0f && maxMajor > 0f) maxMajor / minMajor else 1f
        val ratio = maxOf(ratioP, ratioM)

        if (sampleCount < 4 && ratio < 1.15f) {
            tvPressureStatus.text = "压力通道：采样中…"
            tvPressureStatus.setTextColor(ContextCompat.getColor(this, R.color.hint_text))
        } else if (ratio < 1.15f) {
            pressureSupport = Prefs.PRESSURE_DEAD
            Prefs.setPressureSupport(this, pressureSupport)
            tvPressureStatus.setText(R.string.selftest_pressure_dead)
            tvPressureStatus.setTextColor(0xFFFF5252.toInt())
            applyChannelAvailability()
            applyCheatMasterSwitch()
        } else {
            pressureSupport = Prefs.PRESSURE_OK
            Prefs.setPressureSupport(this, pressureSupport)
            tvPressureStatus.setText(R.string.selftest_pressure_ok)
            tvPressureStatus.setTextColor(0xFF4CAF50.toInt())
            applyChannelAvailability()
            applyCheatMasterSwitch()
        }
    }

    // —— 读取初始配置到 UI ——
    private fun loadInitialPrefs() {
        val s = Prefs.load(this)

        if (s.mode == GameEngine.MODE_WINNERS) {
            rbWinners.isChecked = true
        } else {
            rbTeams.isChecked = true
        }
        updateModeVisibility(s.mode)

        sbWinnerCount.progress = (s.winnerCount - 1).coerceIn(0, 9)
        tvWinnerCountLabel.text = getString(R.string.winner_count_label, s.winnerCount)

        sbTeamCount.progress = (s.teamCount - 2).coerceIn(0, 3)
        tvTeamCountLabel.text = getString(R.string.team_count_label, s.teamCount)

        swHaptics.isChecked = s.hapticsOn
        swSound.isChecked = s.soundOn

        swCheatEnabled.isChecked = s.cheatEnabled
        updateCheatSectionState(s.cheatEnabled)

        swPressure.isChecked = s.pressureOn
        swTilt.isChecked = s.tiltOn
        swLift.isChecked = s.liftOn

        sbSensitivity.progress = s.sensitivity.coerceIn(0, 2)
        tvSensitivityLabel.text = getString(R.string.sensitivity_label, getSensitivityName(s.sensitivity))
    }

    // —— 绑定控件事件监听 ——
    private fun bindListeners() {
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == rbWinners.id) GameEngine.MODE_WINNERS else GameEngine.MODE_TEAMS
            updateModeVisibility(mode)
            if (isInitialized) saveCurrentPrefs()
        }

        sbWinnerCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val count = progress + 1
                tvWinnerCountLabel.text = getString(R.string.winner_count_label, count)
                if (fromUser && isInitialized) saveCurrentPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbTeamCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val count = progress + 2
                tvTeamCountLabel.text = getString(R.string.team_count_label, count)
                if (fromUser && isInitialized) saveCurrentPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        swHaptics.setOnCheckedChangeListener { _, _ ->
            if (isInitialized) saveCurrentPrefs()
        }

        swSound.setOnCheckedChangeListener { _, _ ->
            if (isInitialized) saveCurrentPrefs()
        }

        swCheatEnabled.setOnCheckedChangeListener { _, isChecked ->
            updateCheatSectionState(isChecked)
            if (isInitialized) saveCurrentPrefs()
        }

        swPressure.setOnCheckedChangeListener { _, _ ->
            if (isInitialized) saveCurrentPrefs()
        }

        swTilt.setOnCheckedChangeListener { _, _ ->
            if (isInitialized) saveCurrentPrefs()
        }

        swLift.setOnCheckedChangeListener { _, _ ->
            if (isInitialized) saveCurrentPrefs()
        }

        sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSensitivityLabel.text = getString(R.string.sensitivity_label, getSensitivityName(progress))
                refreshThresholdLabels()
                if (fromUser && isInitialized) saveCurrentPrefs()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // —— 保存配置 ——
    private fun saveCurrentPrefs() {
        val snapshot = Prefs.Snapshot(
            mode = if (rbWinners.isChecked) GameEngine.MODE_WINNERS else GameEngine.MODE_TEAMS,
            winnerCount = sbWinnerCount.progress + 1,
            teamCount = sbTeamCount.progress + 2,
            hapticsOn = swHaptics.isChecked,
            soundOn = swSound.isChecked,
            cheatEnabled = swCheatEnabled.isChecked,
            pressureOn = swPressure.isChecked,
            tiltOn = swTilt.isChecked,
            liftOn = swLift.isChecked,
            sensitivity = sbSensitivity.progress,
        )
        Prefs.save(this, snapshot)
    }

    // —— 联动与辅助方法 ——
    /** 按硬件检测结果联动三通道开关的可用性与说明文案 */
    private fun applyChannelAvailability() {
        // 姿态通道：加速度计实时判定，缺失则禁用
        val tiltOk = tiltSensor != null
        swTilt.isEnabled = tiltOk
        if (!tiltOk) swTilt.isChecked = false
        tvTiltSub.text = if (tiltOk) "读条期间，把手机朝内定赢家那侧压低并保持半秒"
        else "本机无加速度计，此通道不可用"
        tvTiltSub.setTextColor(
            if (tiltOk) ContextCompat.getColor(this, R.color.hint_text) else 0xFFFF5252.toInt()
        )

        // 压力通道：以最近一次自检采样结果为准，不可用则禁用并强制关闭
        when (pressureSupport) {
            Prefs.PRESSURE_DEAD -> {
                swPressure.isEnabled = false
                swPressure.isChecked = false
                tvPressureSub.text = "本机压力数值恒定，此通道不可用"
                tvPressureSub.setTextColor(0xFFFF5252.toInt())
            }
            Prefs.PRESSURE_OK -> {
                swPressure.isEnabled = true
                tvPressureSub.text = "读条期间，用力按压屏幕约半秒"
                tvPressureSub.setTextColor(ContextCompat.getColor(this, R.color.hint_text))
            }
            else -> {
                swPressure.isEnabled = true
                tvPressureSub.text = "读条期间，用力按压屏幕约半秒（先完成上方设备自检）"
                tvPressureSub.setTextColor(ContextCompat.getColor(this, R.color.hint_text))
            }
        }
        // 微抬通道：纯软件逻辑，恒可用
        tvLiftSub.text = "读条期间，手指快速抬起并立即按回原处"
        tvLiftSub.setTextColor(ContextCompat.getColor(this, R.color.hint_text))
    }

    /** 作弊总开关：压力通道未完成自检前禁用 */
    private fun applyCheatMasterSwitch() {
        val ready = pressureSupport != Prefs.PRESSURE_UNKNOWN
        swCheatEnabled.isEnabled = ready
        if (!ready) swCheatEnabled.isChecked = false
        tvCheatSub.text = if (ready) "三条通道按自检结果自动限用"
        else "请先完成上方设备自检"
        tvCheatSub.setTextColor(
            if (ready) ContextCompat.getColor(this, R.color.hint_text) else 0xFFFF5252.toInt()
        )
    }

    /** 按当前灵敏度档位刷新三通道触发阈值说明 */
    private fun refreshThresholdLabels() {
        val s = sbSensitivity.progress.coerceIn(0, 2)
        val pressurePct = intArrayOf(155, 132, 118)[s]
        val tiltMs2 = floatArrayOf(2.2f, 1.5f, 0.9f)[s]
        val tiltDeg = Math.toDegrees(asin((tiltMs2 / 9.8).toDouble()))
        val liftMs = longArrayOf(280, 350, 450)[s]
        tvThresholds.text = "压力：增幅≥${pressurePct}%·持续300ms\n" +
                "倾斜：≥${"%.1f".format(tiltDeg)}°·保持500ms\n" +
                "微抬：${liftMs}ms内按回原位±60dp"
    }

    private fun updateModeVisibility(mode: Int) {
        if (mode == GameEngine.MODE_WINNERS) {
            winnerCountContainer.visibility = View.VISIBLE
            teamCountContainer.visibility = View.GONE
        } else {
            winnerCountContainer.visibility = View.GONE
            teamCountContainer.visibility = View.VISIBLE
        }
    }

    private fun updateCheatSectionState(enabled: Boolean) {
        cheatChannelsContainer.alpha = if (enabled) 1.0f else 0.4f
        setViewGroupEnabled(cheatChannelsContainer, enabled)
        // 总开关打开时，仍要保持硬件不可用通道的禁用态
        if (enabled) applyChannelAvailability()
    }

    private fun setViewGroupEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setViewGroupEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    private fun getSensitivityName(level: Int): String {
        return when (level.coerceIn(0, 2)) {
            0 -> getString(R.string.sens_0)
            1 -> getString(R.string.sens_1)
            2 -> getString(R.string.sens_2)
            else -> getString(R.string.sens_1)
        }
    }

    private fun createSectionTitle(resId: Int): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
            setText(resId)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun createDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1)
            ).apply {
                topMargin = dp(20)
                bottomMargin = dp(20)
            }
            setBackgroundColor(0x22FFFFFF)
        }
    }

    /** 通用开关行；副标题 TextView 由调用方持有，便于检测结果实时联动文案与可用性 */
    private fun createChannelRow(
        title: CharSequence,
        subView: TextView?,
        switchView: SwitchCompat
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = dp(12)
            }
        }

        val tvTitle = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFFFFFFF.toInt())
        }
        textContainer.addView(tvTitle)

        if (subView != null) {
            subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            subView.setTextColor(ContextCompat.getColor(this, R.color.hint_text))
            subView.setPadding(0, dp(2), 0, 0)
            textContainer.addView(subView)
        }

        row.addView(textContainer)
        row.addView(switchView)

        row.setOnClickListener {
            if (switchView.isEnabled) switchView.toggle()
        }
        return row
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
