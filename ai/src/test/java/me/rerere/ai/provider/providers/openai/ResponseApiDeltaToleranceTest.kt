package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression test for ResponseAPI.parseResponseDelta tolerance (issue #241).
 *
 * Bug: ResponseAPI.onEvent calls parseResponseDelta(json) with NO try/catch, so every error()
 * inside it propagates straight out of onEvent and kills the OpenAI Responses-API stream. A frame
 * whose `type` is absent (`error("chunk type not found")`) or whose `type` is a future
 * `response.*` event must degrade to null (skip the frame) rather than throw.
 *
 * parseResponseDelta is `internal`, so it is called directly from this same-module test.
 */
class ResponseApiDeltaToleranceTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `frame with absent type degrades to null instead of throwing`() {
        // Previously error("chunk type not found") -> propagated out of onEvent, killing the stream.
        assertNull(api.parseResponseDelta(buildJsonObject { put("foo", "bar") }))
    }

    @Test
    fun `unknown future response event degrades to null`() {
        val frame = buildJsonObject {
            put("type", "response.some_future_event")
        }
        assertNull(api.parseResponseDelta(frame))
    }
}
