package me.rerere.rikkahub.ui.pages.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for [skillSaveTarget], which routes a completed save to the dialog that
 * started it. The bug: a single identity-less SaveDone closed BOTH the edit dialog and the add-file
 * dialog. Race — start an edit save, dismiss it, open Add File, then the in-flight edit's SaveDone
 * arrives and wrongly dismisses the add-file dialog the user is filling in.
 *
 * The fix carries the saved relativePath on the event; this function decides whose dialog to close.
 * A test against the old behavior (always close both) fails the cross-routing case below.
 */
class SkillSaveTargetTest {

    @Test
    fun `edit-save routes to the edit dialog when it owns the saved path`() {
        assertEquals(
            SkillSaveTarget.EDIT,
            skillSaveTarget(savedRelativePath = "SKILL.md", editingRelativePath = "SKILL.md"),
        )
    }

    @Test
    fun `add-file save routes to the add dialog when no edit dialog is open`() {
        assertEquals(
            SkillSaveTarget.ADD,
            skillSaveTarget(savedRelativePath = "examples/basic.md", editingRelativePath = null),
        )
    }

    @Test
    fun `in-flight edit save does not route to a freshly opened add dialog`() {
        // User started editing examples/basic.md, dismissed it, then opened Add File (editingRelativePath
        // is now null). The in-flight edit's SaveDone for examples/basic.md must NOT close the add dialog.
        assertEquals(
            SkillSaveTarget.ADD,
            skillSaveTarget(savedRelativePath = "examples/basic.md", editingRelativePath = null),
        )
    }

    @Test
    fun `add-file save while a different file is being edited does not close the edit dialog`() {
        assertEquals(
            SkillSaveTarget.ADD,
            skillSaveTarget(savedRelativePath = "new.md", editingRelativePath = "SKILL.md"),
        )
    }
}
