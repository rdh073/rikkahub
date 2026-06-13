package me.rerere.rikkahub.service

import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.Verb
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationSink
import me.rerere.rikkahub.data.model.AutomationVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T11 root-cause: `ChatService.withAutomationLease` used to mint the guard with a HARD-CODED
 * `surface = emptySet()`, so `CapabilityGuard` DENIED every request regardless of any user grant —
 * the automation subsystem was inert. The fix derives the lease `Capability` from the effective
 * grant (`session.pendingAutomationGrant ?: assistant.automationGrant`) via the pure
 * [effectiveAutomationCapability], which maps the `:app` `AutomationGrant` mirror onto the kernel
 * grant and runs `toCapability`.
 *
 * These properties pin the seam: (1) no grant ⇒ null capability ⇒ NO guard ⇒ DENY (no regression);
 * (2) per-run grant overrides the assistant default; (3) a real grant fills the surface/verbs the
 * user approved while SUBMIT stays withheld.
 */
class EffectiveAutomationCapabilityTest {

    private val sessionId = "session-1"
    private val now = 1_000_000L

    @Test
    fun `absent grant (both default) derives no capability so the guard denies all`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(),
            sessionId = sessionId,
            now = now,
        )

        assertNull("a default/empty grant must NOT mint a usable capability", cap)
    }

    @Test
    fun `enabled-but-empty-surface grant still derives no capability`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = emptySet(),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            sessionId = sessionId,
            now = now,
        )

        assertNull("enabled grant with no approved package is still deny-all", cap)
    }

    @Test
    fun `zero TTL or zero steps derives no capability`() {
        val zeroTtl = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 0,
                maxSteps = 50,
            ),
            assistantGrant = AutomationGrant(),
            sessionId = sessionId,
            now = now,
        )
        val zeroSteps = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 5,
                maxSteps = 0,
            ),
            assistantGrant = AutomationGrant(),
            sessionId = sessionId,
            now = now,
        )

        assertNull("zero TTL is already-expired ⇒ deny-all", zeroTtl)
        assertNull("zero steps is no-admit ⇒ deny-all", zeroSteps)
    }

    @Test
    fun `grant for package P with OBSERVE+TAP fills the surface and those verbs`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            sessionId = sessionId,
            now = now,
        )!!

        assertEquals(setOf("com.example.target"), cap.surface)
        assertEquals(setOf(Verb.OBSERVE, Verb.TAP), cap.verbs)
        assertEquals(sessionId, cap.sessionId)
        assertEquals(now + 5L * 60_000L, cap.lease.expiresAt)
        assertEquals(50, cap.lease.maxSteps)
    }

    @Test
    fun `SUBMIT sink is stripped even when the grant lists it`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.SET_TEXT),
                sinks = setOf(AutomationSink.TYPE_INTO, AutomationSink.SUBMIT),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
            sessionId = sessionId,
            now = now,
        )!!

        assertTrue("TYPE_INTO is in budget", Sink.TYPE_INTO in cap.sinkBudget)
        assertTrue("SUBMIT must never reach the lease", Sink.SUBMIT !in cap.sinkBudget)
    }

    @Test
    fun `per-run pending grant overrides the assistant default grant`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.perrun.app"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 3,
                maxSteps = 10,
            ),
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.assistant.default"),
                verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
                ttlMinutes = 30,
                maxSteps = 256,
            ),
            sessionId = sessionId,
            now = now,
        )!!

        assertEquals("per-run surface wins", setOf("com.perrun.app"), cap.surface)
        assertEquals(now + 3L * 60_000L, cap.lease.expiresAt)
        assertEquals(10, cap.lease.maxSteps)
    }

    @Test
    fun `absent per-run grant falls back to the assistant default grant`() {
        val cap = effectiveAutomationCapability(
            pendingGrant = null,
            assistantGrant = AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.assistant.default"),
                verbs = setOf(AutomationVerb.OBSERVE),
                ttlMinutes = 7,
                maxSteps = 20,
            ),
            sessionId = sessionId,
            now = now,
        )!!

        assertEquals(setOf("com.assistant.default"), cap.surface)
    }
}
