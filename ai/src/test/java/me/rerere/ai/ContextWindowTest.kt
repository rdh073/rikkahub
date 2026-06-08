package me.rerere.ai

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6 (design #193, architecture-design:114): [ModelRegistry.getContextWindowForModel] resolution
 * order is `Model.contextWindow override -> registry family lookup -> DEFAULT_CONTEXT_WINDOW`, and it
 * never returns a value <= 0. Pure, JVM-testable: the resolver depends only on a [Model] value, no
 * Android/network.
 */
class ContextWindowTest {

    @Test
    fun `explicit positive override wins over registry and default`() {
        // gpt-5 has a seeded family window (400k); an explicit override must still take priority.
        val model = Model(modelId = "gpt-5", contextWindow = 12_345)
        assertEquals(12_345, ModelRegistry.getContextWindowForModel(model))
    }

    @Test
    fun `non-positive override is ignored and falls through`() {
        // A bad config (0 or negative) must NOT disable the downstream safety guard. Falls through to
        // the registry value for a known family.
        assertEquals(
            400_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gpt-5", contextWindow = 0))
        )
        assertEquals(
            400_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gpt-5", contextWindow = -1))
        )
    }

    @Test
    fun `registry family lookup supplies window when no override`() {
        assertEquals(
            200_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "claude-opus-4-8", contextWindow = null))
        )
        assertEquals(
            1_000_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gemini-2.5-pro", contextWindow = null))
        )
        assertEquals(
            400_000,
            ModelRegistry.getContextWindowForModel(Model(modelId = "gpt-5-mini", contextWindow = null))
        )
    }

    @Test
    fun `unknown model falls back to conservative default`() {
        assertEquals(
            ModelRegistry.DEFAULT_CONTEXT_WINDOW,
            ModelRegistry.getContextWindowForModel(Model(modelId = "totally-unknown-model-xyz"))
        )
        // A blank id matches no family either.
        assertEquals(
            ModelRegistry.DEFAULT_CONTEXT_WINDOW,
            ModelRegistry.getContextWindowForModel(Model(modelId = ""))
        )
    }

    @Test
    fun `P6 resolver always returns a positive window for any id and any override`() {
        // Property: regardless of id or override sign, the resolved window is strictly positive and
        // equals the override exactly when the override is positive.
        runBlocking {
            checkAll(
                Arb.string(0..20),
                Arb.int(-5_000..2_000_000),
            ) { id, override ->
                val model = Model(modelId = id, contextWindow = override)
                val resolved = ModelRegistry.getContextWindowForModel(model)
                assertTrue("resolved window must be > 0 (id='$id', override=$override)", resolved > 0)
                if (override > 0) {
                    assertEquals(override, resolved)
                }
            }
        }
    }
}
