package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the streaming-image SSE wire-shape parse in [parseImageStreamEvent].
 *
 * The OpenAI image endpoints now stream partial previews + a final frame over SSE
 * (`image_generation.partial_image` / `.completed`, and the `image_edit.*` mirror for edits).
 * The parser is the single point that decides, per SSE frame:
 *   - whether the frame is a partial preview or the finalized image (`partial` flag),
 *   - which preview slot the partial belongs to (`partialImageIndex`),
 *   - the image mime type from `output_format`,
 *   - and that unrelated / payload-less frames are dropped (returned null) rather than
 *     surfaced as empty images to the UI.
 *
 * These are pure, network-free assertions: the EventSource/callbackFlow wiring is left to the
 * existing SSE infra; this test pins ONLY the per-frame decode the UI's partial-preview loop
 * depends on.
 */
class OpenAIProviderImageStreamTest {

    private val genPartial = "image_generation.partial_image"
    private val genCompleted = "image_generation.completed"
    private val editPartial = "image_edit.partial_image"
    private val editCompleted = "image_edit.completed"

    private fun parse(
        event: JsonObject,
        partialEventType: String = genPartial,
        completedEventType: String = genCompleted,
        eventType: String? = null,
    ) = parseImageStreamEvent(event, partialEventType, completedEventType, eventType)

    @Test
    fun `partial frame is flagged partial and carries index and jpeg mime`() {
        val event = buildJsonObject {
            put("type", genPartial)
            put("b64_json", "AAAA")
            put("output_format", "jpeg")
            put("partial_image_index", 1)
        }

        val item = parse(event)

        assertTrue(item != null)
        assertEquals("AAAA", item!!.data)
        assertTrue(item.partial)
        assertEquals(1, item.partialImageIndex)
        assertEquals("image/jpeg", item.mimeType)
    }

    @Test
    fun `completed frame is not partial and maps webp mime with no index`() {
        val event = buildJsonObject {
            put("type", genCompleted)
            put("b64_json", "BBBB")
            put("output_format", "webp")
        }

        val item = parse(event)

        assertTrue(item != null)
        assertEquals("BBBB", item!!.data)
        assertTrue(!item.partial)
        assertNull(item.partialImageIndex)
        assertEquals("image/webp", item.mimeType)
    }

    @Test
    fun `frame of unrelated type is dropped`() {
        val event = buildJsonObject {
            put("type", "image_generation.in_progress")
            put("b64_json", "CCCC")
        }

        assertNull(parse(event))
    }

    @Test
    fun `matching type with missing b64_json is dropped`() {
        val event = buildJsonObject {
            put("type", genPartial)
            put("partial_image_index", 0)
        }

        assertNull(parse(event))
    }

    @Test
    fun `absent output_format defaults to png`() {
        val event = buildJsonObject {
            put("type", genCompleted)
            put("b64_json", "DDDD")
        }

        val item = parse(event)

        assertTrue(item != null)
        assertEquals("image/png", item!!.mimeType)
    }

    @Test
    fun `type falls back to SSE event arg when body omits type`() {
        val event = buildJsonObject {
            put("b64_json", "EEEE")
            put("output_format", "jpg")
        }

        val item = parse(event, eventType = genPartial)

        assertTrue(item != null)
        assertTrue(item!!.partial)
        assertEquals("image/jpeg", item.mimeType)
    }

    @Test
    fun `edit partial frame uses the edit event types`() {
        val event = buildJsonObject {
            put("type", editPartial)
            put("b64_json", "FFFF")
            put("output_format", "png")
            put("partial_image_index", 0)
        }

        val item = parse(
            event = event,
            partialEventType = editPartial,
            completedEventType = editCompleted,
        )

        assertTrue(item != null)
        assertTrue(item!!.partial)
        assertEquals(0, item.partialImageIndex)
    }
}
