package me.rerere.rikkahub.service.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The real Android backend for the #187 v1 UI-automation runtime — the ONLY importer of the
 * `android.accessibility` / `android.accessibilityservice` APIs (design I10). It implements the pure
 * [AutomationBackend] seam by walking the live [AccessibilityWindowInfo] forest into the value-type
 * [RawTree] that `:automation`'s [me.rerere.automation.observe.SnapshotProjector] then projects. It
 * exposes NO write verbs and dispatches NO gestures — read-only by construction (write verbs are
 * #198).
 *
 * The service is instantiated by the Android system, not by Koin. It publishes itself as a
 * process-singleton ([instance]) on connect and clears it on teardown, so
 * [AutomationRuntimeRegistry] can hand the live, connected instance to the per-generation tool
 * factory. It is inert until the user enables it in system accessibility settings.
 *
 * Concurrency (design §6 / [AutomationBackend] kdoc): a tool's `execute` runs under
 * `Dispatchers.IO`, so [snapshotRawTree] marshals to a single dedicated service thread
 * ([serviceDispatcher]) and serializes concurrent captures with a [Mutex] — the accessibility node
 * tree must be read off one thread and every [AccessibilityNodeInfo] recycled on every path.
 *
 * Cancellation (design I9): [cancelInFlight] cancels any parked capture so a kill-switch revoke
 * tears down in-flight backend work rather than letting it complete. The capability guard's
 * `revoke()` denies future authorize; this hook cancels the work already running.
 */
class AccessibilityRuntime : AccessibilityService(), AutomationBackend {

    // Monotonic per design I6/P11: bumped on every window state/content change. The core treats a
    // regressing seq as a backend bug, so this only ever increases.
    private val stateSeq = AtomicLong(0L)

    // One dedicated thread for all node-tree reads. Recreated per connect so a reconnect after an
    // unbind starts from a clean scope.
    private val serviceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rikkahub-a11y-snapshot").apply { isDaemon = true }
    }
    private val serviceDispatcher = serviceExecutor.asCoroutineDispatcher()
    private var captureScope = CoroutineScope(SupervisorJob() + serviceDispatcher)

    // Serializes concurrent snapshot captures (one node-tree read at a time).
    private val captureMutex = Mutex()

    // Floating STOP kill-switch. WindowManager add/remove must run on the service main thread.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlay: KillSwitchOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Multi-window awareness (app + system dialogs) + the two events that advance stateSeq.
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> stateSeq.incrementAndGet()
        }
    }

    override fun onInterrupt() {
        // No queued feedback to abandon: this service only reads.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        cancelInFlight()
        mainHandler.post {
            overlay?.hide()
            overlay = null
        }
        if (instance === this) instance = null
    }

    /** Cancel any parked capture (design I9 kill-switch hook) and restart the scope for reuse. */
    fun cancelInFlight() {
        captureScope.cancel()
        captureScope = CoroutineScope(SupervisorJob() + serviceDispatcher)
    }

    /**
     * Show or hide the floating STOP kill-switch (design §7). Called by `ChatService` (via
     * [AutomationRuntimeRegistry]) when a per-conversation lease becomes active / is released.
     * [onStop] revokes the active guard(s) and cancels in-flight work.
     */
    fun setAutomationActive(active: Boolean, onStop: () -> Unit) {
        mainHandler.post {
            if (active) {
                if (overlay == null) overlay = KillSwitchOverlay(this, onStop)
                overlay?.show()
            } else {
                overlay?.hide()
                overlay = null
            }
        }
    }

    /** The package owning the active window; null when nothing is reachable (fails closed upstream). */
    val foregroundPackage: String?
        get() = rootInActiveWindow?.let { root ->
            val pkg = root.packageName?.toString()
            root.recycle()
            pkg
        }

    override suspend fun snapshotRawTree(): RawTree = captureMutex.withLock {
        withContext(captureScope.coroutineContext) {
            val seq = stateSeq.get()
            val foreground = foregroundPackage ?: HOST_PACKAGE
            // getWindows() hands out live AccessibilityWindowInfo handles; recycle each after copying
            // its subtree into the value RawWindow (resource discipline — release on every path). On
            // API 33+ recycle() is a no-op, but minSdk is 26 where leaking windows is real.
            val rawWindows = windows.orEmpty().mapNotNull { window ->
                try {
                    window.toRawWindow()
                } finally {
                    @Suppress("DEPRECATION")
                    window.recycle()
                }
            }
            RawTree(stateSeq = seq, foregroundPkg = foreground, windows = rawWindows)
        }
    }

    override fun windowContentHash(stateSeq: Long): String {
        // Structural/content hash of the active window for the v2 TOCTOU close (design §5, gate
        // finding #7). Contract-only in v1 (no write verb lands), but implemented from day one so the
        // real backend ships the hook before any write verb exists. A dropped WINDOW_STATE event
        // leaves stateSeq stale-but-equal, so the v2 act-assert must compare BOTH this hash and the
        // expected seq.
        val root = rootInActiveWindow ?: return "empty:$stateSeq"
        val acc = StringBuilder()
        try {
            foldStructure(root, acc)
        } finally {
            root.recycle()
        }
        return acc.toString().hashCode().toString(16)
    }

    private fun foldStructure(node: AccessibilityNodeInfo, acc: StringBuilder) {
        acc.append(node.className ?: "?")
            .append(':')
            .append(node.text?.length ?: 0)
            .append(':')
            .append(node.childCount)
            .append('|')
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                try {
                    foldStructure(child, acc)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    private fun AccessibilityWindowInfo.toRawWindow(): RawWindow? {
        val root = this.root ?: return null // secure/inaccessible window: no content to project
        return try {
            val pkg = root.packageName?.toString() ?: return null
            RawWindow(
                pkg = pkg,
                // The a11y framework does not expose FLAG_SECURE on a window; a secure window simply
                // returns no readable content (handled by the null-root branch above), so we report
                // false rather than fabricate a flag we cannot read.
                secure = false,
                systemWindow = type == AccessibilityWindowInfo.TYPE_SYSTEM || pkg.isSystemUiPackage(),
                root = root.toRawNode(),
            )
        } finally {
            root.recycle()
        }
    }

    /** Recursive value-copy of a node subtree. Recycles every child it descends into. */
    private fun AccessibilityNodeInfo.toRawNode(): RawNode {
        val bounds = android.graphics.Rect().also { getBoundsInScreen(it) }
        val children = ArrayList<RawNode>(childCount)
        for (i in 0 until childCount) {
            getChild(i)?.let { child ->
                try {
                    children.add(child.toRawNode())
                } finally {
                    child.recycle()
                }
            }
        }
        return RawNode(
            resourceId = viewIdResourceName,
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            className = className?.toString(),
            visible = isVisibleToUser,
            hasArea = !bounds.isEmpty,
            clickable = isClickable,
            editable = isEditable,
            scrollable = isScrollable,
            checkable = isCheckable,
            checked = isChecked,
            password = isPassword,
            children = children,
        )
    }

    private fun String.isSystemUiPackage(): Boolean =
        this == "com.android.systemui" || this.endsWith("packageinstaller")

    companion object {
        const val HOST_PACKAGE = "me.rerere.rikkahub"

        /**
         * The live, connected service or null. Set on [onServiceConnected], cleared on teardown.
         * `@Volatile` because the kill-switch / tool factory may read it off another thread.
         */
        @Volatile
        var instance: AccessibilityRuntime? = null
            private set
    }
}
