package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the workspace per-tool approval merge (issue #197 slice 6a, finding #5 —
 * the lost-update guard the read-modify-write transaction protects).
 *
 * The invariant: writing one tool's approval policy must MERGE onto the row's current policy, never
 * replace it. In slice 6a the per-tool approval toggles and rename become multi-writer (two UI
 * writers can interleave), so [WorkspaceRepository.setToolApproval] does a read-modify-write that
 * is now wrapped in `db.withTransaction { ... }`: the read and the dependent write are one SQLite
 * transaction, so concurrent writers serialize instead of clobbering. [mergeToolApprovalOverride]
 * is the pure merge the transaction re-runs against the freshly-read row — pinning it pins
 * "an existing key survives a merge of a different key", which is exactly the lost-update class:
 * a stale in-memory snapshot that dropped a concurrently-written key would FAIL the second case
 * below (the second key would be absent after a non-merging replace).
 *
 * FAIL-BEFORE rationale (mirrors WorkspaceRepositoryShellEnableTest): on the unfixed code this test
 * does not even compile — [mergeToolApprovalOverride] does not exist; the merge lived inlined in
 * setToolApproval, outside any transaction boundary.
 *
 * NOTE on the live surface (mirrors the two sibling workspace tests): the full
 * `withTransaction` concurrency property needs an in-memory Room database, and
 * `androidx.room.testing` is `androidTestImplementation` only — NOT run by CI's
 * `testSideloadDebugUnitTest`/`testDebugUnitTest`. Per the in-repo precedent
 * (WorkspaceRepositoryShellEnableTest pinning `isShellRunnable`, WorkspaceToolsTest pinning
 * `resolveWorkspaceToolApproval`) the invariant the transaction protects is unit-tested at the pure
 * seam; the transaction itself is verified by compile + the existing repository tests still passing.
 */
class WorkspaceToolApprovalMergeTest {

    private fun decode(json: String): Map<String, Boolean> =
        JsonInstant.decodeFromString(json)

    // (1) merging into an empty '{}' yields a single-entry map JSON.
    @Test
    fun `merge into empty yields single entry`() {
        val result = mergeToolApprovalOverride("{}", "workspace_shell", needsApproval = true)
        val map = decode(result)
        assertEquals(mapOf("workspace_shell" to true), map)
    }

    // (2) merging a SECOND distinct tool PRESERVES the first entry. This is the lost-update class:
    // a stale snapshot that dropped the concurrently-written first key would leave it absent here.
    @Test
    fun `merging a second tool preserves the first key`() {
        val first = mergeToolApprovalOverride("{}", "workspace_shell", needsApproval = true)
        val second = mergeToolApprovalOverride(first, "workspace_write_file", needsApproval = false)
        val map = decode(second)
        // BOTH keys must be present with their correct values.
        assertEquals(2, map.size)
        assertEquals(true, map["workspace_shell"])
        assertEquals(false, map["workspace_write_file"])
    }

    // (3) re-setting an existing key overwrites only that key, leaving siblings untouched.
    @Test
    fun `re-setting an existing key overwrites only that key`() {
        val base = mergeToolApprovalOverride(
            mergeToolApprovalOverride("{}", "workspace_shell", needsApproval = true),
            "workspace_write_file",
            needsApproval = true,
        )
        val updated = mergeToolApprovalOverride(base, "workspace_shell", needsApproval = false)
        val map = decode(updated)
        assertEquals(2, map.size)
        assertEquals(false, map["workspace_shell"])
        // sibling untouched
        assertEquals(true, map["workspace_write_file"])
    }

    // (4) a corrupt/garbage existing blob starts from {} and still produces a valid one-entry JSON
    // (matches the documented fail-open-on-corrupt repair semantics: the user is explicitly setting
    // one tool's policy, so we repair the column rather than fail).
    @Test
    fun `corrupt existing blob repairs to a single-entry map`() {
        val result = mergeToolApprovalOverride("not-json-at-all", "workspace_read_file", needsApproval = true)
        val map = decode(result)
        assertEquals(mapOf("workspace_read_file" to true), map)
        assertTrue(map.containsKey("workspace_read_file"))
    }
}
