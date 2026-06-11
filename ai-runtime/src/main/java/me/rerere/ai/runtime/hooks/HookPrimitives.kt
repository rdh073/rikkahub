package me.rerere.ai.runtime.hooks

/**
 * Pure decision primitives for the hook dispatcher (#200 v1). Everything here is
 * side-effect-free and order-deterministic so the agent loop's behavior is a pure function of
 * the handler outputs — the property suites in the test source set are the contract.
 */
sealed interface HookDecision {
    data object Allow : HookDecision
    data object Ask : HookDecision
    data class Deny(val reason: String) : HookDecision
}

/** The parsed output of a single hook handler invocation. */
data class HookOutput(
    val decision: HookDecision = HookDecision.Allow,
    val updatedInput: String? = null,
    val additionalContext: String? = null,
    val preventContinuation: Boolean = false,
)

/** The combined decision the agent loop consumes after all matched handlers ran. */
data class AggregatedHookResult(
    val decision: HookDecision = HookDecision.Allow,
    val updatedInput: String? = null,
    val additionalContext: String? = null,
    val preventContinuation: Boolean = false,
)

/**
 * Folds handler outputs (in handler order) into one result:
 * - decision: most restrictive wins — Deny > Ask > Allow; the FIRST Deny's reason is kept so
 *   the surfaced reason is stable regardless of what later handlers return.
 * - updatedInput: each rewrite supersedes the previous one; the last non-null rewrite wins.
 * - additionalContext: order-stable concatenation of every contributor.
 * - preventContinuation: logical OR.
 *
 * Empty input is the Allow passthrough.
 */
fun aggregate(outputs: List<HookOutput>): AggregatedHookResult {
    var decision: HookDecision = HookDecision.Allow
    var updatedInput: String? = null
    val contexts = mutableListOf<String>()
    var preventContinuation = false
    for (output in outputs) {
        decision = mostRestrictive(decision, output.decision)
        output.updatedInput?.let { updatedInput = it }
        output.additionalContext?.let { contexts += it }
        preventContinuation = preventContinuation || output.preventContinuation
    }
    return AggregatedHookResult(
        decision = decision,
        updatedInput = updatedInput,
        additionalContext = contexts.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        preventContinuation = preventContinuation,
    )
}

private fun mostRestrictive(a: HookDecision, b: HookDecision): HookDecision = when {
    a is HookDecision.Deny -> a
    b is HookDecision.Deny -> b
    a == HookDecision.Ask || b == HookDecision.Ask -> HookDecision.Ask
    else -> HookDecision.Allow
}

/**
 * Matcher semantics (spec open Q #3): `null` always matches; otherwise exact tool-name match OR
 * full regex match. A non-null matcher against a null tool name (events that carry no tool, i.e.
 * UserPromptSubmit/Stop) is fail-closed: no match. An invalid regex pattern is not an error —
 * it simply degrades to the exact-name comparison (which already ran), because user-authored
 * matchers must never crash the agent loop.
 */
fun matches(matcher: String?, toolName: String?): Boolean {
    if (matcher == null) return true
    if (toolName == null) return false
    if (matcher == toolName) return true
    return runCatching { Regex(matcher).matches(toolName) }.getOrDefault(false)
}

/** [matches] lifted to a [HookMatcher]. */
fun matchesIf(matcher: HookMatcher, toolName: String?): Boolean =
    matches(matcher.matcher, toolName)
