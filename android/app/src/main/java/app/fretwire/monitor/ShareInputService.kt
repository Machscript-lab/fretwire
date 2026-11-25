package app.fretwire.monitor

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing by itself. Android lets an enabled accessibility service keep receiving
 * audio input while another app (like Yousician) is also using it; ordinary apps get
 * silenced instead. Having this enabled is what lets the guitar monitor keep running
 * on top of Yousician. It listens to no events and cannot read the screen.
 */
class ShareInputService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, ShareInputService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}
