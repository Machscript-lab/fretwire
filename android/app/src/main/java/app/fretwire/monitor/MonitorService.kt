package app.fretwire.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var am: AudioManager
    private var overlay: Overlay? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var inputKey: String? = null
    private var outputKey: String? = null
    private val listener: () -> Unit = { handler.post { updateNotification() } }

    private val reconcile = Runnable { if (Monitor.active) startEngine(force = false) }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = schedule()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = schedule()
        private fun schedule() {
            handler.removeCallbacks(reconcile)
            handler.postDelayed(reconcile, 700)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Monitor.init(this)
        am = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Guitar monitor", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        Monitor.engine.onFailure = { msg ->
            handler.post {
                inputKey = null
                Monitor.message = "Stopped: $msg"
                Monitor.changed()
            }
        }
        Monitor.addListener(listener)
        am.registerAudioDeviceCallback(deviceCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, null -> {
                goForeground()
                Monitor.active = true
                acquireWakeLock()
                startEngine(force = true)
                syncOverlay()
            }
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> Monitor.muted = !Monitor.muted
            ACTION_RESTART -> if (Monitor.active) startEngine(force = true)
            ACTION_SYNC_OVERLAY -> syncOverlay()
        }
        return START_NOT_STICKY
    }

    private fun startEngine(force: Boolean) {
        val input = Monitor.resolveInput(am)
        val output = Monitor.resolveOutput(am)
        if (input == null) {
            Monitor.engine.stop()
            inputKey = null
            Monitor.message = "Waiting for your iRig. Plug it in."
            Monitor.changed()
            return
        }
        val newIn = Monitor.keyOf(input)
        val newOut = output?.let(Monitor::keyOf) ?: Monitor.SYSTEM
        if (!force && Monitor.engine.running && newIn == inputKey && newOut == outputKey) return
        inputKey = newIn
        outputKey = newOut
        Monitor.message = if (input.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
            "Using the phone mic. Wear headphones or it will howl." else null
        Monitor.engine.start(input, output, Monitor.lowLatency)
        Monitor.changed()
    }

    private fun syncOverlay() {
        val want = Monitor.active && Monitor.floating && Settings.canDrawOverlays(this)
        if (want && overlay == null) {
            overlay = Overlay(this) { stopFromUi() }.also { it.show() }
        } else if (!want && overlay != null) {
            overlay?.hide()
            overlay = null
        }
    }

    private fun stopFromUi() = shutdown()

    private fun shutdown() {
        Monitor.active = false
        Monitor.engine.stop()
        Monitor.message = null
        inputKey = null
        overlay?.hide()
        overlay = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Monitor.changed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        am.unregisterAudioDeviceCallback(deviceCallback)
        Monitor.removeListener(listener)
        Monitor.engine.onFailure = null
        if (Monitor.active) {
            Monitor.active = false
            Monitor.engine.stop()
            overlay?.hide()
            wakeLock?.let { if (it.isHeld) it.release() }
            Monitor.changed()
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fretwire:monitor")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification() {
        if (!Monitor.active) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            Monitor.message != null && !Monitor.engine.running -> "Guitar monitor paused"
            Monitor.muted -> "Guitar muted"
            else -> "Guitar monitor on"
        }
        val text = Monitor.message ?: Monitor.engine.inputName?.let { "Listening to $it" }
            ?: "Your guitar plays alongside your other audio"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(action(if (Monitor.muted) "Unmute" else "Mute", ACTION_TOGGLE_MUTE, 1))
            .addAction(action("Stop", ACTION_STOP, 2))
            .build()
    }

    private fun action(title: String, act: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, MonitorService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), title, pi).build()
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "app.fretwire.monitor.START"
        const val ACTION_STOP = "app.fretwire.monitor.STOP"
        const val ACTION_TOGGLE_MUTE = "app.fretwire.monitor.TOGGLE_MUTE"
        const val ACTION_RESTART = "app.fretwire.monitor.RESTART"
        const val ACTION_SYNC_OVERLAY = "app.fretwire.monitor.SYNC_OVERLAY"

        fun send(context: Context, action: String) {
            if (action != ACTION_START && !Monitor.active) return
            val i = Intent(context, MonitorService::class.java).setAction(action)
            if (action == ACTION_START) context.startForegroundService(i) else context.startService(i)
        }
    }
}
