package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guards for the generation entry-point lifecycle contract in [ChatService].
 *
 * [ChatService] is not JVM-instantiable (Context/Room/Koin graph), so these invariants are
 * pinned at the source level — the same pattern as [NoRawPrintlnInServiceWebCommonSpeechTest].
 *
 * Invariant 1 (cancellation rethrow): every `catch (e: Exception)` block in ChatService.kt must
 * rethrow per [me.rerere.rikkahub.utils.shouldRethrowVmError] before reporting. The sendMessage
 * path gained this guard when its CancellationException swallow was fixed; the sibling entry
 * points (regenerateAtMessage, handleToolApproval, translateMessage) silently drifted — their
 * catch blocks swallowed cancellation, so a job cancelled by stopGeneration "completed normally"
 * instead of propagating cancellation, breaking the structured-concurrency contract that
 * CoroutineUtils pins. addError early-returns on CancellationException, which made the swallow
 * invisible. This test fails if any entry point loses (or never gains) the guard again.
 */
class GenerationEntryLifecycleGuardTest {

    @Test
    fun `every Exception catch in ChatService rethrows per shouldRethrowVmError before reporting`() {
        val source = chatServiceSource()
        val violations = mutableListOf<String>()

        var searchFrom = 0
        while (true) {
            val catchIndex = source.indexOf("catch (e: Exception)", searchFrom)
            if (catchIndex < 0) break
            val block = catchBlockBody(source, catchIndex)
            if (!block.contains("if (shouldRethrowVmError(e)) throw e")) {
                val line = source.substring(0, catchIndex).count { it == '\n' } + 1
                violations += "ChatService.kt:$line: catch (e: Exception) without the cancellation rethrow guard"
            }
            searchFrom = catchIndex + 1
        }

        assertFalse("ChatService.kt has no catch (e: Exception) blocks; scan is vacuous", searchFrom == 0)
        assertTrue(
            "Generation catch blocks swallowing CancellationException:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /** Extracts the `{ ... }` body following a `catch (...)` header via brace counting. */
    private fun catchBlockBody(source: String, catchIndex: Int): String {
        val open = source.indexOf('{', catchIndex)
        require(open > 0) { "catch without a block at index $catchIndex" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        error("Unbalanced braces after catch at index $catchIndex")
    }

    private fun chatServiceSource(): String {
        val candidates = listOf(
            "src/main/java/me/rerere/rikkahub/service/ChatService.kt",
            "app/src/main/java/me/rerere/rikkahub/service/ChatService.kt",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
        assertTrue(
            "Could not locate ChatService.kt (CWD=${File("").absolutePath}); scan would pass vacuously",
            file != null
        )
        return file!!.readText()
    }
}
