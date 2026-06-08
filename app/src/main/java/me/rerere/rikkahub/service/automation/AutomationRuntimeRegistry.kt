package me.rerere.rikkahub.service.automation

import me.rerere.automation.act.AutomationCore
import me.rerere.automation.backend.AutomationBackend

/**
 * Koin-injectable indirection over the system-instantiated [AccessibilityRuntime].
 *
 * An `AccessibilityService` is constructed by the Android framework, never by Koin, so the design's
 * `single { AccessibilityRuntime }` intent cannot be taken literally (a Koin-built instance would be
 * a dead object the system never connects). The faithful minimal equivalent is this registry: a
 * Koin `single` that exposes the *live* [AccessibilityRuntime.instance] as the pure
 * [AutomationBackend] to `ChatService` / the `ui_observe` factory, plus the foreground package and
 * the kill-switch cancel hook.
 *
 * Returns null whenever the service is not connected (the user has not enabled it in system
 * accessibility settings), so the per-generation factory fails closed to an empty tool surface.
 */
class AutomationRuntimeRegistry {

    /** The live backend, or null when the accessibility service is not connected. */
    fun backend(): AutomationBackend? = AccessibilityRuntime.instance

    /** A fresh observation core over the live backend, or null when unavailable. */
    fun core(): AutomationCore? = backend()?.let { AutomationCore(it) }

    /** Foreground package of the device, read before observing (design S2). Null when disconnected. */
    fun foregroundPackage(): String? = AccessibilityRuntime.instance?.foregroundPackage

    /** Kill-switch hook (design I9): cancel any in-flight capture on the live service. */
    fun cancelInFlight() {
        AccessibilityRuntime.instance?.cancelInFlight()
    }

    /**
     * Show/hide the floating STOP overlay on the live service (design §7). No-op when the service is
     * not connected. [onStop] revokes the active guard(s) + cancels in-flight work.
     */
    fun setAutomationActive(active: Boolean, onStop: () -> Unit) {
        AccessibilityRuntime.instance?.setAutomationActive(active, onStop)
    }
}
