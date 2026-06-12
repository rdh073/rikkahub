package me.rerere.rikkahub.data.repository

import me.rerere.ai.runtime.task.TaskApprovalRequest
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.runtime.task.TaskStateReducer
import me.rerere.rikkahub.data.ai.task.TaskRunStore
import me.rerere.rikkahub.data.db.dao.TaskRunDAO
import me.rerere.rikkahub.data.db.entity.TaskRunEntity
import me.rerere.rikkahub.data.db.entity.TaskRunEventSummary
import me.rerere.rikkahub.data.db.entity.TaskRunPendingApproval
import me.rerere.rikkahub.data.db.entity.TaskRunStateTag
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * Persistence for task runs (SPEC.md M2, maintainer decision #1: summary-only). This repository
 * is the SINGLE place a [TaskRunEntity.latestState] is ever written, and it writes it ONLY by
 * folding the persisted state through the pure `:ai-runtime` [TaskStateReducer]. Consequently the
 * stored state can never disagree with TASK_STATE_LEGAL: an illegal (state, event) pair is a
 * no-op (the reducer returns the state unchanged, so we skip the write), terminals are absorbing,
 * and replaying a redelivered event stream is idempotent.
 *
 * The repository owns the bridge between the persisted columns and the domain [TaskState]:
 * - reconstruction ([toTaskState]) reads the tag plus the payload columns (final result/error,
 *   pending approval, interrupted summary) so a recovered row reduces from where it left off;
 * - persistence ([applyState]) writes the tag derived from the new domain state and mirrors that
 *   state's payload into exactly the matching columns, clearing the others.
 *
 * Every mutation runs inside one [BoardTransactionRunner] transaction so the read-modify-write of
 * a state fold, a summary append, or a usage merge is atomic against concurrent callers.
 */
class TaskRunRepository(
    private val dao: TaskRunDAO,
    private val transactions: BoardTransactionRunner,
    private val now: () -> Long = System::currentTimeMillis,
) : TaskRunStore {

    /** Persist a fresh run in [TaskState.Created]; the spawn tool call has not been accepted yet. */
    override suspend fun create(spec: TaskSpec): TaskState = transactions.inTransaction {
        val timestamp = now()
        dao.upsert(
            TaskRunEntity(
                id = spec.taskId.toString(),
                conversationId = spec.parentConversationId.toString(),
                parentToolCallId = spec.parentToolCallId,
                agentTypeId = spec.agentTypeId,
                prompt = spec.prompt,
                latestState = TaskRunStateTag.CREATED.name,
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        )
        TaskState.Created
    }

    /** The current domain state of a run, or null if no such run exists. */
    suspend fun get(taskId: Uuid): TaskState? = transactions.inTransaction {
        dao.getById(taskId.toString())?.toTaskState()
    }

    /**
     * Fold [event] over the run's persisted state via [TaskStateReducer] and persist the result.
     *
     * Returns the resulting domain state, or null when no run exists. The reducer — not this
     * method — decides legality: when it returns the state unchanged (illegal edge, or a terminal
     * absorbing the event) the row is left exactly as it was, with no write. This is what keeps
     * the persisted state in lock-step with TASK_STATE_LEGAL.
     */
    override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? = transactions.inTransaction {
        val entity = dao.getById(taskId.toString()) ?: return@inTransaction null
        val current = entity.toTaskState()
        val next = TaskStateReducer.reduce(current, event)
        if (next == current) {
            // No-op transition (illegal edge or absorbing terminal) — leave the row untouched so
            // updated_at is not spuriously bumped and event redelivery stays idempotent.
            return@inTransaction current
        }
        dao.upsert(entity.applyState(next, updatedAt = now()))
        next
    }

    /**
     * Append a summary-only event to the run's history with a strictly monotone sequence
     * ([TaskRunEntity.eventSequence] + 1). Returns the assigned sequence, or null if the run is
     * gone. The monotone cursor lets a redelivered (lower-sequence) event be recognised as stale.
     */
    override suspend fun appendEventSummary(
        taskId: Uuid,
        summary: String,
        kind: String,
    ): Long? = transactions.inTransaction {
        val entity = dao.getById(taskId.toString()) ?: return@inTransaction null
        val sequence = entity.eventSequence + 1
        val timestamp = now()
        val appended = (entity.decodeEventSummaries() ?: emptyList()) +
            TaskRunEventSummary(sequence = sequence, summary = summary, timestamp = timestamp, kind = kind)
        dao.upsert(
            entity.copy(
                eventSummaries = TaskRunEntity.encodeEventSummaries(appended),
                eventSequence = sequence,
                updatedAt = timestamp,
            )
        )
        sequence
    }

    /**
     * Merge a child's CUMULATIVE usage report into the run's counters via [TaskBudgetUsage.record]
     * (component-wise max), so counters never decrease and stale/out-of-order reports are
     * harmless (TASK_BUDGET_MONOTONE). Returns the first cap breach if the merged usage exceeds
     * [budget], or null. Surfacing the breach lets the coordinator fire [TaskEvent.BudgetExceeded];
     * this method does not itself drive the state machine.
     */
    override suspend fun recordUsage(
        taskId: Uuid,
        reported: TaskBudgetUsage,
        budget: TaskBudget,
    ): TaskBudgetBreach? = transactions.inTransaction {
        val entity = dao.getById(taskId.toString()) ?: return@inTransaction null
        val merged = entity.toUsage().record(reported)
        dao.upsert(
            entity.copy(
                usageSteps = merged.steps,
                usageTokens = merged.tokens,
                usageElapsedMs = merged.elapsed.inWholeMilliseconds,
                updatedAt = now(),
            )
        )
        budget.firstBreach(merged)
    }

    // --- entity <-> domain bridge ---------------------------------------------------------------

    /**
     * Reconstruct the domain [TaskState] from the persisted tag plus the payload columns. A
     * payload-carrying state whose column is missing/corrupt is reconstructed with an empty
     * payload rather than throwing: the tag alone proves which state the run is in, and an empty
     * summary/result is safe to display (a corrupt column is a display gap, not a legality break).
     */
    private fun TaskRunEntity.toTaskState(): TaskState {
        val tag = TaskRunStateTag.fromPersistedOrNull(latestState)
            ?: error("corrupt task run $id: unknown latest_state '$latestState'")
        return when (tag) {
            TaskRunStateTag.CREATED -> TaskState.Created
            TaskRunStateTag.QUEUED -> TaskState.Queued
            TaskRunStateTag.STARTING -> TaskState.Starting
            TaskRunStateTag.RUNNING -> TaskState.Running
            TaskRunStateTag.WAITING_APPROVAL -> TaskState.WaitingApproval(
                decodePendingApproval()?.toRequest()
                    ?: TaskApprovalRequest(childToolCallId = "", toolName = "")
            )
            TaskRunStateTag.RESUMING -> TaskState.Resuming
            TaskRunStateTag.SUCCEEDED -> TaskState.Succeeded(finalResult.orEmpty())
            TaskRunStateTag.FAILED -> TaskState.Failed(finalError.orEmpty())
            TaskRunStateTag.CANCELLED -> TaskState.Cancelled
            TaskRunStateTag.BUDGET_EXHAUSTED -> TaskState.BudgetExhausted(
                budgetBreachOf(toUsage())
            )
            TaskRunStateTag.INTERRUPTED -> TaskState.Interrupted(finalResult.orEmpty())
        }
    }

    /**
     * Project [state] back onto the entity columns: write its tag and mirror its payload into the
     * matching column, clearing the payload columns this state does not own. Doing the clearing
     * here (not just the setting) is why the pending-approval column is empty again the moment a
     * run leaves [TaskState.WaitingApproval].
     */
    private fun TaskRunEntity.applyState(state: TaskState, updatedAt: Long): TaskRunEntity {
        val base = copy(
            updatedAt = updatedAt,
            finalResult = null,
            finalError = null,
            pendingApproval = null,
        )
        return when (state) {
            is TaskState.WaitingApproval -> base.copy(
                latestState = TaskRunStateTag.WAITING_APPROVAL.name,
                pendingApproval = TaskRunEntity.encodePendingApproval(state.request.toPersisted()),
            )
            is TaskState.Succeeded -> base.copy(
                latestState = TaskRunStateTag.SUCCEEDED.name,
                finalResult = state.summary,
            )
            is TaskState.Failed -> base.copy(
                latestState = TaskRunStateTag.FAILED.name,
                finalError = state.error,
            )
            // Interrupted carries the progress summary the resume injects (decision #1); it reuses
            // the final_result column since no successful final answer can coexist with it.
            is TaskState.Interrupted -> base.copy(
                latestState = TaskRunStateTag.INTERRUPTED.name,
                finalResult = state.progressSummary,
            )
            is TaskState.BudgetExhausted -> base.copy(latestState = TaskRunStateTag.BUDGET_EXHAUSTED.name)
            TaskState.Created -> base.copy(latestState = TaskRunStateTag.CREATED.name)
            TaskState.Queued -> base.copy(latestState = TaskRunStateTag.QUEUED.name)
            TaskState.Starting -> base.copy(latestState = TaskRunStateTag.STARTING.name)
            TaskState.Running -> base.copy(latestState = TaskRunStateTag.RUNNING.name)
            TaskState.Resuming -> base.copy(latestState = TaskRunStateTag.RESUMING.name)
            TaskState.Cancelled -> base.copy(latestState = TaskRunStateTag.CANCELLED.name)
        }
    }

    private fun TaskRunEntity.toUsage(): TaskBudgetUsage =
        TaskBudgetUsage(steps = usageSteps, tokens = usageTokens, elapsed = usageElapsedMs.milliseconds)

    /**
     * The breach a persisted [TaskRunStateTag.BUDGET_EXHAUSTED] row reconstructs. The specific cap
     * is derived from the persisted counters against the default budget; the row records that a
     * breach happened, not which cap (a single column would be a schema add for a terminal-only
     * detail), so the recomputed cap is the surfaced one.
     */
    private fun budgetBreachOf(usage: TaskBudgetUsage): TaskBudgetBreach =
        TaskBudget().firstBreach(usage) ?: TaskBudgetBreach(
            cap = me.rerere.ai.runtime.task.TaskBudgetCap.Steps,
            usage = usage,
        )

    private fun TaskApprovalRequest.toPersisted(): TaskRunPendingApproval =
        TaskRunPendingApproval(childToolCallId = childToolCallId, toolName = toolName)

    private fun TaskRunPendingApproval.toRequest(): TaskApprovalRequest =
        TaskApprovalRequest(childToolCallId = childToolCallId, toolName = toolName)
}
