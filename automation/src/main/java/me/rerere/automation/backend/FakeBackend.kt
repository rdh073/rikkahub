package me.rerere.automation.backend

import kotlinx.coroutines.CompletableDeferred

/**
 * Deterministic, in-memory [AutomationBackend] for unit/PBT (design §8, the FakeBackend that
 * every P1–P25 / S1 / S2 / MBT property runs over). No threads, no Android — the tree, stateSeq,
 * foreground package and content hashes are all settable, so a property generator drives exactly
 * the topology it wants and asserts on the projection/decision with no flakiness.
 *
 * It also supports asserting in-flight cancellation (design I9 / P20): [snapshotRawTree] can be
 * made to suspend until [releaseGate] is called, so a test can launch an observe, revoke the
 * capability while the call is parked, and assert the coroutine is cancelled rather than completing.
 */
class FakeBackend(
    rawTree: RawTree = RawTree(stateSeq = 0L, foregroundPkg = "com.example.app", windows = emptyList()),
) : AutomationBackend {
    @Volatile
    var rawTree: RawTree = rawTree

    /** Per-stateSeq content hash; defaults to the stateSeq string when not explicitly set. */
    private val contentHashes = HashMap<Long, String>()

    /** Counts backend hits — lets a property assert "exactly one capture per observe", etc. */
    @Volatile
    var snapshotCount: Int = 0
        private set

    /**
     * When non-null, [snapshotRawTree] AND [perform] await this gate before proceeding (in-flight
     * cancel tests). An act parks at its [perform] step, so a test can revoke mid-act and assert the
     * coroutine is cancelled rather than completing (I-act-10 / P20 extended).
     */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    /** Every [perform] call, in order — lets a property assert "perform happened / never happened". */
    val performed = ArrayList<PerformAction>()

    /** Counts [awaitSettle] calls — a property can assert settle runs exactly once per act. */
    @Volatile
    var settleCount: Int = 0
        private set

    override suspend fun snapshotRawTree(): RawTree {
        gate?.await()
        snapshotCount++
        return rawTree
    }

    override fun windowContentHash(stateSeq: Long): String =
        contentHashes[stateSeq] ?: stateSeq.toString()

    override fun currentStateSeq(): Long = rawTree.stateSeq

    override suspend fun perform(action: PerformAction): Boolean {
        gate?.await()
        performed.add(action)
        // A real act changes the screen ⇒ the backend's sequence advances. Modelling that here keeps
        // tids turn-scoped (the post-act re-snapshot sees a fresh seq, so the old grounding is stale
        // for the NEXT act) without a test having to inject the transition by hand.
        rawTree = rawTree.copy(stateSeq = rawTree.stateSeq + 1)
        return true
    }

    override suspend fun awaitSettle() {
        settleCount++
    }

    // --- test-only mutators (deterministic substrate control) ---

    /** Advance the sequence (simulate a WINDOW_STATE/CONTENT event). */
    fun injectTransition(newForegroundPkg: String? = null, newWindows: List<RawWindow>? = null) {
        rawTree = rawTree.copy(
            stateSeq = rawTree.stateSeq + 1,
            foregroundPkg = newForegroundPkg ?: rawTree.foregroundPkg,
            windows = newWindows ?: rawTree.windows,
        )
    }

    fun setForeground(pkg: String) {
        rawTree = rawTree.copy(foregroundPkg = pkg)
    }

    fun setContentHash(stateSeq: Long, hash: String) {
        contentHashes[stateSeq] = hash
    }

    /** Arm the suspension gate so the next [snapshotRawTree] parks until [releaseGate]. */
    fun armGate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gate = it }

    fun releaseGate() {
        gate?.complete(Unit)
        gate = null
    }
}
