package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationVerb
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * T8 invariant: the per-run automation grant (`pendingAutomationGrant`) is transient lease state that
 * shares the EXACT lifecycle of `activeAutomationGuard`. When the automation lease tears down (the one
 * lifecycle that nulls the guard), the grant must be cleared in the SAME step -- otherwise a stale
 * per-run grant from a prior generation could leak its surface into the next lease derivation (T11).
 * Both live on the session because the kill-switch thread must reach them, so the teardown is a single
 * unified clear, not two scattered null-assignments.
 */
class ConversationSessionAutomationLeaseStateTest {

    private fun session(): ConversationSession = ConversationSession(
        id = Uuid.random(),
        initial = Conversation.ofId(id = Uuid.random()),
        scope = CoroutineScope(Dispatchers.Unconfined),
        onIdle = {},
    )

    @Test
    fun `clearing the automation lease state nulls the pending grant alongside the guard`() {
        val s = session()
        s.pendingAutomationGrant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        s.clearAutomationLeaseState()

        assertNull(
            "the per-run grant must be cleared by the same lifecycle that nulls activeAutomationGuard",
            s.pendingAutomationGrant,
        )
        assertNull(s.activeAutomationGuard)
    }

    @Test
    fun `session cleanup clears the pending grant`() {
        val s = session()
        s.pendingAutomationGrant = AutomationGrant(enabled = true, allowedPackages = setOf("com.app"))

        s.cleanup()

        assertNull(s.pendingAutomationGrant)
    }

    /**
     * Per-run-transient invariant: the pending grant is a one-derivation token bound to the immediate
     * next generation. `consumePendingAutomationGrant` returns it AND nulls it in the same step, so the
     * lease derivation that reads it cannot leave it on the session to authorize a LATER, unrelated run.
     * The leak the old code allowed: the grant was cleared only by `clearAutomationLeaseState`, which a
     * generation reaches only when a guard was actually minted -- so a grant that derived no guard (no
     * approved package, expired TTL, or automation disabled at run time) survived onto the next turn.
     */
    @Test
    fun `consuming the pending grant returns it and clears it in one step`() {
        val s = session()
        val grant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )
        s.pendingAutomationGrant = grant

        val consumed = s.consumePendingAutomationGrant()

        assertSame("consume returns the grant for this run's derivation", grant, consumed)
        assertNull("a consumed grant must NOT remain to scope a later unrelated run", s.pendingAutomationGrant)
    }

    @Test
    fun `consuming when no grant is pending returns null and stays null`() {
        val s = session()

        val consumed = s.consumePendingAutomationGrant()

        assertNull(consumed)
        assertNull(s.pendingAutomationGrant)
    }
}
