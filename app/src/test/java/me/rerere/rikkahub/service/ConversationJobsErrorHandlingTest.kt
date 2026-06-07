package me.rerere.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Regression test for issue #92: [ChatService.getConversationJobs] feeds a `stateIn(...)` in ChatVM.
 * The flow is `_sessionsVersion.flatMapLatest { combine(...) }`; an upstream throw in a per-version
 * inner combine must NOT permanently kill the StateFlow's collection coroutine.
 *
 * These tests drive [assembleConversationJobsFlow] — the exact `version.flatMapLatest { inner()
 * .catchConversationJobsErrors() }` assembly that production [ChatService.getConversationJobs] uses —
 * with a fake version source and inner-flow factory.
 *
 * Invariant guarded (recovery, not terminal fallback): after one version's inner flow throws, the
 * outer flow stays alive and a SUBSEQUENT version bump with a working inner flow is still observed by
 * the collector. A [CancellationException] must still propagate so structured-concurrency teardown is
 * never swallowed.
 */
class ConversationJobsErrorHandlingTest {

    @Test
    fun `after a version inner-flow throws, a later version bump is still observed (recovery)`() {
        val liveId = Uuid.random()
        val liveJob: Job = Job()

        // Version source emits two versions; version 0's inner flow throws after one emission,
        // version 1's inner flow is healthy. flatMapLatest switches to version 1 once it arrives.
        // If catch were terminal on the OUTER flow, version 0's throw would complete the whole flow
        // and version 1's live map would NEVER be observed. Catch on the INNER flow keeps the outer
        // flatMapLatest alive so the version-1 emission is reached — that is the recovery invariant.
        val version = flow {
            emit(0L)
            emit(1L)
        }
        val combined = assembleConversationJobsFlow(version) { v ->
            when (v) {
                0L -> flow {
                    emit(emptyMap<Uuid, Job?>())
                    throw IOException("combine boom")
                }
                else -> flowOf(mapOf(liveId to liveJob))
            }
        }

        // toList() drains to natural completion (version source is finite, version 1's inner flow
        // completes after emitting). On the buggy terminal-outer-catch wiring the flow would complete
        // at version 0's throw and liveMap would be absent; here it must be present.
        val observed = runBlocking { combined.toList() }

        assertTrue(
            "outer flow must survive the inner throw and reach the post-failure live emission",
            observed.any { it == mapOf(liveId to liveJob) }
        )
    }

    @Test
    fun `inner throw is converted to empty map, not propagated out`() {
        val combined = assembleConversationJobsFlow(flowOf(0L)) {
            flow {
                emit(mapOf(Uuid.random() to (null as Job?)))
                throw IOException("combine boom")
            }
        }
        // Emissions: the real map, then the emptyMap fallback in place of the throw. The IOException
        // must NOT escape toList(); the fallback identity value is emitted instead of propagating.
        val emitted = runBlocking { combined.toList() }
        assertEquals(
            "inner throw must be replaced by the identity value, never propagated",
            emptyMap<Uuid, Job?>(),
            emitted.last()
        )
    }

    @Test
    fun `cancellation is re-thrown not converted to empty map`() {
        // Drive catchConversationJobsErrors directly: its contract is that a CancellationException is
        // re-thrown (structured-concurrency teardown never swallowed), distinct from a real error which
        // it converts to emptyMap. Tested at the operator layer where that policy lives.
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
