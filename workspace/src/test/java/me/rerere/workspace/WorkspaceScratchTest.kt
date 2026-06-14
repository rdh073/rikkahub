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
