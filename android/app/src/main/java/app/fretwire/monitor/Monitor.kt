package app.fretwire.monitor

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.concurrent.CopyOnWriteArraySet

/** App-wide state shared by the screen, the service and the floating control. */
object Monitor {
    const val AUTO = "auto"
    const val SYSTEM = "system"

    private lateinit var prefs: SharedPreferences
    lateinit var engine: AudioEngine
        private set

    /** Service is on (it may be waiting for the iRig to be plugged in). */
    @Volatile var active = false
    /** Last status / problem to show the user. */
    @Volatile var message: String? = null

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun init(context: Context) {
        if (::engine.isInitialized) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
        engine = AudioEngine(app)
        engine.gain = sliderToGain(volume)
    }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun changed() = listeners.forEach { it() }

    /** Volume slider 0..100; 50 = unity gain, 100 = +12 dB. */
    var volume: Int
        get() = prefs.getInt("volume", 50)
        set(v) {
            prefs.edit().putInt("volume", v.coerceIn(0, 100)).apply()
            engine.gain = sliderToGain(v)
            changed()
        }

    var muted: Boolean
        get() = engine.muted
        set(v) { engine.muted = v; changed() }

    var lowLatency: Boolean
        get() = prefs.getBoolean("lowLatency", true)
        set(v) = prefs.edit().putBoolean("lowLatency", v).apply()

    var floating: Boolean
        get() = prefs.getBoolean("floating", true)
        set(v) = prefs.edit().putBoolean("floating", v).apply()

    /** [AUTO] or a device key from [keyOf]. */
    var inputChoice: String
        get() = prefs.getString("input", AUTO) ?: AUTO
        set(v) = prefs.edit().putString("input", v).apply()

    /** [SYSTEM] or a device key from [keyOf]. */
    var outputChoice: String
        get() = prefs.getString("output", SYSTEM) ?: SYSTEM
        set(v) = prefs.edit().putString("output", v).apply()

    private fun sliderToGain(v: Int): Float {
        val x = v.coerceIn(0, 100) / 50f
        return x * x
    }

    /** Stable across unplug/replug, unlike AudioDeviceInfo.id. */
    fun keyOf(d: AudioDeviceInfo): String =
        "${d.type}|${d.productName}|" + (if (Build.VERSION.SDK_INT >= 28) d.address else "")

    fun inputs(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_FM_TUNER }
            .distinctBy { keyOf(it) }

    fun outputs(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter {
                it.type != AudioDeviceInfo.TYPE_TELEPHONY &&
                        it.type != AudioDeviceInfo.TYPE_FM &&
                        it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            .distinctBy { keyOf(it) }

    fun usbInput(am: AudioManager) = inputs(am).firstOrNull { AudioEngine.isUsb(it.type) }

    /** Null with auto mode means "no iRig found". */
    fun resolveInput(am: AudioManager): AudioDeviceInfo? {
        val choice = inputChoice
        if (choice == AUTO) return usbInput(am)
        return inputs(am).firstOrNull { keyOf(it) == choice } ?: usbInput(am)
    }

    /** Null means let Android pick (same place your other apps' sound goes). */
    fun resolveOutput(am: AudioManager): AudioDeviceInfo? {
        val choice = outputChoice
        if (choice == SYSTEM) return null
        return outputs(am).firstOrNull { keyOf(it) == choice }
    }
}
