package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the workspace-tools approval gate (issue #197 slice 5, ws-tools).
 *
 * The load-bearing security control is [resolveWorkspaceToolApproval]: it decides whether a given
 * workspace tool must be confirmed by the user before it runs. The destructive tools
 * (shell, delete, move) MUST default to approval-required; the read/inspect tools
 * (list, read, write, edit) default to no-approval. A per-workspace override always wins over the
 * default — in either direction.
 *
 * This pins the pure resolution function directly. The 7 tools' execute bodies and
 * [createWorkspaceTools]' Koin/Android wiring are SettingsStore/WorkspaceManager-coupled and not
 * unit-testable in pure JVM (no Robolectric/mockk on the :app unit-test classpath) — the same
 * constraint and precedent as McpToolsByAssistantTest, which tests its pure seam
 * (selectMcpToolsForAssistant) rather than the Android-coupled manager. The null/blank short-circuit
 * in [createWorkspaceTools] (an unbound assistant exposes zero tools) is a trivial early-return
 * verified by compile + the integration path; it is not exercised here because it cannot be without
 * either an unchecked null cast (forbidden) or a real WorkspaceRepository (unconstructible in CI).
 *
 * FAIL-BEFORE rationale: each assertion pins a specific invariant. If the default map were ever
 * weakened (e.g. shell flipped to false, the regression this slice's scope guard forbids), (A)
 * fails. If override precedence were inverted, (B) fails. These are the controls that keep a
 * dormant-but-dangerous tool surface gated.
 */
class WorkspaceToolsTest {

    // (A) Defaults: destructive tools require approval; read/inspect tools do not.
    @Test
    fun `default approval requires confirmation for destructive tools only`() {
        // Destructive — must default to approval-required.
        assertTrue(resolveWorkspaceToolApproval("workspace_shell", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_delete_file", emptyMap()))
        assertTrue(resolveWorkspaceToolApproval("workspace_move_file", emptyMap()))

        // Read / inspect — default to no approval.
        assertFalse(resolveWorkspaceToolApproval("workspace_list_files", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_read_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_write_file", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("workspace_edit_file", emptyMap()))
    }

    // (A) Unknown tools fall through to the final `?: false` (no approval, no crash).
    @Test
    fun `unknown tool defaults to no approval`() {
        assertFalse(resolveWorkspaceToolApproval("unknown_tool", emptyMap()))
    }

    // (B) A per-workspace override wins over the default — in both directions.
    @Test
    fun `override wins over default in both directions`() {
        // Override relaxes a dangerous default.
        assertFalse(
            resolveWorkspaceToolApproval("workspace_shell", mapOf("workspace_shell" to false))
        )
        // Override tightens a safe default.
        assertTrue(
            resolveWorkspaceToolApproval("workspace_read_file", mapOf("workspace_read_file" to true))
        )
    }

    // (B) An override for one tool does not leak to another.
    @Test
    fun `override does not leak across tools`() {
        assertTrue(
            resolveWorkspaceToolApproval("workspace_delete_file", mapOf("workspace_shell" to false))
        )
    }
}
