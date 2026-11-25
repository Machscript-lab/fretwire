package app.fretwire.monitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.log10

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager

    private lateinit var status: TextView
    private lateinit var meter: MeterView
    private lateinit var startBtn: Button
    private lateinit var volumeLabel: TextView
    private lateinit var volume: SeekBar
    private lateinit var mute: Switch
    private lateinit var inputSpinner: Spinner
    private lateinit var outputSpinner: Spinner
    private lateinit var lowLatency: Switch
    private lateinit var floating: Switch

    private var inputKeys = listOf<String>()
    private var outputKeys = listOf<String>()
    private var startAfterOverlayPrompt = false
    private var syncing = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 60)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = loadDevices()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = loadDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Monitor.init(this)
        am = getSystemService(AudioManager::class.java)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        am.registerAudioDeviceCallback(deviceCallback, handler)
        loadDevices()
        syncing = true
        floating.isChecked = Monitor.floating && Settings.canDrawOverlays(this)
        syncing = false
        if (startAfterOverlayPrompt) {
            startAfterOverlayPrompt = false
            MonitorService.send(this, MonitorService.ACTION_START)
        } else {
            MonitorService.send(this, MonitorService.ACTION_SYNC_OVERLAY)
        }
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        am.unregisterAudioDeviceCallback(deviceCallback)
        super.onPause()
    }

    // ---- Start / stop ----

    private fun onStartStop() {
        if (Monitor.active) {
            MonitorService.send(this, MonitorService.ACTION_STOP)
            return
        }
        val needed = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
        } else {
            startWithOverlayCheck()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWithOverlayCheck()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Audio permission needed")
                .setMessage("Fretwire Monitor needs permission to record audio to hear your iRig. It doesn't save or send anything.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startWithOverlayCheck() {
        if (Monitor.floating && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Show over other apps?")
                .setMessage("To keep a small guitar control floating over your other apps, allow \"Display over other apps\" for Fretwire Monitor on the next screen, then come back.")
                .setPositiveButton("Allow") { _, _ ->
                    startAfterOverlayPrompt = true
                    openOverlaySettings()
                }
                .setNegativeButton("Skip") { _, _ ->
                    Monitor.floating = false
                    syncing = true
                    floating.isChecked = false
                    syncing = false
                    MonitorService.send(this, MonitorService.ACTION_START)
                }
                .show()
        } else {
            MonitorService.send(this, MonitorService.ACTION_START)
        }
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    // ---- Devices ----

    private fun loadDevices() {
        val ins = Monitor.inputs(am)
        val usb = Monitor.usbInput(am)
        val inLabels = mutableListOf(
            if (usb != null) "Auto: ${AudioEngine.describe(usb)}" else "Auto: iRig / USB input (not plugged in)"
        )
        val inKeys = mutableListOf(Monitor.AUTO)
        ins.forEach { inLabels += AudioEngine.describe(it); inKeys += Monitor.keyOf(it) }
        inputKeys = inKeys
        setSpinner(inputSpinner, inLabels, inKeys.indexOf(Monitor.inputChoice).coerceAtLeast(0))

        val outs = Monitor.outputs(am)
        val outLabels = mutableListOf("Same as other apps (recommended)")
        val outKeys = mutableListOf(Monitor.SYSTEM)
        outs.forEach { outLabels += AudioEngine.describe(it); outKeys += Monitor.keyOf(it) }
        outputKeys = outKeys
        setSpinner(outputSpinner, outLabels, outKeys.indexOf(Monitor.outputChoice).coerceAtLeast(0))
    }

    private fun setSpinner(s: Spinner, labels: List<String>, selection: Int) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        s.adapter = adapter
        s.setSelection(selection, false)
    }

    // ---- Status ----

    private fun refresh() {
        val e = Monitor.engine
        meter.level = e.level
        meter.muted = e.muted
        meter.invalidate()
        startBtn.text = if (Monitor.active) "STOP" else "START"
        startBtn.background = pill(if (Monitor.active) 0xFFFF5C7A.toInt() else 0xFF3DF2B0.toInt())
        if (mute.isChecked != e.muted) {
            syncing = true
            mute.isChecked = e.muted
            syncing = false
        }

        status.text = when {
            !Monitor.active -> {
                val usb = Monitor.usbInput(am)
                if (usb != null) "Ready: ${AudioEngine.describe(usb)} found. Tap START."
                else "Off. Plug in your iRig, then tap START."
            }
            Monitor.message != null && !e.running -> Monitor.message
            e.running -> buildString {
                append(if (e.muted) "Muted" else "Live")
                e.inputName?.let { append(" · from $it") }
                append(" → ${e.outputName ?: "default output"}")
                if (e.latencyMs > 0) append(" · ~${e.latencyMs} ms buffer")
                if (e.outputName?.contains("Bluetooth") == true) {
                    append("\nBluetooth adds a lot of lag. Wired headphones work best.")
                }
                Monitor.message?.let { append("\n$it") }
            }
            else -> "Starting…"
        }
    }

    // ---- UI ----

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        col.addView(TextView(this).apply {
            text = "FRETWIRE MONITOR"
            setTextColor(0xFF3DF2B0.toInt())
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        })
        col.addView(TextView(this).apply {
            text = "Hear your iRig guitar in your headphones, mixed with whatever else is playing."
            setTextColor(0xFFB0B0C0.toInt())
            textSize = 14f
            setPadding(0, dp(4), 0, dp(20))
        })

        meter = MeterView(this)
        col.addView(meter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, dp(10), 0, dp(16))
        }
        col.addView(status)

        startBtn = Button(this).apply {
            textSize = 18f
            setTextColor(0xFF05050A.toInt())
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setOnClickListener { onStartStop() }
        }
        col.addView(startBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))

        volumeLabel = label("Guitar volume")
        col.addView(volumeLabel)
        volume = SeekBar(this).apply {
            max = 100
            progress = Monitor.volume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) Monitor.volume = p
                    volumeLabel.text = "Guitar volume  ${volumeDb(p)}"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        volumeLabel.text = "Guitar volume  ${volumeDb(Monitor.volume)}"
        col.addView(volume, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        mute = switch("Mute guitar", Monitor.muted) { Monitor.muted = it }
        col.addView(mute)

        col.addView(label("Guitar input"))
        inputSpinner = Spinner(this)
        inputSpinner.onItemSelectedListener = selected { pos ->
            val key = inputKeys.getOrNull(pos) ?: return@selected
            if (key != Monitor.inputChoice) {
                Monitor.inputChoice = key
                MonitorService.send(this, MonitorService.ACTION_RESTART)
            }
        }
        col.addView(inputSpinner)

        col.addView(label("Play guitar through"))
        outputSpinner = Spinner(this)
        outputSpinner.onItemSelectedListener = selected { pos ->
            val key = outputKeys.getOrNull(pos) ?: return@selected
            if (key != Monitor.outputChoice) {
                Monitor.outputChoice = key
                MonitorService.send(this, MonitorService.ACTION_RESTART)
            }
        }
        col.addView(outputSpinner)

        col.addView(spacer(12))
        lowLatency = switch("Lowest latency (turn off if you hear crackles)", Monitor.lowLatency) {
            Monitor.lowLatency = it
            MonitorService.send(this, MonitorService.ACTION_RESTART)
        }
        col.addView(lowLatency)

        floating = switch("Floating control over other apps", Monitor.floating) { on ->
            Monitor.floating = on
            if (on && !Settings.canDrawOverlays(this)) openOverlaySettings()
            MonitorService.send(this, MonitorService.ACTION_SYNC_OVERLAY)
        }
        col.addView(floating)

        col.addView(TextView(this).apply {
            text = """
                Tips
                • Plug your headphones into the iRig's headphone jack, the phone's jack, or a USB-C adapter. Wired is best; Bluetooth adds noticeable delay.
                • Music, videos and other apps keep playing. Use the guitar volume to balance against them.
                • The floating 🎸 bubble: tap it for volume, mute and stop; drag it anywhere. Its ring lights up as you play.
                • If the meter stays flat, turn up the gain knob on the iRig, or pick the input yourself above.
            """.trimIndent()
            setTextColor(0xFF9090A0.toInt())
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            setBackgroundColor(0xFF05050A.toInt())
            addView(col)
        }
    }

    private fun volumeDb(p: Int): String {
        if (p == 0) return "off"
        val x = p / 50f
        val db = 20 * log10(x * x)
        return String.format("%+.0f dB", db)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setTextColor(0xFFB0B0C0.toInt())
        textSize = 13f
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun spacer(h: Int) = View(this).apply { minimumHeight = dp(h) }

    private fun switch(t: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t
        isChecked = checked
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, v -> if (!syncing) onChange(v) }
    }

    private fun selected(onPick: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(29).toFloat()
        setColor(color)
    }

    companion object {
        private const val REQ_PERMS = 1
    }
}

/** Horizontal input level meter, in dB. */
class MeterView(context: Context) : View(context) {
    var level = 0f
    var muted = false
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A26.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        val u = if (level <= 0.0001f) 0f else ((20f * log10(level) + 48f) / 48f).coerceIn(0f, 1f)
        if (u <= 0f) return
        fg.color = when {
            muted -> 0xFF8A8A99.toInt()
            level > 0.9f -> 0xFFFF5C7A.toInt()
            else -> 0xFF3DF2B0.toInt()
        }
        rect.set(0f, 0f, width * u, height.toFloat())
        canvas.drawRoundRect(rect, r, r, fg)
    }
}
