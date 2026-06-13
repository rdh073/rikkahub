package me.rerere.rikkahub.data.repository

import kotlinx.serialization.json.Json
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import me.rerere.ai.runtime.contract.MisfirePolicy
import me.rerere.ai.runtime.schedule.Recurrence
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.data.db.dao.TaskScheduleDAO
import me.rerere.rikkahub.data.db.entity.TaskScheduleEntity
import me.rerere.rikkahub.data.model.Assistant
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * The outcome of a winning [TaskScheduleRepository.claimDue]: the new run's id the worker will drive
 * [TaskCoordinator.run] under, plus the schedule's post-claim [snapshot] (already advanced/disabled).
 * The win is carried in this VALUE — a non-null [ScheduleClaim] means "you won this window" — so the
 * caller never has to re-read the row and infer the win from post-state, exactly as `claimResume`
 * reports its win as a `Boolean` rather than via a follow-up read.
 */
data class ScheduleClaim(
    val runId: Uuid,
    val snapshot: ScheduleSnapshot,
)

/**
 * The SINGLE legality path for task schedules (SPEC.md M3): the schedule tools and the schedule UI
 * both mutate through this repository, so legality never depends on the caller. Each public method
 * runs inside exactly one [BoardTransactionRunner] transaction and validates EVERYTHING before it
 * writes anything — there is no partial write to roll back.
 *
 * Gates enforced here, nowhere else (SPEC.md "Repository Safety Gates"):
 * - **Target spawnable.** [ScheduleDraft.targetAssistantId] must resolve to an existing
 *   [Assistant] with `spawnable = true`. The lookup is an injected `suspend (Uuid) -> Assistant?`
 *   (DIP): the repository never imports the settings store, mirroring how `TaskCoordinator` takes
 *   injected lookups, so it stays a pure persistence concern.
 * - **Active-schedule caps.** Enabled schedules are capped per conversation
 *   ([MAX_ACTIVE_PER_CONVERSATION]) AND per owner class ([MAX_ACTIVE_PER_USER]); the owner split
 *   keeps an agent from starving the user's quota and vice-versa (spec assumption 4).
 * - **Minimum recurring interval.** A `RECURRING` draft's interval must be
 *   >= [MIN_RECURRENCE_INTERVAL_MILLIS]; a malformed or missing spec is rejected too. This prevents
 *   a runaway tight loop chewing battery and aligns with WorkManager's own 15-minute floor.
 * - **Prompt bound.** `prompt.length <= ` [MAX_PROMPT_CHARS].
 * - **Scoping.** [list] and [delete] are scoped to the bound conversation; a [delete] of an id not
 *   in this conversation REJECTS (never silently deletes cross-conversation).
 *
 * Domain rejections return [ScheduleMutationResult.Rejected] — an EXPECTED outcome the caller
 * surfaces to its user/model. Never an exception: a rejected schedule edit must not abort the chat
 * turn that attempted it (mirrors [TaskBoardRepository]'s `BoardMutationResult`).
 *
 * The firing side ([claimDue]/[finishRun]) is the same single-writer discipline: [claimDue] is the
 * atomic claim-and-advance a worker calls when a schedule's window is due — one transaction that
 * decides the single winner, advances/disables the row, and reports the win DIRECTLY as a
 * [ScheduleClaim] (mirrors `TaskRunRepository.claimResume`). [finishRun] clears the in-flight marker
 * once the spawned run reaches a terminal state.
 */
