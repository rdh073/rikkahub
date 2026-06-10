package me.rerere.rikkahub.ui.pages.imggen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the streaming-preview temp-file leak (issue #231, slice 2 review).
 *
 * The previous collector tracked the live preview in a single `var previewFile: File?`. When
 * numOfImages > 1, partials carry distinct partial_image_index values and so produce distinct
 * preview filenames; the single var only ever held the most-recently-written one, so previews for
 * earlier indices were never deletable — they leaked until the next app start purged appTempFolder.
 * It also had no terminal cleanup, so a flow ending after a partial (user cancel / SSE failure)
 * leaked its last preview too.
 *
 * [PreviewSlots] is the data-structure fix: per-index tracking + [PreviewSlots.drain] surfacing every
 * outstanding file for the collector's terminal `finally`. These assertions fail against single-var
 * tracking (which cannot retain more than one outstanding preview) and hold for the keyed map.
 */
class PreviewSlotsTest {

    private fun fakeFile(name: String) = File("/tmp/$name")

    @Test
    fun `distinct indices retain distinct outstanding previews`() {
        val slots = PreviewSlots()
        val a = fakeFile("imggen_0.png")
        val b = fakeFile("imggen_1.png")

        slots.put(0, a)
        slots.put(1, b)

        // The single-var design would have lost slot 0 here; both must survive.
        val drained = slots.drain()
        assertEquals(setOf(a, b), drained.toSet())
        assertEquals(2, drained.size)
    }

    @Test
    fun `take removes only the requested index and returns its file`() {
        val slots = PreviewSlots()
        val a = fakeFile("imggen_0.png")
        val b = fakeFile("imggen_1.png")
        slots.put(0, a)
        slots.put(1, b)

        assertEquals(a, slots.take(0))
        assertNull(slots.take(0))
        // Slot 1 is untouched by taking slot 0.
        assertEquals(listOf(b), slots.drain())
    }

    @Test
    fun `put replaces the prior handle for the same index`() {
        val slots = PreviewSlots()
        val first = fakeFile("imggen_0_a.png")
        val second = fakeFile("imggen_0_b.png")
        slots.put(0, first)
        slots.put(0, second)

        assertEquals(listOf(second), slots.drain())
    }

    @Test
    fun `drain empties the tracker so a second drain yields nothing`() {
        val slots = PreviewSlots()
        slots.put(0, fakeFile("imggen_0.png"))
        slots.drain()

        assertTrue(slots.drain().isEmpty())
    }
}
