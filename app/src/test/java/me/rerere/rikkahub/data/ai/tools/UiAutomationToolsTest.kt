package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.automation.act.AutomationCore
import me.rerere.automation.backend.FakeBackend
import me.rerere.automation.backend.RawNode
import me.rerere.automation.backend.RawTree
import me.rerere.automation.backend.RawWindow
import me.rerere.automation.cap.Capability
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Lease
import me.rerere.automation.cap.TrustClock
import me.rerere.automation.cap.Verb
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the per-generation `ui_observe` tool factory ([getUiAutomationTools]) — the
 * read-only v1 `:app` surface of #187, built on the already-merged `:automation` kernel (#205).
 *
 * The factory is intentionally Android-free (design I10): it takes an [AutomationCore] over a
 * [FakeBackend], a [CapabilityGuard] over a hand-advanced [TrustClock], and a foreground-package
 * supplier — so the whole contract is exercised here with NO Android, NO device, mirroring
 * SpawnToolTest. The real [me.rerere.rikkahub.service.automation.AccessibilityRuntime] backend is
 * the only a11y-API importer and is covered by an instrumented contract test (NOT a CI gate).
 *
 * Four regressions pinned (the maintainer-mandated CI-runnable surface):
 *  1. ACTIVATION GATING — off ⇒ emptyList(); on ⇒ exactly [ui_observe], needsApproval==false.
 *  2. LEASE WIRING / S2 — a revoked OR clock-expired guard returns a denied Text AND the backend is
 *     never hit (authorize() runs BEFORE AutomationCore.observe()); a healthy guard yields one hit.
 *  3. SNAPSHOT → UIMessagePart.Text MAPPING — the single returned part is a Text whose table carries
 *     stateSeq/foregroundPkg/screenState + each target, NEVER an Image, never the host package, never
 *     password plaintext (delegates to the proven SnapshotProjector).
 *  4. MALFORMED args ⇒ fail-closed denied Text, backend never hit (design P24).
 */
class UiAutomationToolsTest {

    private val target = "com.example.target"
    private val fixedNow = 1_000L
    private val clock = TrustClock { fixedNow }

    /** A guard whose root surface allows [target] for OBSERVE and is still in-lease. */
    private fun healthyGuard(
        surface: Set<String> = setOf(target),
        expiresAt: Long = fixedNow + 60_000L,
        maxSteps: Int = 16,
    ): CapabilityGuard = CapabilityGuard(
        capability = Capability.root(
            sessionId = "conversation-1",
            surface = surface,
            verbs = setOf(Verb.OBSERVE),
            lease = Lease(expiresAt = expiresAt, maxSteps = maxSteps),
        ),
        clock = clock,
    )

