package app.fretwire.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * Live passthrough: reads the guitar input and plays it straight back out.
 *
 * It never requests audio focus, so music, videos and calls from other apps keep
 * playing and are mixed with the guitar by the system.
 *
 * The input is the clock master: reads block, writes never do. If the output runs
 * slower than the input, excess samples are dropped instead of letting latency
 * creep up. If the output underruns (crackles), its buffer grows by one burst.
 */
class AudioEngine(private val context: Context) {

    /** Linear gain applied to the guitar signal. */
    @Volatile var gain = 1f
    @Volatile var muted = false

    /** Input peak level, 0..1, decaying. For meters. */
    @Volatile var level = 0f
        private set
    @Volatile var running = false
        private set
    @Volatile var inputName: String? = null
        private set
    @Volatile var outputName: String? = null
        private set
    @Volatile var latencyMs = 0
        private set

    /** Called on the audio thread when the engine stops by itself (error or device loss). */
    var onFailure: ((String) -> Unit)? = null

    private var thread: Thread? = null

    fun start(input: AudioDeviceInfo?, output: AudioDeviceInfo?, lowLatency: Boolean) {
        stop()
        running = true
        thread = Thread({ loop(input, output, lowLatency) }, "fretwire-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) it.join(1500) }
        thread = null
        level = 0f
        latencyMs = 0
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(sampleRate: Int, bytes: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(MediaRecorder.AudioSource.VOICE_PERFORMANCE)
            add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.MIC)
        }
        var lastError: Exception? = null
        for (source in sources) {
            try {
                val rec = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bytes)
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
                rec.release()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("Couldn't open the audio input", lastError)
    }

    private fun loop(input: AudioDeviceInfo?, output: AudioDeviceInfo?, lowLatency: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val am = context.getSystemService(AudioManager::class.java)
        val sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 256

        var rec: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            val minRec = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            rec = buildRecord(sampleRate, max(minRec, burst * 4 * 8))
            if (input != null) rec.preferredDevice = input

            val outFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val minTrack = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(outFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minTrack, burst * 4 * 16))
            if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            track = builder.build()
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("Couldn't open the audio output")
            if (output != null) track.preferredDevice = output

            val capacity = track.bufferCapacityInFrames
            var target = min(capacity, if (lowLatency) burst * 2 else burst * 6)
            target = track.setBufferSizeInFrames(target).takeIf { it > 0 } ?: target

            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("The input is busy (another app may be recording)")
            }
            track.play()

            val buf = FloatArray(burst)
            var lastUnderruns = track.underrunCount
            var tick = 0
            while (running) {
                val n = rec.read(buf, 0, burst, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IllegalStateException("Input stopped (error $n)")
                if (n == 0) continue

                val g = if (muted) 0f else gain
                var peak = 0f
                for (i in 0 until n) {
                    val x = buf[i]
                    val a = abs(x)
                    if (a > peak) peak = a
                    buf[i] = softClip(x * g)
                }
                level = max(peak, level * 0.85f)

                track.write(buf, 0, n, AudioTrack.WRITE_NON_BLOCKING)

                val underruns = track.underrunCount
                if (underruns > lastUnderruns) {
                    lastUnderruns = underruns
                    if (target < capacity) {
                        target = min(capacity, target + burst)
                        target = track.setBufferSizeInFrames(target).takeIf { it > 0 } ?: target
                    }
                }

                if (tick++ % 32 == 0) {
                    inputName = rec.routedDevice?.let(::describe)
                    outputName = track.routedDevice?.let(::describe)
                    // Output buffer + one input burst; device/driver latency isn't visible here.
                    latencyMs = ((target + burst) * 1000L / sampleRate).toInt()
                }
            }
        } catch (e: Exception) {
            if (running) {
                running = false
                onFailure?.invoke(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            try { track?.pause(); track?.flush() } catch (_: Exception) {}
            track?.release()
            level = 0f
        }
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val shaped = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -shaped else shaped
    }

    companion object {
        fun describe(d: AudioDeviceInfo): String {
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
            val kind = typeLabel(d.type)
            return if (name != null && (d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                        d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        isBleType(d.type))
            ) "$name ($kind)" else kind
        }

        fun isUsb(type: Int) = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || isBleType(type)

        private fun isBleType(type: Int) = Build.VERSION.SDK_INT >= 31 &&
                (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)

        fun typeLabel(type: Int): String = when {
            isUsb(type) -> "USB"
            isBluetooth(type) -> "Bluetooth"
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            type == AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line"
            else -> "Audio device"
        }
    }
}
