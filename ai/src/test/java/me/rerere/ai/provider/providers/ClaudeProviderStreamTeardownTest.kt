package me.rerere.ai.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the Anthropic stream teardown decision in [claudeStreamFrameTerminal].
 *
 * The bug (issue #240): the `message_stop` branch in onEvent called `close()` and relied on a
 * trailing `return` to skip the `trySend(messageChunk)` below it — the sibling `error` branch did
 * the same. The terminal decision is now a single pure classification (`message_stop` / `error` ->
 * terminal, anything else -> null), and onEvent's `trySend(...)` runs ONLY on the `null`
 * (non-terminal) classification — so a terminal frame can never reach the send.
 *
 * The invariant: a frame whose `type` terminates the stream must NOT also send a content chunk.
 * These assertions are pure and network-free (no EventSource/callbackFlow, no android.util.Log on
 * this path), matching the documented :ai unit-test limitation in GoogleProviderResponseTest.
 */
class ClaudeProviderStreamTeardownTest {

    @Test
    fun `message_stop is classified terminal so the chunk after close is not sent`() {
        assertEquals(ClaudeStreamTerminal.MessageStop, claudeStreamFrameTerminal("message_stop"))
    }

    @Test
    fun `error frame is classified terminal`() {
        assertEquals(ClaudeStreamTerminal.Error, claudeStreamFrameTerminal("error"))
    }

    @Test
    fun `content_block_delta is non-terminal and emits`() {
        assertNull(claudeStreamFrameTerminal("content_block_delta"))
    }

    @Test
    fun `message_start is non-terminal and emits`() {
        assertNull(claudeStreamFrameTerminal("message_start"))
    }

    @Test
    fun `null and unknown frame types are non-terminal`() {
        assertNull(claudeStreamFrameTerminal(null))
        assertNull(claudeStreamFrameTerminal("ping"))
    }
}
