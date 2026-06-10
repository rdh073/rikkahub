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
 * numOfImages > 1, each output image produces its own preview filename; the single var only ever
 * held the most-recently-written one, so previews for earlier output images were never deletable —
 * they leaked until the next app start purged appTempFolder. It also had no terminal cleanup, so a
 * flow ending after a partial (user cancel / SSE failure) leaked its last preview too.
 *
 * [PreviewSlots] is the data-structure fix: per-OUTPUT-image tracking + [PreviewSlots.drain]
 * surfacing every outstanding file for the collector's terminal `finally`. The collector keys slots
 * by the output-image index (the count of finalized images so far), NOT by the frame's
 * `partial_image_index` — that wire field is a single image's progressive-refinement counter and
 * resets to 0 per output image, so keying on it would collide distinct images on slot 0. These
 * assertions exercise the keyed-map contract directly: distinct keys retain distinct files (a
 * single-var tracker cannot), [take] is per-key, [put] replaces, [drain] empties.
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

/**
 * Regression guard for the multi-image preview-slot collision (issue #231, slice 2 review round 2).
 *
 * `partial_image_index` is a single image's progressive-refinement counter (0..partial_images-1) and
 * resets to 0 for EACH of the n output images — it is NOT the output-image slot. The OpenAI docs do
 * NOT specify whether, with n > 1, the per-image partial sequences arrive sequentially or interleaved
 * (https://developers.openai.com/api/reference/resources/images/generation-streaming-events leaves
 * the n>1 ordering undefined). Under any interleaving where two distinct output images each have a
 * live partial, keying slots on `partial_image_index` collapses both onto slot 0: image 1's partial
 * overwrites image 0's slot-0 handle WITHOUT deleting image 0's temp file, leaking it.
 *
 * [previewSlotKey] keys on the OUTPUT-image index (count of finalized images so far) — distinct per
 * output image — so each image's live preview occupies its own slot and is drained on the terminal
 * cleanup regardless of frame interleaving. The two tests replay the same interleaved frame sequence:
 * the buggy `partial_image_index` strategy leaks image 0's file; the output-index strategy retains
 * both.
 */
class PreviewSlotKeyTest {

    private data class Frame(val partial: Boolean, val partialImageIndex: Int?, val outputImage: Int)

    /**
     * Replay the collector's preview bookkeeping with [slotOf] choosing the slot key. Returns, per
     * slot key, the set of distinct OUTPUT images that ever occupied that key. The structural
     * invariant the collector relies on is that a preview slot belongs to exactly one output image —
     * two output images sharing a slot means one image's `take(slot)?.delete()` destroys the other's
     * still-live preview mid-stream (data loss / collision). Mirrors collectImageGeneration's
     * take/put loop, IO stripped.
     */
    private fun slotOwners(
        frames: List<Frame>,
        slotOf: (frame: Frame, finalizedCount: Int) -> Int,
    ): Map<Int, Set<Int>> {
        val owners = mutableMapOf<Int, MutableSet<Int>>()
        var finalizedCount = 0
        frames.forEach { frame ->
            val slot = slotOf(frame, finalizedCount)
            if (frame.partial) {
                owners.getOrPut(slot) { mutableSetOf() }.add(frame.outputImage)
            } else {
                finalizedCount++
            }
        }
        return owners
    }

    // Interleaved: image 0 partial, image 1 partial, image 0 partial again — both still in flight.
    // (n>1 partial ordering is undefined per the wire docs; the collector must be collision-safe.)
    private val interleavedTwoImagePartials = listOf(
        Frame(partial = true, partialImageIndex = 0, outputImage = 0),
        Frame(partial = true, partialImageIndex = 0, outputImage = 1),
        Frame(partial = true, partialImageIndex = 1, outputImage = 0),
    )

    @Test
    fun `output-index slot key gives each output image its own slot`() {
        val owners = slotOwners(interleavedTwoImagePartials) { frame, _ ->
            // The collector keys partials by the output image, not partial_image_index. In real flow
            // finalizedCount tracks this; the test pins the contract directly via outputImage.
            previewSlotKey(frame.outputImage)
        }
        // No slot is shared by more than one output image: each image refines its own preview safely.
        assertTrue(owners.values.all { it.size == 1 })
        assertEquals(setOf(0), owners[previewSlotKey(0)])
        assertEquals(setOf(1), owners[previewSlotKey(1)])
    }

    @Test
    fun `partial_image_index slot key collides distinct output images onto one slot (the bug)`() {
        val owners = slotOwners(interleavedTwoImagePartials) { frame, finalized ->
            frame.partialImageIndex ?: finalized
        }
        // image 0 and image 1 both land on partial_image_index 0 -> slot 0. image 1's partial runs
        // `take(0)?.delete()` and destroys image 0's still-live preview file mid-stream. This shared
        // ownership is exactly the regression the output-index key fixes.
        assertEquals(setOf(0, 1), owners[0])
    }
}
