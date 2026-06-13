package me.rerere.rikkahub.ui.pages.assistant.detail

import me.rerere.rikkahub.data.model.AutomationGrant
import me.rerere.rikkahub.data.model.AutomationSink
import me.rerere.rikkahub.data.model.AutomationVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure scope-editor logic behind AssistantAutomationPage (#187 v2 T9). The page is a thin Compose
 * shell over these helpers, so the editor invariants (per-verb toggles are additive/idempotent, the
 * package set never holds blanks/dupes, the SUBMIT sink can never be granted from this surface, and a
 * freshly-enabled grant carries the conservative TTL/steps defaults) are provable on the JVM.
 */
class AssistantAutomationPageTest {

    // --- defaults ---

    @Test
    fun `enabling a fresh grant applies the conservative TTL and steps defaults`() {
        val granted = AutomationGrant().withEnabled(true)

        assertTrue(granted.enabled)
        assertEquals(DEFAULT_TTL_MINUTES, granted.ttlMinutes)
        assertEquals(DEFAULT_MAX_STEPS, granted.maxSteps)
    }

    @Test
    fun `enabling a grant that already has TTL and steps keeps the user values`() {
        val configured = AutomationGrant(ttlMinutes = 12, maxSteps = 7)

        val granted = configured.withEnabled(true)

        assertEquals(12, granted.ttlMinutes)
        assertEquals(7, granted.maxSteps)
    }

    @Test
    fun `disabling a grant flips the master switch without wiping the scope`() {
        val grant = AutomationGrant(
            enabled = true,
            allowedPackages = setOf("com.example.app"),
            verbs = setOf(AutomationVerb.OBSERVE),
            ttlMinutes = 5,
            maxSteps = 50,
        )

        val disabled = grant.withEnabled(false)

        assertFalse(disabled.enabled)
        assertEquals(setOf("com.example.app"), disabled.allowedPackages)
        assertEquals(setOf(AutomationVerb.OBSERVE), disabled.verbs)
    }

    // --- per-verb switches ---

    @Test
    fun `toggling a verb on adds it and toggling off removes it`() {
        val withTap = AutomationGrant().withVerb(AutomationVerb.TAP, true)
        assertEquals(setOf(AutomationVerb.TAP), withTap.verbs)

        val withoutTap = withTap.withVerb(AutomationVerb.TAP, false)
        assertTrue(withoutTap.verbs.isEmpty())
    }

    @Test
    fun `toggling a verb is idempotent`() {
        val once = AutomationGrant().withVerb(AutomationVerb.OBSERVE, true)
        val twice = once.withVerb(AutomationVerb.OBSERVE, true)
        assertEquals(once, twice)
    }

    // --- sinks (SUBMIT excluded) ---

    @Test
    fun `the editor never offers the SUBMIT sink`() {
        // SUBMIT stays the stricter separate opt-in the kernel withholds; this surface must not list it.
        assertFalse(EDITABLE_SINKS.contains(AutomationSink.SUBMIT))
        assertEquals(setOf(AutomationSink.TYPE_INTO, AutomationSink.GLOBAL_NAV), EDITABLE_SINKS.toSet())
    }

    @Test
    fun `granting the SUBMIT sink through the editor is rejected`() {
        // A defensive guard: even if a caller passes SUBMIT, it can never be written from this surface.
        val grant = AutomationGrant().withSink(AutomationSink.SUBMIT, true)
        assertFalse(grant.sinks.contains(AutomationSink.SUBMIT))
    }

    @Test
    fun `toggling an allowed sink on adds it and off removes it`() {
        val withType = AutomationGrant().withSink(AutomationSink.TYPE_INTO, true)
        assertEquals(setOf(AutomationSink.TYPE_INTO), withType.sinks)

        val withoutType = withType.withSink(AutomationSink.TYPE_INTO, false)
        assertTrue(withoutType.sinks.isEmpty())
    }

    // --- allowed packages ---

    @Test
    fun `adding a package trims it and stores it once`() {
        val grant = AutomationGrant()
            .withAddedPackage("  com.example.target  ")
            .withAddedPackage("com.example.target")

        assertEquals(setOf("com.example.target"), grant.allowedPackages)
    }

    @Test
    fun `adding a blank package is a no-op`() {
        val grant = AutomationGrant().withAddedPackage("   ")
        assertTrue(grant.allowedPackages.isEmpty())
    }

    @Test
    fun `removing a package drops exactly that entry`() {
        val grant = AutomationGrant(allowedPackages = setOf("a.b", "c.d"))
            .withRemovedPackage("a.b")

        assertEquals(setOf("c.d"), grant.allowedPackages)
    }

    // --- round-trip ---

    @Test
    fun `a fully configured grant round-trips through the editor unchanged`() {
        val configured = AutomationGrant()
            .withEnabled(true)
            .withAddedPackage("com.example.target")
            .withVerb(AutomationVerb.OBSERVE, true)
            .withVerb(AutomationVerb.TAP, true)
            .withSink(AutomationSink.TYPE_INTO, true)

        assertEquals(
            AutomationGrant(
                enabled = true,
                allowedPackages = setOf("com.example.target"),
                verbs = setOf(AutomationVerb.OBSERVE, AutomationVerb.TAP),
                sinks = setOf(AutomationSink.TYPE_INTO),
                ttlMinutes = DEFAULT_TTL_MINUTES,
                maxSteps = DEFAULT_MAX_STEPS,
            ),
            configured,
        )
    }
}
