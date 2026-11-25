package app.fretwire.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Small draggable bubble that floats over every app.
 * Tap: show/hide the volume panel. Drag: move it. The ring shows your input level.
 */
class Overlay(private val context: Context, private val onClose: () -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val root = LinearLayout(context)
    private val bubble = BubbleView(context)
    private val panel = LinearLayout(context)
    private val muteBtn = TextView(context)
    private val seek = SeekBar(context)
    private var seeking = false
    private var shown = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("x", 0)
        y = prefs.getInt("y", context.dp(160))
    }

    private val tick = object : Runnable {
        override fun run() {
            val e = Monitor.engine
            bubble.level = e.level
            bubble.muted = e.muted
            bubble.live = e.running && !e.silenced
            bubble.invalidate()
            muteBtn.text = if (e.muted) "UNMUTE" else "MUTE"
            if (!seeking && seek.progress != Monitor.volume) seek.progress = Monitor.volume
            handler.postDelayed(this, 50)
        }
    }

    init {
        root.orientation = LinearLayout.HORIZONTAL
        root.gravity = Gravity.CENTER_VERTICAL
        val size = context.dp(56)
        root.addView(bubble, LinearLayout.LayoutParams(size, size))

        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER_VERTICAL
        panel.background = GradientDrawable().apply {
            cornerRadius = context.dp(28).toFloat()
            setColor(0xF014141F.toInt())
            setStroke(context.dp(1), 0x403DF2B0)
        }
        panel.setPadding(context.dp(12), context.dp(4), context.dp(8), context.dp(4))
        panel.visibility = View.GONE

        muteBtn.apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(10))
            setOnClickListener { Monitor.muted = !Monitor.muted }
        }
        panel.addView(muteBtn)

        seek.max = 100
        seek.progress = Monitor.volume
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) Monitor.volume = p
            }
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) { seeking = false }
        })
        panel.addView(seek, LinearLayout.LayoutParams(context.dp(150), context.dp(44)))

        panel.addView(iconButton("⚙") {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })
        panel.addView(iconButton("✕") { onClose() })

        root.addView(panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = context.dp(6) })

        setupDrag()
    }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        setOnClickListener { onClick() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        bubble.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).roundToInt().coerceAtLeast(0)
                        params.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        if (shown) wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    } else {
                        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    }
                }
            }
            true
        }
    }

    fun show() {
        if (shown) return
        wm.addView(root, params)
        shown = true
        handler.post(tick)
    }

    fun hide() {
        handler.removeCallbacks(tick)
        if (shown) {
            try { wm.removeView(root) } catch (_: Exception) {}
            shown = false
        }
    }
}

/** Round bubble: guitar icon with a ring that lights up with your playing. */
class BubbleView(context: Context) : View(context) {
    var level = 0f
    var muted = false
    var live = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF014141F.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        color = 0x33FFFFFF
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dp(24).toFloat()
    }
    private val slash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5C7A.toInt()
        strokeWidth = context.dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = minOf(w, h) / 2f
        val inset = ring.strokeWidth
        canvas.drawCircle(w / 2, h / 2, r - inset / 2, fill)
        oval.set(inset, inset, w - inset, h - inset)
        canvas.drawOval(oval, track)

        ring.color = when {
            !live -> 0xFF8A8A99.toInt()
            muted -> 0xFFFF5C7A.toInt()
            else -> 0xFF3DF2B0.toInt()
        }
        val sweep = if (!live) 360f else levelToUnit(level) * 360f
        if (sweep > 1f) canvas.drawArc(oval, -90f, sweep, false, ring)

        text.alpha = if (muted || !live) 110 else 255
        val y = h / 2 - (text.descent() + text.ascent()) / 2
        canvas.drawText("🎸", w / 2, y, text)
        if (muted) canvas.drawLine(w * 0.28f, h * 0.28f, w * 0.72f, h * 0.72f, slash)
    }

    private fun levelToUnit(l: Float): Float {
        if (l <= 0.0001f) return 0f
        val db = 20f * log10(l)
        return ((db + 48f) / 48f).coerceIn(0f, 1f)
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
