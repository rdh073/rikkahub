package me.rerere.rikkahub.service.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import androidx.core.content.getSystemService
import me.rerere.rikkahub.R

/**
 * The floating STOP kill-switch (design §7), reachable from ANY foreground app while automation is
 * active. Implemented as a `TYPE_ACCESSIBILITY_OVERLAY` attached by [AccessibilityRuntime] — that
 * window type is granted together with the accessibility service, so it needs NO
 * `SYSTEM_ALERT_WINDOW` runtime permission (keeps the permission surface minimal).
 *
 * Tapping STOP fires [onStop], which `ChatService` wires to revoke every active capability guard and
 * cancel in-flight work via [AutomationKillSwitch]. The overlay is shown only while a lease is live
 * and removed otherwise, so it never lingers.
 *
 * Read-only feature: this overlay only stops; it never drives UI.
 */
class KillSwitchOverlay(
    private val service: AccessibilityService,
    private val onStop: () -> Unit,
) {
    private val windowManager = service.getSystemService<WindowManager>()
    private var view: View? = null

    fun show() {
        if (view != null) return
        val wm = windowManager ?: return
        val button = Button(service).apply {
            text = service.getString(R.string.automation_kill_switch_stop)
            setBackgroundColor(Color.parseColor("#B00020"))
            setTextColor(Color.WHITE)
            setOnClickListener { onStop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 96
        }
        runCatching { wm.addView(button, params) }
            .onSuccess { view = button }
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeView(current) }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
