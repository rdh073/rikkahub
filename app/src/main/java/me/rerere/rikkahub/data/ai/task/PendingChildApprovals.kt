package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-memory rendezvous between a suspended child approval ([ParentApprovalSurface] awaiting
 * the parent's decision) and `ChatService.handleToolApproval` resolving it. Keyed by the
 * namespaced `taskId/childToolCallId`; one entry per in-flight forwarded approval.
 *
 * Never persisted, mirroring the execution-handle rule: a pending entry is only meaningful while
 * the awaiting child coroutine is alive. If the generation is cancelled mid-wait, [await]'s
 * cancellation removes the entry; a later resolve for that id is then a recorded no-op (false) —
 * a decision for a dead waiter must not invent an approval out of thin air.
 */
class PendingChildApprovals {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Register [namespacedToolCallId] as pending and suspend until [resolve] supplies the
     * parent's decision. The entry is removed on EVERY exit — decision or cancellation — so the
     * map never accumulates dead waiters.
     */
    suspend fun await(namespacedToolCallId: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        check(pending.putIfAbsent(namespacedToolCallId, deferred) == null) {
            "duplicate pending child approval: $namespacedToolCallId"
        }
        return try {
            deferred.await()
        } finally {
            pending.remove(namespacedToolCallId)
        }
    }

    /**
     * Deliver the parent's decision to the waiter, if it is still alive. Returns true when a
     * waiter was resumed; false when no waiter exists (already resolved, cancelled, or a stale
     * id) — the caller treats that as a cosmetic-only resolution.
     */
    fun resolve(namespacedToolCallId: String, approved: Boolean): Boolean =
        pending[namespacedToolCallId]?.complete(approved) ?: false

    /** Whether a live waiter exists for the id. */
    fun isPending(namespacedToolCallId: String): Boolean = pending.containsKey(namespacedToolCallId)
}