class TaskScheduleRepository(
    private val dao: TaskScheduleDAO,
    private val transactions: BoardTransactionRunner,
    private val resolveAssistant: suspend (Uuid) -> Assistant?,
    private val json: Json = Json,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Create one schedule for [conversationId] owned by [owner]. Validates every gate before any
     * write; the first failing gate returns [ScheduleMutationResult.Rejected] and the transaction
     * touches no row.
     */
    suspend fun create(
        conversationId: Uuid,
        owner: ScheduleOwner,
        draft: ScheduleDraft,
    ): ScheduleMutationResult = transactions.inTransaction {
        if (draft.prompt.length > MAX_PROMPT_CHARS) {
            return@inTransaction ScheduleMutationResult.Rejected(
                "prompt is too long: ${draft.prompt.length} > $MAX_PROMPT_CHARS"
            )
        }

        val target = resolveAssistant(draft.targetAssistantId)
            ?: return@inTransaction ScheduleMutationResult.Rejected(
                "unknown target assistant: ${draft.targetAssistantId}"
            )
        if (!target.spawnable) {
            return@inTransaction ScheduleMutationResult.Rejected(
                "target assistant is not spawnable: ${draft.targetAssistantId}"
            )
        }

        if (draft.kind == ScheduleKind.RECURRING) {
            val spec = parseRecurrenceSpec(draft.recurrenceSpec)
                ?: return@inTransaction ScheduleMutationResult.Rejected(
                    "recurring schedule requires a valid recurrenceSpec"
                )
            val intervalMillis = spec.intervalMillis()
            if (intervalMillis < MIN_RECURRENCE_INTERVAL_MILLIS) {
                return@inTransaction ScheduleMutationResult.Rejected(
                    "recurring interval $intervalMillis ms is below the minimum $MIN_RECURRENCE_INTERVAL_MILLIS ms"
                )
            }
        }

        // Caps count only ENABLED schedules; a fired one-shot (disabled) no longer occupies quota.
        val enabledHere = dao.listByConversation(conversationId.toString()).count { it.enabled }
        if (enabledHere >= MAX_ACTIVE_PER_CONVERSATION) {
            return@inTransaction ScheduleMutationResult.Rejected(
                "per-conversation active schedule cap reached ($MAX_ACTIVE_PER_CONVERSATION)"
            )
        }
        if (dao.countEnabledByOwner(owner.name) >= MAX_ACTIVE_PER_USER) {
            return@inTransaction ScheduleMutationResult.Rejected(
                "per-${owner.name.lowercase()} active schedule cap reached ($MAX_ACTIVE_PER_USER)"
            )
        }

        val timestamp = now()
        val entity = TaskScheduleEntity(
            id = Uuid.random().toString(),
            conversationId = conversationId.toString(),
            targetAssistantId = draft.targetAssistantId.toString(),
            prompt = draft.prompt,
            owner = owner.name,
            kind = draft.kind.name,
            recurrenceSpec = if (draft.kind == ScheduleKind.RECURRING) draft.recurrenceSpec else null,
            timeZoneId = draft.timeZoneId,
            firstFireAt = draft.firstFireAt,
            nextFireAt = draft.firstFireAt,
            enabled = true,
            misfirePolicy = draft.misfirePolicy.name,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        dao.insert(entity)
        ScheduleMutationResult.Accepted(entity.toSnapshot())
    }

    /** Schedules on [conversationId], in presentation order (next_fire_at ascending). */
    suspend fun list(conversationId: Uuid): List<ScheduleSnapshot> = transactions.inTransaction {
        dao.listByConversation(conversationId.toString()).map { it.toSnapshot() }
    }

    /**
     * Delete the schedule [id] iff it belongs to [conversationId]. A row owned by a different
     * conversation (or absent entirely) REJECTS — the scope check is what stops an agent (or UI)
     * bound to one conversation from reaching into another's schedules.
     */
    suspend fun delete(conversationId: Uuid, id: Uuid): ScheduleMutationResult = transactions.inTransaction {
        val row = dao.getById(id.toString())
            ?.takeIf { it.conversationId == conversationId.toString() }
            ?: return@inTransaction ScheduleMutationResult.Rejected("schedule not found: $id")
        val snapshot = row.toSnapshot()
        dao.deleteById(id.toString())
        ScheduleMutationResult.Accepted(snapshot)
    }

    /**
     * Atomically claim schedule [scheduleId]'s due window at [now]. ONE transaction, validate before
     * write: returns null unless the row exists AND is `enabled` AND has no in-flight run AND is due
     * (`nextFireAt <= now`). On a win it mints a fresh `runningTaskRunId`, stamps `lastFiredAt`, and
     * either DISABLES a one-shot or ADVANCES a recurring schedule's `nextFireAt` to the first
     * occurrence strictly after [now] (coalescing every window missed while the process was dead into
     * exactly that one fire — never N catch-up runs). The win is reported DIRECTLY: a non-null
     * [ScheduleClaim] means this caller won, so a second concurrent claim for the same window — now
     * seeing a non-null `runningTaskRunId` — loses with null. Mirrors `TaskRunRepository.claimResume`.
     */
    suspend fun claimDue(scheduleId: Uuid, now: Long): ScheduleClaim? = transactions.inTransaction {
        val row = dao.getById(scheduleId.toString()) ?: return@inTransaction null
        if (!row.enabled || row.runningTaskRunId != null || row.nextFireAt > now) {
            return@inTransaction null
        }

        val advancedNextFire: Long? = when (ScheduleKind.valueOf(row.kind)) {
            ScheduleKind.ONE_SHOT -> null // a one-shot fires once: disable after this claim.
            ScheduleKind.RECURRING -> {
                // A row that passed the create gate always carries a valid recurrence_spec; if it is
                // somehow absent we cannot advance, so we disable rather than spin in place.
                parseRecurrenceSpec(row.recurrenceSpec)?.let { spec ->
                    Recurrence.nextOccurrenceAfter(
                        spec = spec,
                        firstFireAt = row.firstFireAt,
                        lastFiredAt = row.lastFiredAt,
                        zone = ZoneId.of(row.timeZoneId),
                        now = now,
                    )
                }
            }
        }

        val runId = Uuid.random()
        val claimed = row.copy(
            runningTaskRunId = runId.toString(),
            lastFiredAt = now,
            nextFireAt = advancedNextFire ?: row.nextFireAt,
            enabled = advancedNextFire != null,
            updatedAt = now,
        )
        dao.update(claimed)
        ScheduleClaim(runId = runId, snapshot = claimed.toSnapshot())
    }

    /**
     * Mark the fire identified by [runId] finished: clear `running_task_run_id` so the schedule is no
     * longer pinned in-flight and record [terminalTaskRunId] as `last_task_run_id`. Abort-safe — an
     * absent row (the conversation was deleted mid-run) or a stale [runId] (the schedule was already
     * re-claimed) is a no-op, never an exception. One transaction.
     */
    suspend fun finishRun(scheduleId: Uuid, runId: Uuid, terminalTaskRunId: Uuid) {
        transactions.inTransaction<Unit> {
            val row = dao.getById(scheduleId.toString()) ?: return@inTransaction
            // Only the worker that owns the in-flight marker may clear it; a stale finisher (the row
            // was re-claimed under a new runId) must not stomp the live claim.
            if (row.runningTaskRunId != runId.toString()) return@inTransaction
            dao.update(
                row.copy(
                    runningTaskRunId = null,
                    lastTaskRunId = terminalTaskRunId.toString(),
                    updatedAt = now(),
                )
            )
        }
    }

    // --- gate helpers (transaction-internal) ----------------------------------------------------

    private fun parseRecurrenceSpec(raw: String?): RecurrenceSpec? {
        if (raw.isNullOrBlank()) return null
        // A malformed spec (bad JSON, every < 1) is a rejection, not a crash — the create path must
        // stay abort-safe, so a parse failure folds into the same Rejected the gate returns.
        return runCatching { json.decodeFromString<RecurrenceSpec>(raw) }.getOrNull()
    }

    private fun RecurrenceSpec.intervalMillis(): Long = when (unit) {
        RecurrenceUnit.MINUTES -> every.toLong() * MILLIS_PER_MINUTE
        RecurrenceUnit.HOURS -> every.toLong() * MILLIS_PER_HOUR
        RecurrenceUnit.DAYS -> every.toLong() * MILLIS_PER_DAY
    }

    private fun TaskScheduleEntity.toSnapshot(): ScheduleSnapshot = ScheduleSnapshot(
        id = Uuid.parse(id),
        targetAssistantId = Uuid.parse(targetAssistantId),
        prompt = prompt,
        owner = ScheduleOwner.valueOf(owner),
        kind = ScheduleKind.valueOf(kind),
        firstFireAt = firstFireAt,
        nextFireAt = nextFireAt,
        timeZoneId = timeZoneId,
        recurrenceSpec = recurrenceSpec,
        misfirePolicy = MisfirePolicy.valueOf(misfirePolicy),
        enabled = enabled,
        lastFiredAt = lastFiredAt,
        lastTaskRunId = lastTaskRunId?.let { Uuid.parse(it) },
        runningTaskRunId = runningTaskRunId?.let { Uuid.parse(it) },
    )

    companion object {
        /** Max enabled schedules per conversation (spec assumption 3). */
        const val MAX_ACTIVE_PER_CONVERSATION: Int = 20

        /** Max enabled schedules per owner class (spec assumption 3/4). */
        const val MAX_ACTIVE_PER_USER: Int = 100

        /**
         * Minimum recurring interval (spec assumption 3): 15 minutes, aligned with WorkManager's own
         * minimum periodic interval — promising a tighter cadence than the transport can honor (and
         * the battery drain it implies) is forbidden.
         */
        const val MIN_RECURRENCE_INTERVAL_MILLIS: Long = 15L * 60 * 1000

        /** Maximum schedule prompt length (spec assumption 3). */
        const val MAX_PROMPT_CHARS: Int = 8000

        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
        private const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
    }
}
