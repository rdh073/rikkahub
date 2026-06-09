package me.rerere.automation.act

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.automation.backend.AutomationBackend
import me.rerere.automation.backend.PerformAction
import me.rerere.automation.cap.AuthRequest
import me.rerere.automation.cap.CapabilityGuard
import me.rerere.automation.cap.Decision
import me.rerere.automation.cap.Sink
import me.rerere.automation.cap.Verb
import me.rerere.automation.observe.ScreenState
import me.rerere.automation.observe.Selector
import me.rerere.automation.observe.SnapshotProjector
import me.rerere.automation.observe.UiFlag
import me.rerere.automation.observe.UiSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * The state-grounded observation loop (#187 design §5). v1 is **read-only**: it exposes only
 * [observe], which captures the backend's raw tree, projects it, and returns an authoritative,
 * freshly-grounded [UiSnapshot]. The full act path —
 *   `resolve(selector) → assert(expectedSeq + windowContentHash) → act → awaitSettle → re-snapshot`
 * — is documented here as the v2 seam but is intentionally NOT implemented (no write verb ships in
 * v1; see #198). Adding it is purely additive: a new `act(...)` method beside [observe].
 *
 * Invariants this enforces (properties P10/P11/P12):
 *  - **P11** the observed `stateSeq` is monotonic and never decreases across observes. The core is
 *    the source of truth for the observed sequence: it tracks the last value and rejects a backend
 *    that regresses (a regressing a11y backend is a bug, not a state to silently accept). It does
 *    NOT fabricate forward progress — a stale-but-equal seq stays equal (the TOCTOU close for the
 *    v2 act path is the content-hash in [AutomationBackend.windowContentHash], not a faked bump).
 *  - **P10** every returned snapshot has `stateSeq ≥` the sequence at entry.
 *  - **P12** when the foreground is the host app, [observe] returns [ScreenState.FOREGROUND_IS_HOST]
 *    with no targets — the agent must pause and re-ground rather than act on host UI.
 */
class AutomationCore(
    private val backend: AutomationBackend,
    private val projector: SnapshotProjector = SnapshotProjector(),
) {
    // Highest stateSeq observed so far. Monotonic guard for P11; starts below any real seq.
    private val lastObservedSeq = AtomicLong(Long.MIN_VALUE)

    /**
     * Capture and project the current UI. Returns a self-grounded snapshot whose `stateSeq` is ≥
     * every prior observe (P10/P11). The snapshot text is the mandatory, self-sufficient channel —
     * :app maps it to a `UIMessagePart.Text` (tool-output images are dropped by most providers).
     */
    suspend fun observe(): UiSnapshot {
        val raw = backend.snapshotRawTree()
        // Capture the TOCTOU token atomically with the tree: this is the windowContentHash the act
        // path re-checks. The projector leaves it "" (a bare projection is not grounded); the core
        // is the one place a snapshot is bound to a live backend, so it stamps the hash here.
        val snapshot = projector.project(raw)
            .copy(windowContentHash = backend.windowContentHash(raw.stateSeq))

        // P11: enforce non-decreasing observed sequence. A backend that hands back a lower seq than
        // we've already seen is malfunctioning; fail loud rather than let the model act on a tree
        // that appears to have travelled backwards in time.
        val previous = lastObservedSeq.get()
        check(snapshot.stateSeq >= previous) {
            "backend stateSeq regressed: got ${snapshot.stateSeq}, last observed $previous"
        }
        lastObservedSeq.set(snapshot.stateSeq)

        return snapshot
    }

    /**
     * The act path (#198 slice 8, design §1 — the act state machine on the proven OCap kernel).
     * Sequence, every step grounded in a kernel seam:
     *
     *   host-pause → resolve(selector) → assert(seq + windowContentHash) → authorize → perform → settle → re-snapshot
     *
     *  0. **host-pause** (I-act-6 / P12 extended): if [grounded] is [ScreenState.FOREGROUND_IS_HOST]
     *     the act refuses with [ActOutcome.StaleState] BEFORE resolve/authorize — no act dispatches
     *     while the host app is foreground, independently of the capability surface. The model must
     *     re-ground (GoHost = pause + re-ground), so it is StaleState, never Denied.
     *  1. **resolve** the [Act] against [grounded] (pure, over the snapshot the model already holds):
     *     a selector matching nothing ⇒ [ActOutcome.StaleState] (re-observe); matching >1 ⇒
     *     [ActOutcome.Denied] AMBIGUOUS (fail closed, never guess — I-act-9). Global acts skip resolve.
     *  2. **assert** the grounding is still fresh: `backend.currentStateSeq() == grounded.stateSeq`
     *     AND `backend.windowContentHash(grounded.stateSeq) == grounded.windowContentHash`. Either
     *     mismatch ⇒ [ActOutcome.StaleState]. Both are required — a dropped event leaves the seq
     *     stale-but-equal; the hash catches it (MR3 / P8 / assert-both, the TOCTOU core).
     *  3. **authorize** via the [CapabilityGuard] BEFORE any dispatch (S2). The OCap is derived from
     *     the [Act] variant (the model never supplies it). DENY ⇒ [ActOutcome.Denied] GUARD.
     *  4. **perform → settle → re-snapshot**, all routed through the capability's revocation token
     *     ([CapabilityGuard.guardInFlight]) so a kill-switch [CapabilityGuard.revoke] cancels the act
     *     in flight, and a revoke that fires between authorize and perform lands in `onAlreadyRevoked`
     *     so the backend is never touched (I-act-10 / P20 extended). Success is the re-snapshot
     *     postcondition (the screen actually changed), NOT the backend's dispatch boolean (D4).
     *
     * Note: only the lowest-risk nav verbs ship here — scroll (no sink) and global nav
     * (`Sink.GLOBAL_NAV`, not dangerous). Dangerous-sink (submit-class) confirmation is slice 11, and
     * full system-UI-non-actionable enforcement on tap is slice 10; neither is reachable from here.
     */
    suspend fun act(guard: CapabilityGuard, grounded: UiSnapshot, request: Act): ActOutcome {
        // 0. GoHost (I-act-6 / P12 extended): no act dispatches while the host app is foreground.
        // Enforced here, BEFORE resolve/authorize, so it covers Act.Global (which skips resolve) and
        // does NOT silently depend on the surface DENY — host-pause is an admission invariant in its
        // own right (design §2 I-act-6, §4 property "host-pause"; the GoHost arrow = pause + re-ground).
        // The model must re-ground, so this is a StaleState, not a Denied (re-observe, never replay).
        if (grounded.screenState == ScreenState.FOREGROUND_IS_HOST) return ActOutcome.StaleState

        // 1. resolve (pure, over the grounded snapshot the tid came from).
        val tid: Int? = when (request) {
            is Act.Global -> null
            is Act.Targeted -> when (val r = resolve(grounded, request.selector)) {
                is Resolve.Found -> r.tid
                Resolve.NotFound -> return ActOutcome.StaleState
                Resolve.Ambiguous -> return ActOutcome.Denied(ActDenyReason.AMBIGUOUS)
            }
        }

        // 2. assert: grounding still fresh in BOTH seq and content hash (the TOCTOU close).
        if (backend.currentStateSeq() != grounded.stateSeq ||
            backend.windowContentHash(grounded.stateSeq) != grounded.windowContentHash
        ) {
            return ActOutcome.StaleState
        }

        // 3. authorize BEFORE the backend (S2). OCap derived from the variant; target is the screen
        // the grounding came from. sensitiveNode guards a (pathological) scroll of a password node.
        val (verb, sink) = when (request) {
            is Act.Targeted -> Verb.SCROLL to null
            is Act.Global -> Verb.GLOBAL to Sink.GLOBAL_NAV
        }
        val sensitive = tid != null &&
            grounded.targets.first { it.tid == tid }.flags.contains(UiFlag.PASSWORD)
        val authRequest = AuthRequest(
            verb = verb,
            targetPkg = grounded.foregroundPkg,
            sink = sink,
            sensitiveNode = sensitive,
        )
        if (guard.authorize(authRequest) == Decision.DENY) {
            return ActOutcome.Denied(ActDenyReason.GUARD)
        }

        // 4. perform → settle → re-snapshot under the revocation token (revoke cancels in-flight).
        val performAction = when (request) {
            is Act.Global -> PerformAction.Global(request.nav)
            is Act.Targeted -> PerformAction.Node(grounded.stateSeq, tid!!, request.kind)
        }
        val job = currentCoroutineContext()[Job]
        return guard.guardInFlight(
            cancel = { job?.cancel(CancellationException("automation revoked")) },
            onAlreadyRevoked = { ActOutcome.Denied(ActDenyReason.REVOKED) },
            block = {
                backend.perform(performAction)
                backend.awaitSettle()
                // D4: act success is the fresh re-grounding, not perform()'s boolean.
                ActOutcome.Acted(observe())
            },
        )
    }

    /** Resolve a [Selector] against [grounded]; ambiguity is a deny, not a guess (design I-act-9). */
    private fun resolve(grounded: UiSnapshot, selector: Selector): Resolve = when (selector) {
        is Selector.ByTid ->
            if (grounded.targets.any { it.tid == selector.tid }) Resolve.Found(selector.tid)
            else Resolve.NotFound

        is Selector.ByText -> grounded.targets
            .filter { it.text == selector.text && (selector.role == null || it.role == selector.role) }
            .toResolve()

        is Selector.BySemanticKey -> grounded.targets
            .filter { it.semanticKey == selector.semanticKey }
            .toResolve()
    }

    private fun List<me.rerere.automation.observe.UiTarget>.toResolve(): Resolve = when (size) {
        0 -> Resolve.NotFound
        1 -> Resolve.Found(this[0].tid)
        else -> Resolve.Ambiguous
    }

    private sealed interface Resolve {
        data class Found(val tid: Int) : Resolve
        object NotFound : Resolve
        object Ambiguous : Resolve
    }

    /** True once the current foreground is the host app (P12) — caller pauses the agent loop. */
    fun isHostForeground(snapshot: UiSnapshot): Boolean =
        snapshot.screenState == ScreenState.FOREGROUND_IS_HOST
}
