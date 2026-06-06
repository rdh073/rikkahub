package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic test for [shouldPublishStreamingUpdate], the throttle predicate that decides whether a
 * streaming chunk should be written to the UI-facing conversation StateFlow. Keeping the decision
 * pure lets it run on the JVM without any Android / coroutine machinery, exactly like
 * [shouldRenewWakeLock] and its [WakeLockRenewTest].
 *
 * The invariant this guards (issue #108): for a fast SSE token stream, the whole-conversation UI
 * StateFlow must NOT be rewritten on every chunk (each rewrite triggers full recomposition + derived
 * work), yet the final merged state must NEVER be dropped. The canonical merged conversation is
 * computed per chunk regardless; only the StateFlow publish is gated, with a mandatory final flush.
 *
 * The replay model below drives BOTH production pure functions exactly as
 * [ChatService.handleMessageComplete] does: the per-chunk publish gate [shouldPublishStreamingUpdate]
 * AND the terminal flush decision [shouldFlushFinalStreamingUpdate]. Because the model calls the real
 * flush predicate (not a hand-rolled `lastPublishedValue != v` copy), deleting or weakening the
 * production flush — e.g. making it always-false or dropping the `!lastChunkPublished` guard — turns
 * these tests red. The "naive sample" model (gate only, no flush) is included to prove why the issue's
 * named anti-pattern drops the final state.
 */
class StreamingUiCoalesceTest {

    private val interval = STREAMING_UI_COALESCE_INTERVAL_MS

    /**
     * Replays a chunk stream through the production gate ([shouldPublishStreamingUpdate]) plus the
     * production terminal flush predicate ([shouldFlushFinalStreamingUpdate]) and records every UI
     * publish. [times] is the per-chunk arrival clock (epoch-style millis); [values] is the merged
     * value the chunk would carry (here an Int standing in for the merged Conversation).
     *
     * Mirrors the production loop variables 1:1: lastChunkMessages -> lastValue,
     * lastChunkPublished -> lastChunkPublished.
     */
    private fun publishesWithFlush(times: List<Long>, values: List<Int>): List<Int> {
        require(times.size == values.size)
        val published = mutableListOf<Int>()
        var lastPublishAt = 0L
        var lastValue: Int? = null
        var lastChunkPublished = false
        times.forEachIndexed { i, now ->
            val v = values[i]
            lastValue = v // canonical merged value is remembered every chunk
            if (shouldPublishStreamingUpdate(lastPublishAt, now)) {
                lastPublishAt = now
                lastChunkPublished = true
                published.add(v)
            } else {
                lastChunkPublished = false
            }
        }
        // Terminal flush, gated by the production predicate (not a hand-rolled comparison).
        if (shouldFlushFinalStreamingUpdate(lastValue != null, lastChunkPublished)) {
            lastValue?.let { published.add(it) }
        }
        return published
    }

    /** The issue's named anti-pattern: gate only, NO final flush. Used to prove it drops the tail. */
    private fun publishesNaiveSample(times: List<Long>, values: List<Int>): List<Int> {
        val published = mutableListOf<Int>()
        var lastPublishAt = 0L
        times.forEachIndexed { i, now ->
            if (shouldPublishStreamingUpdate(lastPublishAt, now)) {
                lastPublishAt = now
                published.add(values[i])
            }
        }
        return published
    }

    @Test
    fun `first chunk publishes immediately (lastPublishAt = 0)`() {
        // lastPublishAt == 0 means "never published". In production `now` is System.currentTimeMillis()
        // (epoch millis, far larger than the window), so the first token shows with no startup latency.
        val epochNow = System.currentTimeMillis()
        assertTrue(shouldPublishStreamingUpdate(lastPublishAt = 0L, now = epochNow))
    }

    @Test
    fun `within window does not publish (drops intermediate UI writes)`() {
        val last = 1_000_000L
        assertFalse(shouldPublishStreamingUpdate(last, last + 1))
        assertFalse(shouldPublishStreamingUpdate(last, last + interval - 1))
    }

    @Test
    fun `at or past window publishes`() {
        val last = 1_000_000L
        assertTrue(shouldPublishStreamingUpdate(last, last + interval))
        assertTrue(shouldPublishStreamingUpdate(last, last + interval + 1))
    }

    @Test
    fun `coalesces a fast burst into far fewer UI publishes`() {
        // 50 chunks arriving 1 ms apart, all inside a few coalesce windows. The unfixed per-chunk
        // publish writes the StateFlow 50 times; the gate must publish far fewer.
        val base = 1_000_000L
        val n = 50
        val times = (0 until n).map { base + it.toLong() } // +1ms spacing
        val values = (0 until n).toList()
        val published = publishesWithFlush(times, values)
        assertTrue(
            "expected far fewer than $n publishes, got ${published.size}",
            published.size < n / 2
        )
    }

    @Test
    fun `final value is always flushed even when it lands inside the throttle window`() {
        // Burst whose last chunk lands well inside the window after the previous publish, so the gate
        // alone would not publish it. The mandatory flush must publish the LAST merged value.
        val base = 1_000_000L
        val n = 50
        val times = (0 until n).map { base + it.toLong() }
        val values = (0 until n).toList()

        val withFlush = publishesWithFlush(times, values)
        assertEquals("final merged value must be the last published", values.last(), withFlush.last())

        // Prove why naive sample() (the issue's named anti-pattern) is wrong: it drops the final value.
        val naive = publishesNaiveSample(times, values)
        assertFalse(
            "naive gate-only must NOT publish the final value (this is the bug to avoid)",
            naive.last() == values.last()
        )
    }

    @Test
    fun `published values are monotonic - never stale`() {
        // Each merged value is strictly newer than the previous (here a monotonically increasing Int).
        // No publish may carry an older value than a previously published one.
        val base = 1_000_000L
        val times = (0 until 200).map { base + it.toLong() }
        val values = (0 until 200).toList()
        val published = publishesWithFlush(times, values)
        for (i in 1 until published.size) {
            assertTrue(
                "publish must never go backwards: ${published[i]} after ${published[i - 1]}",
                published[i] > published[i - 1]
            )
        }
    }

    @Test
    fun `slow stream publishes every chunk (no coalescing when spacing exceeds the window)`() {
        // When chunks are spaced wider than the window, every chunk publishes — coalescing only kicks
        // in for fast bursts, so a slow stream is unaffected and the flush adds no duplicate.
        val base = 1_000_000L
        val n = 10
        val times = (0 until n).map { base + it.toLong() * (interval + 5L) }
        val values = (0 until n).toList()
        val published = publishesWithFlush(times, values)
        assertEquals(values, published)
    }

    @Test
    fun `coalesce window sits in the issue's suggested 16-50 ms band`() {
        // The window must be small enough to feel real-time yet large enough to cut per-token writes.
        assertTrue(STREAMING_UI_COALESCE_INTERVAL_MS in 16L..50L)
    }

    // ---- Terminal flush decision: directly covers the #108 fix (onCompletion force-flush) ----

    @Test
    fun `flushes when the last chunk was throttled (not yet published)`() {
        // The bug to guard: a final chunk that landed inside the throttle window was remembered but
        // never written to the StateFlow. The terminal flush MUST fire so the final state is not lost.
        assertTrue(shouldFlushFinalStreamingUpdate(hasLastMessages = true, lastChunkPublished = false))
    }

    @Test
    fun `does not flush when the last chunk was already published`() {
        // Slow stream / wide spacing: the final chunk crossed the window and was already published.
        // Re-flushing would be a redundant duplicate StateFlow write — must not fire.
        assertFalse(shouldFlushFinalStreamingUpdate(hasLastMessages = true, lastChunkPublished = true))
    }

    @Test
    fun `does not flush when no chunk was ever received`() {
        // Empty stream (immediate cancel / no tokens): nothing remembered, nothing to flush.
        assertFalse(shouldFlushFinalStreamingUpdate(hasLastMessages = false, lastChunkPublished = false))
        assertFalse(shouldFlushFinalStreamingUpdate(hasLastMessages = false, lastChunkPublished = true))
    }
}
