package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Regression test for issue #92: [ChatService.getConversationJobs] feeds a `stateIn(...)` in ChatVM
 * without a terminal `.catch`, so an upstream throw in the combine/flatMapLatest body would crash the
 * StateFlow's collection coroutine instead of being handled.
 *
 * The terminal-error policy lives in the extracted pure operator [catchConversationJobsErrors], the
 * same operator the production [ChatService.getConversationJobs] applies. Driving that operator with a
 * fake source flow exercises the production wiring, not a hand-rolled copy.
 *
 * Invariant guarded: a real upstream failure must NOT escape the flow (the per-conversation job
 * StateFlow must stay alive and emit the identity value [emptyMap]), while a [CancellationException]
 * must still propagate so structured-concurrency teardown is never swallowed.
 */
class ConversationJobsErrorHandlingTest {

    @Test
    fun `upstream throw is caught and replaced with empty map (stream survives)`() {
        val id = Uuid.random()
        val source = flow<Map<Uuid, Job?>> {
            emit(mapOf(id to null))
            throw IOException("combine boom")
        }
        // On the UNFIXED getConversationJobs (no terminal catch), the IOException propagates out of
        // toList() and this line throws — the test fails for that exact reason.
        val collected = runBlocking { source.catchConversationJobsErrors().toList() }
        assertEquals(
            "stream must survive the upstream throw and emit the identity value last",
            emptyMap<Uuid, Job?>(),
            collected.last()
        )
    }

    @Test
    fun `cancellation is re-thrown not converted to empty map`() {
        val source = flow<Map<Uuid, Job?>> {
            throw CancellationException("cancelled")
        }
        var rethrown = false
        try {
            runBlocking { source.catchConversationJobsErrors().toList() }
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue("CancellationException must propagate, never be swallowed", rethrown)
    }
}
