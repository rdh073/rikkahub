package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.model.AutomationSink
import me.rerere.rikkahub.data.model.AutomationVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10: the in-chat per-run grant sheet builds a transient [me.rerere.rikkahub.data.model.AutomationGrant]
 * from the foreground package + selected verbs + sinks + TTL. The builder is the pure confirm-logic of
 * the bottom sheet (top-level, JVM-testable like [shouldBlockSubmitForMissingModel]); the ViewModel
 * writes its result to `ConversationSession.pendingAutomationGrant`.
 *
 * The load-bearing invariant proven here is SUBMIT exclusion: the sheet may never mint a submit-class
 * grant — submit automation stays the stricter separate opt-in the kernel withholds — so SUBMIT is
 * stripped regardless of what is passed in. A naive "copy the selected sinks through" implementation
 * fails the SUBMIT case.
 */
class PerRunGrantBuilderTest {

    @Test
    fun `confirming for a foreground package mints an enabled grant scoped to exactly that package`() {
        val grant = buildPerRunGrant(
            foregroundPackage = "com.example.target",
            verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
            sinks = emptySet(),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        assertTrue("a confirmed grant is enabled", grant!!.enabled)
        assertEquals(setOf("com.example.target"), grant.allowedPackages)
        assertEquals(setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP), grant.verbs)
        assertEquals(5, grant.ttlMinutes)
        assertEquals(50, grant.maxSteps)
    }

    @Test
    fun `SUBMIT is stripped from the granted sinks even when selected`() {
        val grant = buildPerRunGrant(
            foregroundPackage = "com.example.target",
            verbs = setOf(AutomationVerb.SET_TEXT),
            sinks = setOf(AutomationSink.TYPE_INTO, AutomationSink.SUBMIT),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        assertFalse(
            "SUBMIT must never reach a per-run grant — it is the stricter separate opt-in",
            grant!!.sinks.contains(AutomationSink.SUBMIT),
        )
        assertTrue(grant.sinks.contains(AutomationSink.TYPE_INTO))
    }

    @Test
    fun `a null or blank foreground package yields no grant`() {
        assertNull(
            "no foreground package = nothing to scope the grant to = no grant",
            buildPerRunGrant(
                foregroundPackage = null,
                verbs = setOf(AutomationVerb.OBSERVE),
                sinks = emptySet(),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
        )
        assertNull(
            buildPerRunGrant(
                foregroundPackage = "   ",
                verbs = setOf(AutomationVerb.OBSERVE),
                sinks = emptySet(),
                ttlMinutes = 5,
                maxSteps = 50,
            ),
        )
    }
}
