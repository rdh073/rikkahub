package me.rerere.workspace

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T3 coverage for the filesystem-materializing [ensureDefaultScratch] (issue #282, milestone M1).
 * Same `@Test` + `runBlocking { checkAll(...) }` convention as [WorkspaceCwdPolicyTest]; this suite
 * touches the real filesystem (via JUnit's [TemporaryFolder]), so it is a small/medium test rather
 * than a pure one. Covers W-D2 (idempotence), W-B1 (default-create), W-B6 (never clobber a non-dir).
 */
class WorkspaceScratchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun scratchDir(filesDir: File): File =
        File(File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]), WorkspaceCwdPolicy.DEFAULT_SCRATCH[1])

    // ---- W-B1: absent cwd + unset row materializes `.xcloudz/scratch` under filesDir ----
    @Test
    fun `W-B1 ensureDefaultScratch creates the hidden scratch dir under filesDir`() {
        val filesDir = tmp.newFolder("files")

        val scratch = ensureDefaultScratch(filesDir)

        assertEquals(scratchDir(filesDir).canonicalFile, scratch.canonicalFile)
        assertTrue("scratch dir must exist", scratch.exists())
        assertTrue("scratch must be a directory", scratch.isDirectory)
        assertEquals(
            ".xcloudz/scratch",
            scratch.canonicalFile.relativeTo(filesDir.canonicalFile).path.replace(File.separatorChar, '/'),
        )
    }

    // ---- W-B1 (property): under any filesDir LOCATION, default-create yields the contained ----
    // `.xcloudz/scratch` directory. Generates an arbitrary (dot-dir-inclusive, via arbRelativePath)
    // location for an EXISTING filesDir — the helper's contract is a real app data dir (Context.filesDir),
    // not a not-yet-created path — so the helper never depends on filesDir being one specific path, and
    // the result is always the same contained scratch dir at the exact relative location.
    @Test
    fun `W-B1 property default-create yields the contained xcloudz scratch dir for any filesDir`() {
        runBlocking {
            checkAll(100, arbRelativePath()) { sub ->
                // An existing files dir at an arbitrary location (Context.filesDir always exists at call time).
                val filesDir = File(tmp.newFolder(), sub).apply { mkdirs() }

                val scratch = ensureDefaultScratch(filesDir)

                assertTrue("scratch dir exists", scratch.exists())
                assertTrue("scratch is a directory", scratch.isDirectory)
                assertEquals(scratchDir(filesDir).canonicalFile, scratch.canonicalFile)
                assertEquals(
                    ".xcloudz/scratch",
                    scratch.canonicalFile.relativeTo(filesDir.canonicalFile).path
                        .replace(File.separatorChar, '/'),
                )
            }
        }
    }

    // ---- W-D2: calling twice returns the SAME dir and preserves any tree already inside it ----
    @Test
    fun `W-D2 ensureDefaultScratch is idempotent and preserves the existing tree`() {
        val filesDir = tmp.newFolder("files")

        val first = ensureDefaultScratch(filesDir)
        // Seed a file inside the scratch dir; a second ensure call must not clobber it.
        val seeded = File(first, "keep.txt").apply { writeText("payload") }

        val second = ensureDefaultScratch(filesDir)

        assertEquals("twice returns the same dir", first.canonicalFile, second.canonicalFile)
        assertTrue("the existing tree is preserved", seeded.exists())
        assertEquals("payload", seeded.readText())
    }

    // ---- W-D2 (property): for an ARBITRARY nested tree, a second ensure preserves it byte-for-byte ----
    // The single-file example above only guards a top-level file; a wrong impl that `deleteRecursively()`d
    // the scratch dir before re-creating it would survive that example yet corrupt a real project tree.
    // This property seeds an arbitrary-depth file path and asserts the second ensure call is a pure no-op.
    @Test
    fun `W-D2 property a second ensure preserves an arbitrary nested tree and returns the same dir`() {
        runBlocking {
            checkAll(
                100,
                arbRelativePath(),
                Arb.string(0..16),
            ) { nested, payload ->
                val filesDir = tmp.newFolder()

                val first = ensureDefaultScratch(filesDir)
                // Materialize an arbitrary-depth file INSIDE the scratch dir, then ensure again.
                val seeded = File(first, "$nested/keep.txt").apply {
                    parentFile?.mkdirs()
                    writeText(payload)
                }

                val second = ensureDefaultScratch(filesDir)

                assertEquals("twice returns the same dir", first.canonicalFile, second.canonicalFile)
                assertTrue("the nested tree is preserved", seeded.exists())
                assertEquals("contents are preserved byte-for-byte", payload, seeded.readText())
            }
        }
    }

    // ---- W-B6: a NON-DIRECTORY at `.xcloudz` is never overwritten; fall back to files root ----
    @Test
    fun `W-B6 a file occupying xcloudz is never clobbered and the files root is returned`() {
        val filesDir = tmp.newFolder("files")
        val xcloudz = File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]).apply { writeText("not a dir") }

        val result = ensureDefaultScratch(filesDir)

        assertEquals("falls back to the files root", filesDir.canonicalFile, result.canonicalFile)
        assertTrue("the occupying file is preserved", xcloudz.exists())
        assertFalse("the occupying file is NOT turned into a directory", xcloudz.isDirectory)
        assertEquals("not a dir", xcloudz.readText())
    }

    // ---- W-B6: a NON-DIRECTORY at `.xcloudz/scratch` (with `.xcloudz` a real dir) is never clobbered ----
    @Test
    fun `W-B6 a file occupying xcloudz scratch is never clobbered and the files root is returned`() {
        val filesDir = tmp.newFolder("files")
        val xcloudz = File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0]).apply { mkdirs() }
        val scratchAsFile = File(xcloudz, WorkspaceCwdPolicy.DEFAULT_SCRATCH[1]).apply { writeText("not a dir") }

        val result = ensureDefaultScratch(filesDir)

        assertEquals("falls back to the files root", filesDir.canonicalFile, result.canonicalFile)
        assertTrue("the occupying file is preserved", scratchAsFile.exists())
        assertFalse("the occupying file is NOT turned into a directory", scratchAsFile.isDirectory)
        assertEquals("not a dir", scratchAsFile.readText())
    }

    // ---- W-B6 (property): for any pre-existing non-dir at either segment, never clobber ----
    @Test
    fun `W-B6 property never overwrites an existing non-directory at either scratch segment`() {
        runBlocking {
            checkAll(
                100,
                Arb.element("xcloudz", "scratch"),
                Arb.string(0..8),
            ) { occupy, payload ->
                val filesDir = tmp.newFolder()
                val xcloudz = File(filesDir, WorkspaceCwdPolicy.DEFAULT_SCRATCH[0])
                val blocker = if (occupy == "xcloudz") {
                    xcloudz.apply { writeText(payload) }
                } else {
                    xcloudz.mkdirs()
                    File(xcloudz, WorkspaceCwdPolicy.DEFAULT_SCRATCH[1]).apply { writeText(payload) }
                }

                val result = ensureDefaultScratch(filesDir)

                assertEquals(filesDir.canonicalFile, result.canonicalFile)
                assertTrue("blocker preserved", blocker.exists())
                assertFalse("blocker is still a non-directory", blocker.isDirectory)
                assertEquals(payload, blocker.readText())
            }
        }
    }
}
