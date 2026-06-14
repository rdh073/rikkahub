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
 * T8 invariant + finding 3: the per-run automation grant (`pendingAutomationGrant`) is transient lease
 * state, but its lifecycle is the whole TURN, not a single lease entry. The per-generation guard
 * (`activeAutomationGuard`) is always dropped at teardown; the grant is dropped TOO when the turn
 * truly ended (no pending approval), so a stale per-run grant cannot leak onto a LATER, unrelated run
 * (T11). When an ASK-guardrail approval breaks the turn (a Pending tool waits for the user), the lease
 * tears down but the grant is PRESERVED so the approval-resume re-mints the guard from it. Both fields
 * live on the session because the kill-switch thread must reach them.
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
     * Finding 3: a per-run grant authorizes a whole TURN, not a single lease entry. An ASK-guardrail
     * approval breaks the turn (a Pending tool waits for the user) and the lease tears down BUT the
     * turn has not ended — the approval-resume re-enters the lease and must re-mint the SAME guard from
     * the SAME grant. So the lease teardown must be able to drop the per-generation guard while
     * PRESERVING the per-run grant for the resume. Clearing both (the old single-step teardown) is the
     * bug: on resume the grant is gone, no guard is minted, and the approved `ui_*` call errors
     * "Tool not found". The terminal teardown (no pending approval) still clears both.
     */
    @Test
    fun `preserving the grant on teardown nulls the guard but keeps the grant for the resume`() {
        val s = session()
        val grant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )
        s.pendingAutomationGrant = grant

        s.clearAutomationLeaseState(preserveGrant = true)

        assertNull("the per-generation guard is always dropped at teardown", s.activeAutomationGuard)
        assertSame(
            "the per-run grant must survive an approval-break teardown so the resume re-mints it",
            grant,
            s.pendingAutomationGrant,
        )
    }

    @Test
    fun `the terminal teardown still clears both the guard and the grant`() {
        val s = session()
        s.pendingAutomationGrant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.target"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        s.clearAutomationLeaseState(preserveGrant = false)

        assertNull(
            "a turn that truly ended (no pending approval) must not leak its grant to a later run",
            s.pendingAutomationGrant,
        )
        assertNull(s.activeAutomationGuard)
    }
}