    /** Foreground app + a clickable button and a password field, to assert masking in projection. */
    private fun targetTree(stateSeq: Long = 0L): RawTree = RawTree(
        stateSeq = stateSeq,
        foregroundPkg = target,
        windows = listOf(
            RawWindow(
                pkg = target,
                root = RawNode(
                    className = "android.widget.FrameLayout",
                    children = listOf(
                        RawNode(
                            text = "Sign in",
                            className = "android.widget.Button",
                            clickable = true,
                        ),
                        RawNode(
                            text = "hunter2",
                            className = "android.widget.EditText",
                            editable = true,
                            password = true,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun observeTool(
        assistant: Assistant,
        guard: CapabilityGuard?,
        backend: FakeBackend,
        foregroundPkg: String? = target,
    ) = getUiAutomationTools(
        assistant = assistant,
        guard = guard,
        core = AutomationCore(backend),
        foregroundPkg = { foregroundPkg },
    )

    // --- 1. ACTIVATION GATING ---

    @Test
    fun `factory returns empty list when ui automation is disabled`() {
        val backend = FakeBackend(targetTree())
        val tools = observeTool(
            assistant = Assistant(uiAutomationEnabled = false),
            guard = healthyGuard(),
            backend = backend,
        )
        assertTrue("disabled assistant must expose no automation tools", tools.isEmpty())
    }

    @Test
    fun `factory returns empty list when there is no guard even if enabled`() {
        val backend = FakeBackend(targetTree())
        val tools = observeTool(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = null,
            backend = backend,
        )
        assertTrue("a null guard means no authority ⇒ empty surface", tools.isEmpty())
    }

    @Test
    fun `factory exposes exactly the read-only ui_observe tool when enabled`() {
        val backend = FakeBackend(targetTree())
        val tools = observeTool(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = healthyGuard(),
            backend = backend,
        )
        assertEquals(listOf("ui_observe"), tools.map { it.name })
        // needsApproval is forced false: the in-chat approval gate is unreachable while another app
        // is foreground (design constraint 1).
        assertFalse("ui_observe must not request approval", tools.single().needsApproval)
    }

    // --- 2. LEASE WIRING / S2 (guard called BEFORE the backend) ---

    @Test
    fun `revoked guard yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard().also { it.revoke() }
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount) // S2: authorize() ran before AutomationCore.observe()
        val text = parts.single() as UIMessagePart.Text
        assertTrue("denied result must explain the deny", text.text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `expired lease yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        val guard = healthyGuard(expiresAt = fixedNow - 1L) // already past expiry under the trust clock
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `surface-not-allowed yields a denied text and never touches the backend`() {
        val backend = FakeBackend(targetTree())
        // Default-empty surface = deny-all (S1): observing the foreground app is not authorized.
        val guard = healthyGuard(surface = emptySet())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), guard, backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `healthy guard captures exactly one snapshot and returns it as text`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        assertEquals("exactly one backend capture per observe", 1, backend.snapshotCount)
        assertTrue("the single part must be Text, never Image", parts.single() is UIMessagePart.Text)
    }

    // --- 3. SNAPSHOT → TEXT MAPPING (self-sufficient, leak-free) ---

    @Test
    fun `rendered snapshot carries the table header and never leaks password text or an image`() {
        val backend = FakeBackend(targetTree(stateSeq = 7L))
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        val parts = runBlocking { tool.execute(buildJsonObject { }) }

        // Mandatory text channel: providers drop tool-output images (gate A1), so the snapshot must be
        // a single self-sufficient Text and never an Image.
        assertEquals(1, parts.size)
        val text = (parts.single() as UIMessagePart.Text).text

        assertTrue("must report stateSeq", text.contains("7"))
        assertTrue("must report the foreground package", text.contains(target))
        assertTrue("must report a screen state", text.contains("READY"))
        assertTrue("the clickable button label must appear", text.contains("Sign in"))
        // The password field's plaintext must NEVER reach the model (SnapshotProjector masks it).
        assertFalse("password plaintext must never be rendered", text.contains("hunter2"))
        assertFalse(
            "host package must never appear in the projection",
            text.contains("me.rerere.rikkahub"),
        )
        assertTrue("no part may be an image", parts.none { it is UIMessagePart.Image })
    }

    @Test
    fun `host-foreground snapshot renders the pause state with no targets`() {
        // When rikkahub itself is foreground, the projector returns FOREGROUND_IS_HOST + no targets:
        // the agent must pause/re-ground rather than act on host UI (P12). Surface must include the
        // host so the guard admits the observe (we are explicitly observing our own foreground here).
        val hostTree = RawTree(
            stateSeq = 3L,
            foregroundPkg = "me.rerere.rikkahub",
            windows = listOf(RawWindow(pkg = "me.rerere.rikkahub", root = RawNode(text = "chat"))),
        )
        val backend = FakeBackend(hostTree)
        val guard = healthyGuard(surface = setOf("me.rerere.rikkahub"))
        val tool = getUiAutomationTools(
            assistant = Assistant(uiAutomationEnabled = true),
            guard = guard,
            core = AutomationCore(backend),
            foregroundPkg = { "me.rerere.rikkahub" },
        ).single()

        val text = (runBlocking { tool.execute(buildJsonObject { }) }.single() as UIMessagePart.Text).text

        assertTrue("must surface the host-foreground pause state", text.contains("FOREGROUND_IS_HOST"))
        assertEquals("host snapshot must not leak host content", false, text.contains("chat"))
    }

    // --- 4. MALFORMED args ⇒ fail-closed ---

    @Test
    fun `a non-object argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        // ui_observe takes an empty object; a JsonNull (or any non-object) is malformed and must
        // fail closed at the guard (P24), never reaching AutomationCore.
        val parts = runBlocking { tool.execute(JsonNull) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }

    @Test
    fun `a primitive argument fails closed without touching the backend`() {
        val backend = FakeBackend(targetTree())
        val tool = observeTool(Assistant(uiAutomationEnabled = true), healthyGuard(), backend).single()

        val parts = runBlocking { tool.execute(JsonPrimitive("not-an-object")) }

        assertEquals(0, backend.snapshotCount)
        assertTrue((parts.single() as UIMessagePart.Text).text.contains("denied", ignoreCase = true))
    }
}
