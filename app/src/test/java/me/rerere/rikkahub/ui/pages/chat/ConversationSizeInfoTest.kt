package me.rerere.rikkahub.ui.pages.chat

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.contextTokens
import me.rerere.ai.core.resolveReserveOutput
import me.rerere.ai.core.tokenPressure
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5 (design #193 Stage 1): the size-warning path and the auto-compact trigger consume the SAME
 * [tokenPressure] reading over the SAME [contextTokens] measurement, so for identical inputs they
 * cannot disagree on whether the conversation is over threshold. Also locks the deletion of the old
 * hardcoded 300k constant: the warning is now model-relative (a fixed token count means different
 * pressure on a 128k vs a 1M window).
 *
 * Tested through the extracted pure core [computeConversationSizeInfo] that the @Composable
 * rememberConversationSizeInfo delegates to, so this exercises the production wiring on the JVM
 * without Compose.
 */
class ConversationSizeInfoTest {

    private fun assistantWithUsage(promptTokens: Int): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
            usage = TokenUsage(
                promptTokens = promptTokens,
                completionTokens = 0,
                totalTokens = promptTokens,
            ),
        )

    private fun nodes(count: Int): List<UIMessage> =
        List(count) { UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("m$it"))) }

    // ---- P5: warning and trigger share the same softOver signal ----

    @Test
    fun `P5 warning token signal equals tokenPressure softOver on identical inputs`() {
        runBlocking {
            // window options mirror the trigger's generators.
            val models = listOf(
                Model(modelId = "gpt-4o"),          // 128k
                Model(modelId = "claude-opus-4-8"), // 200k
                Model(modelId = "gemini-2.5-pro"),  // 1M
                Model(modelId = "unknown-xyz"),     // default 128k
            )
            checkAll(
                Arb.element(models),
                Arb.int(0..2_000_000),
                Arb.int(0..200_000), // assistant maxTokens (0 -> default reserve)
            ) { model, promptTokens, maxTokens ->
                val messages = listOf(assistantWithUsage(promptTokens))
                val info = computeConversationSizeInfo(
                    nodeCount = 1,
                    messages = messages,
                    model = model,
                    assistantMaxTokens = maxTokens,
                )

                // Recompute the trigger's view of the same conversation with the warning's fraction and
                // the SAME reserve the trigger would use (resolveReserveOutput(assistant.maxTokens)).
                // The over-threshold signal is soft OR hard -- the SAME predicate the auto-compact
                // trigger uses -- so the two cannot disagree, including hardOver (P5, single source).
                val window = ModelRegistry.getContextWindowForModel(model)
                val p = tokenPressure(
                    contextTokens = contextTokens(messages),
                    window = window,
                    thresholdFraction = CONVERSATION_SIZE_WARNING_FRACTION,
                    reserveOutput = resolveReserveOutput(maxTokens),
                )
                val expected = p.softOver || p.hardOver

                assertEquals(
                    "warning and trigger must agree on the over-threshold signal",
                    expected,
                    info.exceedTokenThreshold,
                )
            }
        }
    }

    @Test
    fun `P5 small window with high maxTokens still agrees on hard guard`() {
        // The exact case the reviewer flagged: a small window + large maxTokens lowers allowedTokens, so
        // hardOver can fire below the soft fraction. The warning must use the same reserve as the trigger
        // so both see the same hardOver. Context chosen to land in the (allowedTokens, softLimit) gap.
        val model = Model(modelId = "gpt-4o") // 128k
        val maxTokens = 64_000                // -> reserve capped at 20k
        val window = ModelRegistry.getContextWindowForModel(model)
        val allowed = window - resolveReserveOutput(maxTokens) - 13_000 // SAFETY_BUFFER
        val softLimit = (window * CONVERSATION_SIZE_WARNING_FRACTION).toInt()
        // Sanity: with a big reserve the hard guard is below the soft line, so a gap exists.
        assertTrue("expected allowed < softLimit for this case", allowed < softLimit)
        val gapTokens = allowed + 1 // over hard guard, under soft line

        val info = computeConversationSizeInfo(
            nodeCount = 1,
            messages = listOf(assistantWithUsage(gapTokens)),
            model = model,
            assistantMaxTokens = maxTokens,
        )
        val p = tokenPressure(
            contextTokens = contextTokens(listOf(assistantWithUsage(gapTokens))),
            window = window,
            thresholdFraction = CONVERSATION_SIZE_WARNING_FRACTION,
            reserveOutput = resolveReserveOutput(maxTokens),
        )
        assertTrue("trigger would fire on the hard guard here", p.hardOver && !p.softOver)
        assertTrue("warning must agree the conversation is over threshold", info.exceedTokenThreshold)
    }

    // ---- showWarning requires BOTH thresholds (rare, high-confidence) ----

    @Test
    fun `showWarning requires both node count and token thresholds`() {
        val smallWindowModel = Model(modelId = "gpt-4o") // 128k
        val window = ModelRegistry.getContextWindowForModel(smallWindowModel)
        val overTokens = (window * CONVERSATION_SIZE_WARNING_FRACTION).toInt() + 5_000

        // Token over but few nodes -> no warning.
        val tokenOverFewNodes = computeConversationSizeInfo(
            nodeCount = 1,
            messages = listOf(assistantWithUsage(overTokens)),
            model = smallWindowModel,
            assistantMaxTokens = null,
        )
        assertTrue(tokenOverFewNodes.exceedTokenThreshold)
        assertFalse(tokenOverFewNodes.exceedNodeCountThreshold)
        assertFalse(tokenOverFewNodes.showWarning)

        // Many nodes but tokens under -> no warning.
        val nodeOverTokensUnder = computeConversationSizeInfo(
            nodeCount = MESSAGE_NODE_WARNING_THRESHOLD + 1,
            messages = listOf(assistantWithUsage(10)),
            model = smallWindowModel,
            assistantMaxTokens = null,
        )
        assertTrue(nodeOverTokensUnder.exceedNodeCountThreshold)
        assertFalse(nodeOverTokensUnder.exceedTokenThreshold)
        assertFalse(nodeOverTokensUnder.showWarning)

        // Both over -> warning.
        val bothOver = computeConversationSizeInfo(
            nodeCount = MESSAGE_NODE_WARNING_THRESHOLD + 1,
            messages = listOf(assistantWithUsage(overTokens)),
            model = smallWindowModel,
            assistantMaxTokens = null,
        )
        assertTrue(bothOver.showWarning)
    }

    // ---- 300k constant is gone: warning is model-relative ----

    @Test
    fun `warning is model relative not a fixed 300k token count`() {
        // 300k tokens: under a 1M window this is NOT high pressure (0.3 < 0.9 fraction), but on a 128k
        // window it is well over. The old hardcoded 300k threshold could not tell these apart.
        val tokens = 300_000
        val bigWindow = Model(modelId = "gemini-2.5-pro") // 1M
        val smallWindow = Model(modelId = "gpt-4o")       // 128k

        val onBig = computeConversationSizeInfo(1, listOf(assistantWithUsage(tokens)), bigWindow, null)
        val onSmall = computeConversationSizeInfo(1, listOf(assistantWithUsage(tokens)), smallWindow, null)

        assertFalse("300k tokens is low pressure on a 1M window", onBig.exceedTokenThreshold)
        assertTrue("300k tokens is over a 128k window", onSmall.exceedTokenThreshold)
    }

    @Test
    fun `null model uses the conservative default window`() {
        val info = computeConversationSizeInfo(1, listOf(assistantWithUsage(10)), model = null, assistantMaxTokens = null)
        assertEquals(ModelRegistry.DEFAULT_CONTEXT_WINDOW, info.contextWindow)
    }
}
