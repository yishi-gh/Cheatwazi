package com.cheatwazi.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.ToggleButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import kotlin.math.asin
import kotlin.math.hypot

class SettingsActivity : AppCompatActivity() {

    // —— 游戏节控件 ——
    private lateinit var tvModeWinners: TextView
    private lateinit var tvModeTeams: TextView
    private var selectedMode: Int = GameEngine.MODE_WINNERS
    private lateinit var tvModeHint: TextView
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
    private lateinit var rgChannel: RadioGroup
    private lateinit var rbPressure: AppCompatRadioButton
    private lateinit var rbTilt: AppCompatRadioButton
    private lateinit var rbLift: AppCompatRadioButton
    private lateinit var rbOrdinal: AppCompatRadioButton
    private lateinit var tvChannelSub: TextView
    private lateinit var tvSensitivityLabel: TextView
    private lateinit var sbSensitivity: SeekBar
    private lateinit var tvThresholds: TextView
    private val ordinalChips = ArrayList<ToggleButton>()

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

    // —— 构建页面 UI（卡片分组 + 青色点缀） ——
    private fun buildUi() {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.bg))
        }
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        scrollView.addView(rootLayout)

        // 头部：小字品牌 + 大标题
        rootLayout.addView(TextView(this).apply {
            text = "C H E A T W A Z I"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.p2))
            typeface = Typeface.DEFAULT_BOLD
        })
        rootLayout.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(26) }
            setText(R.string.settings_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })

        // —— 「游戏」节 ——
        sectionLabel(rootLayout, R.string.game_section)
        val gameCard = card()
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        tvModeWinners = modeSegment(R.string.mode_winners)
        tvModeTeams = modeSegment(R.string.mode_teams).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        modeRow.addView(tvModeWinners)
        modeRow.addView(tvModeTeams)
        gameCard.addView(modeRow)
        tvModeHint = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
        }
        gameCard.addView(tvModeHint)
        cardDivider(gameCard)
        // 赢家数量 (1..8)
        winnerCountContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        tvWinnerCountLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xFFFFFFFF.toInt())
        }
        sbWinnerCount = SeekBar(this).apply {
            max = 7 // 0..7 -> 1..8（赢家数量上限 8）
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        tintSeek(sbWinnerCount)
        winnerCountContainer.addView(tvWinnerCountLabel)
        winnerCountContainer.addView(sbWinnerCount)
        gameCard.addView(winnerCountContainer)
        cardDivider(gameCard)
        // 队伍数量 (2..5)
        teamCountContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
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
            ).apply { topMargin = dp(8) }
        }
        tintSeek(sbTeamCount)
        teamCountContainer.addView(tvTeamCountLabel)
        teamCountContainer.addView(sbTeamCount)
        gameCard.addView(teamCountContainer)
        rootLayout.addView(gameCard)

        // —— 「设备自检」节 ——
        sectionLabel(rootLayout, R.string.selftest_label)
        val selfCard = card()
        selfCard.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setText(R.string.selftest_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
        })
        val selftestBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120)
            ).apply { bottomMargin = dp(12) }
            background = GradientDrawable().apply {
                setColor(0x14FFFFFF)
                cornerRadius = dp(16).toFloat()
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
        selfCard.addView(selftestBox)
        val statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        selfCard.addView(statusContainer)
        rootLayout.addView(selfCard)

        // —— 「作弊引擎」节 ——
        sectionLabel(rootLayout, R.string.cheat_section)
        val cheatCard = card()
        swCheatEnabled = SwitchCompat(this)
        tintSwitch(swCheatEnabled)
        tvCheatSub = TextView(this)
        cheatCard.addView(
            createChannelRow(getString(R.string.cheat_enabled_label), tvCheatSub, swCheatEnabled)
        )
        cardDivider(cheatCard)

        // 通道区容器：单通道互斥，四选一
        cheatChannelsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rgChannel = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rbPressure = AppCompatRadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.channel_pressure)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        rbTilt = AppCompatRadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.channel_tilt)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        rbLift = AppCompatRadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.channel_lift)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        rbOrdinal = AppCompatRadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.channel_ordinal)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        listOf(rbPressure, rbTilt, rbLift, rbOrdinal).forEach { rb ->
            tintRadio(rb)
            rgChannel.addView(rb)
        }
        cheatChannelsContainer.addView(rgChannel)
        // 选中通道的用法说明（随选择联动）
        tvChannelSub = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.hint_text))
            setPadding(0, dp(6), 0, dp(4))
        }
        cheatChannelsContainer.addView(tvChannelSub)
        cheatCard.addView(cheatChannelsContainer)
        cardDivider(cheatCard)

        // 灵敏度 SeekBar
        val sensitivityContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
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
            ).apply { topMargin = dp(8) }
        }
        tintSeek(sbSensitivity)
        sensitivityContainer.addView(tvSensitivityLabel)
        sensitivityContainer.addView(sbSensitivity)
        // 当前灵敏度下通道的触发阈值（随灵敏度实时刷新）
        tvThresholds = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
            setPadding(0, dp(6), 0, 0)
        }
        sensitivityContainer.addView(tvThresholds)
        refreshThresholdLabels()
        cheatCard.addView(sensitivityContainer)
        rootLayout.addView(cheatCard)

        // —— 「序号内定（一次性）」节 ——
        sectionLabel(rootLayout, R.string.ordinal_section)
        val ordCard = card()
        ordCard.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setText(R.string.ordinal_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
        })
        var chipIndex = 0
        for (row in 0 until 2) {
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            repeat(4) {
                val n = ++chipIndex
                val chip = ToggleButton(this).apply {
                    textOn = n.toString()
                    textOff = n.toString()
                    text = n.toString()
                    layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        marginEnd = if (n % 4 == 0) 0 else dp(8)
                    }
                    // 选中态：青色实底 + 白字；未选中：暗底 + 灰字，一眼可辨
                    background = StateListDrawable().apply {
                        addState(
                            intArrayOf(android.R.attr.state_checked),
                            GradientDrawable().apply {
                                setColor(0xFF0491B3.toInt())
                                cornerRadius = dp(12).toFloat()
                            }
                        )
                        addState(
                            IntArray(0),
                            GradientDrawable().apply {
                                setColor(0x1FFFFFFF)
                                cornerRadius = dp(12).toFloat()
                            }
                        )
                    }
                    setTextColor(
                        ColorStateList(
                            arrayOf(
                                intArrayOf(android.R.attr.state_checked),
                                IntArray(0)
                            ),
                            intArrayOf(0xFFFFFFFF.toInt(), 0xFF9AA6A6.toInt())
                        )
                    )
                }
                chip.setOnCheckedChangeListener { _, _ ->
                    if (isInitialized) saveCurrentPrefs()
                }
                ordinalChips.add(chip)
                chipRow.addView(chip)
            }
            ordCard.addView(chipRow)
        }
        rootLayout.addView(ordCard)

        // —— 「反馈」节 ——
        sectionLabel(rootLayout, R.string.feedback_section)
        val fbCard = card()
        swHaptics = SwitchCompat(this)
        tintSwitch(swHaptics)
        fbCard.addView(createChannelRow(getString(R.string.haptics_label), null, swHaptics))
        cardDivider(fbCard)
        swSound = SwitchCompat(this)
        tintSwitch(swSound)
        fbCard.addView(createChannelRow(getString(R.string.sound_label), null, swSound))
        rootLayout.addView(fbCard)

        // —— 「使用说明」节 ——
        sectionLabel(rootLayout, R.string.howto_title)
        val howCard = card()
        howCard.addView(TextView(this).apply {
            setText(R.string.howto_body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
            setLineSpacing(dp(3).toFloat(), 1.15f)
        })
        rootLayout.addView(howCard)

        // GitHub 行 + 版权（卡片外）
        val tvGithub = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(22)
            }
            text = "github.com/yishi-gh/Cheatwazi"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF4D8DFF.toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_github, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            gravity = Gravity.CENTER_VERTICAL
        }
        tvGithub.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yishi-gh/Cheatwazi"))
                )
            }
        }
        rootLayout.addView(tvGithub)
        rootLayout.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setText(R.string.copyright_note)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(ContextCompat.getColor(context, R.color.hint_text))
        })

        setContentView(scrollView)
    }

    // —— UI 构建辅助 ——
    /** 卡片外节标题：小号灰色粗体，带字距 */
    private fun sectionLabel(parent: LinearLayout, resId: Int) {
        parent.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(10) }
            setText(resId)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.hint_text))
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
    }

    /** 圆角分组卡片 */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFF121417.toInt())
            cornerRadius = dp(16).toFloat()
        }
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }

    /** 卡片内细分隔线 */
    private fun cardDivider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
            setBackgroundColor(0x14FFFFFF.toInt())
        })
    }

    /** 模式分段按钮：选中青底白字，未选暗底灰字 */
    private fun modeSegment(textRes: Int): TextView = TextView(this).apply {
        id = View.generateViewId()
        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
        setText(textRes)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun updateModeSegment() {
        val selBg = GradientDrawable().apply {
            setColor(0xFF0491B3.toInt())
            cornerRadius = dp(12).toFloat()
        }
        val unBg = GradientDrawable().apply {
            setColor(0x1AFFFFFF.toInt())
            cornerRadius = dp(12).toFloat()
        }
        val winners = selectedMode == GameEngine.MODE_WINNERS
        tvModeWinners.background = if (winners) selBg else unBg
        tvModeWinners.setTextColor(if (winners) 0xFFFFFFFF.toInt() else 0xFF9AA6A6.toInt())
        tvModeTeams.background = if (winners) unBg else selBg
        tvModeTeams.setTextColor(if (winners) 0xFF9AA6A6.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun tintSwitch(sw: SwitchCompat) {
        sw.thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(0xFFE6E9E9.toInt(), 0xFFFFFFFF.toInt())
        )
        sw.trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(0x2EFFFFFF, 0xE60491B3.toInt())
        )
    }

    private fun tintRadio(rb: AppCompatRadioButton) {
        rb.buttonTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            ),
            intArrayOf(0x55FFFFFF, 0xFF0491B3.toInt())
        )
    }

    private fun tintSeek(sb: SeekBar) {
        val c = ColorStateList.valueOf(0xFF0491B3.toInt())
        sb.progressTintList = c
        sb.thumbTintList = c
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

        selectedMode = s.mode
        updateModeSegment()
        updateModeVisibility(s.mode)

        sbWinnerCount.progress = (s.winnerCount - 1).coerceIn(0, 7)
        tvWinnerCountLabel.text = getString(R.string.winner_count_label, s.winnerCount)

        sbTeamCount.progress = (s.teamCount - 2).coerceIn(0, 3)
        tvTeamCountLabel.text = getString(R.string.team_count_label, s.teamCount)

        ordinalChips.forEachIndexed { i, chip ->
            chip.isChecked = (i + 1) in s.ordinalTargets
        }

        swHaptics.isChecked = s.hapticsOn
        swSound.isChecked = s.soundOn

        val channelOn = s.cheatChannel != CheatEngine.CHANNEL_OFF
        swCheatEnabled.isChecked = channelOn
        when (s.cheatChannel) {
            CheatEngine.CHANNEL_PRESSURE -> rbPressure.isChecked = true
            CheatEngine.CHANNEL_TILT -> rbTilt.isChecked = true
            CheatEngine.CHANNEL_ORDINAL -> rbOrdinal.isChecked = true
            else -> rbLift.isChecked = true
        }
        updateCheatSectionState(channelOn)
        updateChannelUi()

        sbSensitivity.progress = s.sensitivity.coerceIn(0, 2)
        tvSensitivityLabel.text = getString(R.string.sensitivity_label, getSensitivityName(s.sensitivity))
    }

    // —— 绑定控件事件监听 ——
    private fun bindListeners() {
        tvModeWinners.setOnClickListener { setMode(GameEngine.MODE_WINNERS) }
        tvModeTeams.setOnClickListener { setMode(GameEngine.MODE_TEAMS) }

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

        // 单通道互斥：切换选择即保存并联动用法说明与序号块可用性
        rgChannel.setOnCheckedChangeListener { _, _ ->
            updateChannelUi()
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
        val selectedChannel = when {
            rbPressure.isChecked -> CheatEngine.CHANNEL_PRESSURE
            rbTilt.isChecked -> CheatEngine.CHANNEL_TILT
            rbLift.isChecked -> CheatEngine.CHANNEL_LIFT
            rbOrdinal.isChecked -> CheatEngine.CHANNEL_ORDINAL
            else -> CheatEngine.CHANNEL_LIFT
        }
        val snapshot = Prefs.Snapshot(
            mode = selectedMode,
            winnerCount = sbWinnerCount.progress + 1,
            teamCount = sbTeamCount.progress + 2,
            hapticsOn = swHaptics.isChecked,
            soundOn = swSound.isChecked,
            cheatChannel = if (swCheatEnabled.isChecked) selectedChannel else CheatEngine.CHANNEL_OFF,
            sensitivity = sbSensitivity.progress,
            ordinalTargets = ordinalChips
                .mapIndexed { i, chip -> if (chip.isChecked) i + 1 else null }
                .filterNotNull()
                .toSet(),
        )
        Prefs.save(this, snapshot)
    }

    // —— 联动与辅助方法 ——
    /** 按硬件检测结果联动单选通道的可用性（压力/倾斜有硬件前提） */
    private fun applyChannelAvailability() {
        val tiltOk = tiltSensor != null
        rbTilt.isEnabled = tiltOk
        rbTilt.text = getString(R.string.channel_tilt) +
                if (tiltOk) "" else "（本机无加速度计，不可用）"
        rbTilt.setTextColor(
            if (tiltOk) 0xFFFFFFFF.toInt() else 0xFFFF5252.toInt()
        )
        if (!tiltOk && rbTilt.isChecked) rbLift.isChecked = true

        rbPressure.isEnabled = pressureSupport != Prefs.PRESSURE_DEAD
        rbPressure.text = getString(R.string.channel_pressure) +
                if (pressureSupport == Prefs.PRESSURE_DEAD) "（本机压力数值恒定，不可用）" else ""
        rbPressure.setTextColor(
            if (pressureSupport == Prefs.PRESSURE_DEAD) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt()
        )
        if (pressureSupport == Prefs.PRESSURE_DEAD && rbPressure.isChecked) rbLift.isChecked = true
        updateChannelUi()
    }

    /** 选中通道的用法说明与序号勾选块可用性 */
    private fun updateChannelUi() {
        tvChannelSub.text = when {
            rbPressure.isChecked -> "读条期间，用力按压屏幕约半秒；多人重按则按力度取前 N 名"
            rbTilt.isChecked -> "读条期间，把手机朝获胜者那侧压低半秒"
            rbLift.isChecked -> "读条期间，手指快速抬起再按回；多人可同时各自指定自己"
            rbOrdinal.isChecked -> "勾选后，下一局第 N 个放手指的人赢，用一次自动失效"
            else -> ""
        }
        tvChannelSub.setTextColor(ContextCompat.getColor(this, R.color.hint_text))
        val ordinalActive = swCheatEnabled.isChecked && rbOrdinal.isChecked
        ordinalChips.forEach { chip ->
            chip.isEnabled = ordinalActive
            chip.alpha = if (ordinalActive) 1.0f else 0.4f
        }
    }

    /** 作弊总开关：压力通道未完成自检前禁用 */
    private fun applyCheatMasterSwitch() {
        val ready = pressureSupport != Prefs.PRESSURE_UNKNOWN
        swCheatEnabled.isEnabled = ready
        if (!ready) swCheatEnabled.isChecked = false
        tvCheatSub.text = if (ready) "开启后选择一条作弊通道，多通道不叠加"
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
            tvModeHint.text = getString(R.string.mode_hint_winners)
        } else {
            winnerCountContainer.visibility = View.GONE
            teamCountContainer.visibility = View.VISIBLE
            tvModeHint.text = getString(R.string.mode_hint_teams)
        }
    }

    /** 切换游戏模式：分段按钮样式联动 */
    private fun setMode(mode: Int) {
        if (selectedMode == mode) return
        selectedMode = mode
        updateModeSegment()
        updateModeVisibility(mode)
        if (isInitialized) saveCurrentPrefs()
    }

    private fun updateCheatSectionState(enabled: Boolean) {
        cheatChannelsContainer.alpha = if (enabled) 1.0f else 0.4f
        setViewGroupEnabled(cheatChannelsContainer, enabled)
        // 总开关打开时，仍要保持硬件不可用通道的禁用态
        if (enabled) applyChannelAvailability() else updateChannelUi()
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
