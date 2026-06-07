package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Pure-JVM regression test for [runEmitting], the cancellation-vs-error event classifier that backs
 * [launchEmitting]. The VMs it serves (SkillsVM / SkillDetailVM) used to deliver results through a
 * Composable-captured `onResult` callback after long IO work; a disposed screen left the VM calling a
 * stale lambda. The fix routes results through a one-shot SharedFlow collected with lifecycle.
 *
 * The behavioral invariant this guards — and the exact reason a test on the old code would fail — is:
 *   - success: [block] emits its own success event (here a literal sealed event).
 *   - non-cancellation failure: the escaping throwable becomes a failure event via onError.
 *   - cancellation: rethrown, and NO failure event is emitted (teardown is not a failed operation).
 *
 * Before this change there was no `runEmitting` seam at all and the import/save catch turned a
 * CancellationException-shaped failure into an `onResult(false, ...)` UI report; the cancellation case
 * below pins that this can no longer happen.
 *
 * runEmitting is exercised directly (not through viewModelScope) precisely so the test needs no
 * coroutines-test / Robolectric — viewModelScope binds Dispatchers.Main which is unavailable on the
 * plain JVM. launchEmitting is a one-line `viewModelScope.launch { runEmitting(...) }` wrapper over it.
 */
class RunEmittingEventTest {

    private sealed interface Event {
        data class Done(val name: String) : Event
        data class Failed(val message: String) : Event
    }

    private class JobCancelled : CancellationException("job cancelled")

    private fun newEvents() = MutableSharedFlow<Event>(extraBufferCapacity = 1)

    @Test
    fun `success path emits the block's own event`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield() // let the collector subscribe before any emission
            runEmitting(
                events = events,
                onError = { Event.Failed(it.message ?: "unknown") },
            ) {
                events.tryEmit(Event.Done("my-skill"))
            }
            yield()
            collector.cancel()
        }
        assertEquals(listOf(Event.Done("my-skill")), collected)
    }

    @Test
    fun `non-cancellation failure emits a failure event via onError`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield()
            runEmitting(
                events = events,
                onError = { Event.Failed(it.message ?: "unknown") },
            ) {
                throw IOException("disk full")
            }
            yield()
            collector.cancel()
        }
        assertEquals(listOf(Event.Failed("disk full")), collected)
    }

    @Test
    fun `cancellation rethrows and emits no failure event`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        var rethrown = false
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield()
            try {
                runEmitting(
                    events = events,
                    onError = { Event.Failed("should never be emitted on cancellation") },
                ) {
                    throw JobCancelled()
                }
            } catch (e: CancellationException) {
                rethrown = true
            }
            yield()
            collector.cancel()
        }
        assertTrue("CancellationException must propagate, not be swallowed", rethrown)
        assertTrue("cancellation must not emit a failure event", collected.isEmpty())
    }

    @Test
    fun `bare CancellationException also rethrows without a failure event`() = runBlocking {
        val events = newEvents()
        val collected = mutableListOf<Event>()
        coroutineScope {
            val collector = launch { events.collect { collected.add(it) } }
            yield()
            try {
                runEmitting(
                    events = events,
                    onError = { Event.Failed("nope") },
                ) {
                    throw CancellationException()
                }
                fail("expected CancellationException to propagate")
            } catch (e: CancellationException) {
                // expected
            }
            yield()
            collector.cancel()
        }
        assertTrue(collected.isEmpty())
    }
}
