package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression guard for issue #100: the persisted crash trace (surfaced/copyable via
// SafeModeActivity) must not retain raw exception MESSAGE content. The previous
// implementation used throwable.stackTraceToString(), whose per-exception toString()
// line is "class: message" — so a message embedding a secret (api key, file path, a
// JsonDecodingException's offending input) leaked into the persisted, exported buffer.
// buildRedactedStackTrace keeps the FRAMES (class/method/line) but strips the message.
class CrashHandlerStackTraceRedactionTest {

    @Test
    fun `built trace strips the exception message but keeps class and frames`() {
        val ex = RuntimeException("failed: api_key=sk-LIVE-deadbeef path=/home/user/secret")

        val trace = CrashHandler.buildRedactedStackTrace("main", ex)

        // FAILS on stackTraceToString() (message present); PASSES after redaction.
        assertFalse("secret token must not persist", trace.contains("sk-LIVE-deadbeef"))
        assertFalse("file path must not persist", trace.contains("/home/user/secret"))
        assertFalse("raw message must not persist", trace.contains("api_key="))

        assertTrue("class identity kept for triage", trace.contains("RuntimeException"))
        assertTrue("at least one stack frame kept", trace.contains("\tat "))
        assertTrue("thread name kept", trace.contains("Thread: main"))
    }

    @Test
    fun `cause chain message is also stripped`() {
        val cause = IllegalStateException("inner secret token=gho_innerLEAK")
        val ex = RuntimeException("outer secret api_key=sk-OUTER-leak", cause)

        val trace = CrashHandler.buildRedactedStackTrace("worker-3", ex)

        assertFalse(trace.contains("gho_innerLEAK"))
        assertFalse(trace.contains("sk-OUTER-leak"))
        assertTrue("cause class identity kept", trace.contains("IllegalStateException"))
        assertTrue("cause section labelled", trace.contains("Caused by"))
    }
}
