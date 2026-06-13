package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationVerb
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertNull
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
}
