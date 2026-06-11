package me.rerere.ai.runtime.hooks

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property suite over [matches] / [matchesIf] — the pure predicates that decide which
 * [HookMatcher]s fire for a given tool name (spec open Q #3: exact tool-name OR regex,
 * `null` matcher = always matches).
 */
class HookPrimitivesPropertyTest {

    // Alphanumeric names contain no regex metacharacters, so as a pattern such a name
    // matches exactly itself — which lets exact-vs-regex properties stay crisp.
    private val arbToolName: Arb<String> = Arb.string(1..8, Codepoint.alphanumeric())

    @Test
    fun `null matcher always matches any tool name`() {
        runBlocking {
            checkAll(500, arbToolName.orNull(0.2)) { toolName ->
                assertTrue(matches(null, toolName))
            }
        }
    }

    @Test
    fun `exact tool-name matcher matches itself even when it is not a valid regex`() {
        runBlocking {
            // Arbitrary strings include regex metacharacters and invalid patterns;
            // exact equality must match regardless.
            checkAll(500, Arb.string(0..12)) { name ->
                assertTrue(matches(name, name))
            }
        }
    }

    @Test
    fun `literal matcher does not match a different literal name`() {
        runBlocking {
            checkAll(500, arbToolName, arbToolName) { a, b ->
                if (a != b) {
                    assertFalse(matches(a, b))
                }
            }
        }
    }

    @Test
    fun `regex matcher matches per regex semantics`() {
        runBlocking {
            checkAll(500, arbToolName, Arb.string(0..8, Codepoint.alphanumeric())) { prefix, suffix ->
                assertTrue(matches("$prefix.*", "$prefix$suffix"))
            }
        }
    }

    @Test
    fun `regex matcher requires a full match not a substring match`() {
        runBlocking {
            checkAll(500, arbToolName, arbToolName) { core, extra ->
                // "core" as a pattern fully matches only "core"; "core+extra" is longer.
                assertFalse(matches(core, core + extra))
            }
        }
    }

    @Test
    fun `non-null matcher never matches a null tool name`() {
        runBlocking {
            // Events without a tool name (UserPromptSubmit/Stop) only fire null-matcher
            // hooks — a named matcher against no name is fail-closed.
            checkAll(500, Arb.string(0..12)) { matcher ->
                assertFalse(matches(matcher, null))
            }
        }
    }

    @Test
    fun `invalid regex pattern matches only its exact-equal name`() {
        assertFalse(matches("[", "anything"))
        assertTrue(matches("[", "["))
    }

    @Test
    fun `matchesIf delegates to the matcher field`() {
        runBlocking {
            checkAll(500, arbToolName.orNull(0.3), arbToolName.orNull(0.2)) { matcher, toolName ->
                val hookMatcher = HookMatcher(matcher = matcher)
                assertEquals(matches(matcher, toolName), matchesIf(hookMatcher, toolName))
            }
        }
    }
}
