package me.rerere.rikkahub.data.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillImportLimitsTest {
    @Test
    fun `readBytesLimited reads small streams unchanged`() {
        val input = ByteArray(1024) { (it % 251).toByte() }

        val result = SkillImportLimits.readBytesLimited(
            ByteArrayInputStream(input),
            max = 4096,
            label = "test",
        )

        assertArrayEquals(input, result)
    }

    @Test
    fun `readBytesLimited throws when stream exceeds cap without buffering everything`() {
        // An effectively unbounded stream: on the unfixed code path
        // (InputStream.readBytes()) this would allocate without limit and never
        // throw for size. The cap must abort early.
        val infinite = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = 0
                return len
            }
        }

        assertThrows(SkillImportLimitException::class.java) {
            SkillImportLimits.readBytesLimited(infinite, max = 64 * 1024, label = "bomb")
        }
    }

    @Test
    fun `readBytesLimited throws exactly at boundary plus one`() {
        val cap = 8 * 1024L
        val atCap = ByteArray(cap.toInt())
        val overCap = ByteArray(cap.toInt() + 1)

        // At the cap: allowed.
        val ok = SkillImportLimits.readBytesLimited(
            ByteArrayInputStream(atCap),
            max = cap,
            label = "boundary",
        )
        assertArrayEquals(atCap, ok)

        // One byte over: rejected.
        assertThrows(SkillImportLimitException::class.java) {
            SkillImportLimits.readBytesLimited(
                ByteArrayInputStream(overCap),
                max = cap,
                label = "boundary",
            )
        }
    }

    @Test
    fun `checkTotalAndCount throws past entry count`() {
        SkillImportLimits.checkTotalAndCount(currentTotal = 0, currentCount = SkillImportLimits.MAX_ENTRY_COUNT)

        assertThrows(SkillImportLimitException::class.java) {
            SkillImportLimits.checkTotalAndCount(
                currentTotal = 0,
                currentCount = SkillImportLimits.MAX_ENTRY_COUNT + 1,
            )
        }
    }

    @Test
    fun `checkTotalAndCount throws past total uncompressed bytes`() {
        SkillImportLimits.checkTotalAndCount(
            currentTotal = SkillImportLimits.MAX_TOTAL_UNCOMPRESSED_BYTES,
            currentCount = 1,
        )

        assertThrows(SkillImportLimitException::class.java) {
            SkillImportLimits.checkTotalAndCount(
                currentTotal = SkillImportLimits.MAX_TOTAL_UNCOMPRESSED_BYTES + 1,
                currentCount = 1,
            )
        }
    }

    // Mirrors SkillsVM.normalizeZipEntryPath: rejects traversal / empty / dot-only
    // names by returning null (the entry is then dropped after being counted/sized).
    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `scanZipEntries counts and size-checks entries under rejected paths`() {
        // A bomb-shaped entry whose name normalizeZipEntryPath rejects (`../bomb`).
        // On the unfixed code the size/count guards lived inside the accepted-path
        // branch, so this entry was never read through readBytesLimited nor counted —
        // closeEntry() still fully inflated it (CPU-runaway DoS). The fix counts and
        // size-checks EVERY non-directory entry, so the per-entry cap fires here.
        val oversized = ByteArray((SkillImportLimits.MAX_ENTRY_BYTES + 1).toInt())
        val zip = zipOf("../bomb" to oversized)

        assertThrows(SkillImportLimitException::class.java) {
            ZipInputStream(ByteArrayInputStream(zip)).use {
                SkillImportLimits.scanZipEntries(it, ::normalizeZipEntryPath)
            }
        }
    }

    @Test
    fun `scanZipEntries drops rejected-path entries but keeps valid ones`() {
        val zip = zipOf(
            "../escape.txt" to "nope".toByteArray(),
            "skill/SKILL.md" to "ok".toByteArray(),
        )

        val files = ZipInputStream(ByteArrayInputStream(zip)).use {
            SkillImportLimits.scanZipEntries(it, ::normalizeZipEntryPath)
        }

        assertEquals(setOf("skill/SKILL.md"), files.keys)
    }

    @Test
    fun `traverseGitHubTree counts directories not just downloaded files`() {
        // A shallow repo with massive directory fan-out at the root and ZERO files:
        // the root lists more sub-directories than the entry cap, and each sub-directory
        // is empty (so recursion returns immediately — depth stays shallow, the depth
        // cap is never the cause). On the unfixed code only `result.size` (downloaded
        // files) was checked; it stayed 0, so the count cap never fired and the walk
        // would issue one API request per sub-directory, unbounded. The fix counts every
        // visited entry (file AND dir), so the count cap fires on the root listing.
        val fanOut = SkillImportLimits.MAX_ENTRY_COUNT + 1
        val fetchDir: (String) -> List<SkillImportLimits.GitHubEntry>? = { dir ->
            if (dir.isEmpty()) {
                (0 until fanOut).map { i ->
                    SkillImportLimits.GitHubEntry("$i", isDir = true, downloadUrl = null)
                }
            } else {
                emptyList()
            }
        }

        assertThrows(SkillImportLimitException::class.java) {
            SkillImportLimits.traverseGitHubTree(
                dirPath = "",
                basePath = "",
                result = mutableListOf(),
                visited = intArrayOf(0),
                depth = 0,
                fetchDir = fetchDir,
            )
        }
    }

    @Test
    fun `traverseGitHubTree collects files within limits`() {
        val fetchDir: (String) -> List<SkillImportLimits.GitHubEntry>? = { dir ->
            when (dir) {
                "" -> listOf(
                    SkillImportLimits.GitHubEntry("SKILL.md", isDir = false, downloadUrl = "https://x/SKILL.md"),
                    SkillImportLimits.GitHubEntry("sub", isDir = true, downloadUrl = null),
                )
                "sub" -> listOf(
                    SkillImportLimits.GitHubEntry("sub/a.txt", isDir = false, downloadUrl = "https://x/a.txt"),
                )
                else -> emptyList()
            }
        }
        val result = mutableListOf<Pair<String, String>>()

        val ok = SkillImportLimits.traverseGitHubTree(
            dirPath = "",
            basePath = "",
            result = result,
            visited = intArrayOf(0),
            depth = 0,
            fetchDir = fetchDir,
        )

        assertTrue(ok)
        assertEquals(listOf("SKILL.md", "sub/a.txt"), result.map { it.first })
    }
}
