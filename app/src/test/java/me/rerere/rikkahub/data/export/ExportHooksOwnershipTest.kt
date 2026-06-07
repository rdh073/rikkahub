package me.rerere.rikkahub.data.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Regression for #88: a file export/import must NOT be cancelled by leaving the screen.
 *
 * The bug: [rememberExporter]/[rememberImporter] bound the write/read coroutine to
 * `rememberCoroutineScope()`, whose Job is cancelled on composition disposal. Navigating away
 * mid-export tore down that scope, so [writeExportBytes] never flushed and the user's chosen file
 * was left empty — silent data loss with no error surfaced.
 *
 * The fix moves the post-URI work onto the application-wide AppScope (SupervisorJob, outlives
 * composition). These tests pin the ownership invariant via the pure [writeExportBytes] seam so it
 * runs on the JVM (testDebugUnitTest) without a ContentResolver or Android.
 */
class ExportHooksOwnershipTest {

    private val content = "{\"hello\":\"world\"}"

    /** Pre-fix model: write launched as a CHILD of the composition scope. Disposal loses the write. */
    @Test
    fun `write bound to caller scope is lost when that scope is cancelled`() = runBlocking {
        val composition = CoroutineScope(Job() + Dispatchers.Default)
        val sink = ByteArrayOutputStream()

        val writeJob = composition.launch { writeExportBytes(content, sink) }
        composition.cancel() // user navigates away before the IO completes
        writeJob.join()

        assertEquals("cancelled composition scope must drop the write", 0, sink.size())
    }

    /** Post-fix model: write launched on an independent owner scope (AppScope stand-in). Survives. */
    @Test
    fun `write launched on owner scope survives caller cancellation`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default) // AppScope stand-in
        val caller = CoroutineScope(Job() + Dispatchers.Default)
        val sink = ByteArrayOutputStream()

        // The caller only triggers the work; the work's lifetime is the owner scope.
        val ownerJob = owner.launch { writeExportBytes(content, sink) }
        caller.launch { /* triggering UI action */ }
        caller.cancel() // user navigates away
        ownerJob.join()

        assertArrayEquals(
            "owner-scoped write must complete despite caller cancellation",
            content.toByteArray(),
            sink.toByteArray()
        )
    }
}
