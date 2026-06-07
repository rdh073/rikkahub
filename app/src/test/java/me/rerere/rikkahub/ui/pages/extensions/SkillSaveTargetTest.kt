package me.rerere.rikkahub.ui.pages.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression test for save-completion routing in SkillDetailPage / SkillsPage.
 *
 * Original bug (cross-CATEGORY): a single identity-less SaveDone closed BOTH the edit and add dialogs.
 * Carrying [SkillSaveOrigin] on the event fixed that — but origin is a CATEGORY, not an INVOCATION
 * instance. The reopened bug (within-CATEGORY race): start an edit save of file A, dismiss the edit
 * dialog, open the edit dialog again for file B; A's in-flight SaveDone(EDIT) then dismissed B's dialog
 * because both share origin EDIT. The save runs on viewModelScope, so dismissing the dialog does NOT
 * cancel the save — the late completion is real and must be routed to the instance that started it.
 *
 * Fix: [SkillSaveTarget] carries an opaque per-confirm [SkillSaveTarget.token] minted by
 * [SkillSaveTokens]. The page dismisses a dialog only when the completion's token still matches the
 * token of the currently-open instance. [shouldDismiss] mirrors the page's collector decision so the
 * race can be unit-tested without Compose.
 */
class SkillSaveTargetTest {

    /**
     * Mirror of the page collector's decision for a SaveDone of [completion]: dismiss the dialog only
     * when the completion's token still matches the token the page recorded for the currently-open
     * dialog of that category ([openEditToken] / [openAddToken]; null = no open dialog of that kind).
     */
    private fun shouldDismiss(
        completion: SkillSaveTarget,
        openEditToken: Long?,
        openAddToken: Long?,
    ): Boolean = when (completion.origin) {
        SkillSaveOrigin.EDIT -> completion.token == openEditToken
        SkillSaveOrigin.ADD -> completion.token == openAddToken
    }

    @Test
    fun `tokens are unique and monotonic`() {
        val tokens = SkillSaveTokens()
        val a = tokens.next()
        val b = tokens.next()
        assertNotEquals(a, b)
        assertTrue(b > a)
    }

    @Test
    fun `edit-save dismisses the edit dialog it started`() {
        val tokens = SkillSaveTokens()
        val t = tokens.next()
        // The edit dialog that started the save is still open with token t.
        assertTrue(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, t),
                openEditToken = t,
                openAddToken = null,
            )
        )
    }

    @Test
    fun `add-save dismisses the add dialog it started`() {
        val tokens = SkillSaveTokens()
        val t = tokens.next()
        assertTrue(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.ADD, t),
                openEditToken = null,
                openAddToken = t,
            )
        )
    }

    @Test
    fun `edit-save never dismisses an add dialog (cross-category)`() {
        val tokens = SkillSaveTokens()
        val editToken = tokens.next()
        val addToken = tokens.next()
        assertFalse(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, editToken),
                openEditToken = null,
                openAddToken = addToken,
            )
        )
    }

    @Test
    fun `in-flight edit-save of file A does NOT dismiss the edit dialog reopened for file B`() {
        // Repro from the reopened finding: confirm edit of A (token A), dismiss the dialog, reopen the
        // edit dialog for B (token B). A's save is still running on viewModelScope and now completes.
        val tokens = SkillSaveTokens()
        val tokenA = tokens.next()
        val tokenB = tokens.next()
        // With category-only routing this returned true and wrongly closed B's dialog. With instance
        // identity, A's completion no longer matches the open dialog's token (B), so it is ignored.
        assertFalse(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, tokenA),
                openEditToken = tokenB,
                openAddToken = null,
            )
        )
        // And B's own completion still dismisses B.
        assertTrue(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, tokenB),
                openEditToken = tokenB,
                openAddToken = null,
            )
        )
    }

    @Test
    fun `a completion after its dialog was dismissed (none reopened) dismisses nothing`() {
        val tokens = SkillSaveTokens()
        val tokenA = tokens.next()
        // Dialog dismissed -> page cleared its token to null. A's late completion matches nothing.
        assertFalse(
            shouldDismiss(
                completion = SkillSaveTarget(SkillSaveOrigin.EDIT, tokenA),
                openEditToken = null,
                openAddToken = null,
            )
        )
    }

    @Test
    fun `SkillSaveTarget carries the origin captured at the call site`() {
        assertEquals(
            SkillSaveOrigin.EDIT,
            SkillSaveTarget(SkillSaveOrigin.EDIT, 0L).origin,
        )
        assertEquals(
            SkillSaveOrigin.ADD,
            SkillSaveTarget(SkillSaveOrigin.ADD, 0L).origin,
        )
    }
}
