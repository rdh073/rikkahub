package me.rerere.rikkahub.data.ai.schedule

import kotlinx.coroutines.runBlocking
import me.rerere.ai.runtime.contract.MisfirePolicy
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.contract.ScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * JVM unit tests for [ScheduleRescheduler] — the cold-start pass [RikkaHubApp] runs after
 * `recoverTasks()` (SPEC.md M6 / task T11). It is pure policy driven by narrow injected seams (a
 * snapshot reader, an orphan-run predicate, an orphan-clear mutation, the enqueue transport), so it
 * is testable on the JVM unit path with fakes — no Android, mirroring [ScheduleWorkerTest].
 *
 * The contract these tests pin (the T11 acceptance):
 *  - every overdue enabled schedule is re-enqueued via the [ScheduleEnqueuer] seam at its
 *    `nextFireAt`, so a fire missed while the process was dead fires on the next opportunity;
 *  - an ORPHAN `running_task_run_id` — one pointing at a run the recovery pass already marked
 *    `Interrupted`, or at a run that no longer exists — is CLEARED, so a killed fire never pins its
 *    schedule "running" forever (the claim filters on `running_task_run_id == null`, so an
 *    uncleared orphan would block every future fire).
 */
class ScheduleReschedulerTest {

    private fun snapshot(
        id: Uuid = Uuid.random(),
        nextFireAt: Long,
        enabled: Boolean = true,
        runningTaskRunId: Uuid? = null,
    ): ScheduleSnapshot = ScheduleSnapshot(
        id = id,
        targetAssistantId = Uuid.random(),
        prompt = "go",
        owner = ScheduleOwner.USER,
        kind = ScheduleKind.RECURRING,
        firstFireAt = 1_000L,
        nextFireAt = nextFireAt,
        timeZoneId = "UTC",
        recurrenceSpec = """{"every":15,"unit":"MINUTES"}""",
        misfirePolicy = MisfirePolicy.FIRE_ONCE_AND_COALESCE,
        enabled = enabled,
        lastFiredAt = 1_000L,
        lastTaskRunId = null,
        runningTaskRunId = runningTaskRunId,
    )

    private fun rescheduler(
        overdue: List<ScheduleSnapshot>,
        orphanRunIds: Set<Uuid> = emptySet(),
        cleared: MutableList<Uuid> = mutableListOf(),
        enqueued: MutableList<Pair<Uuid, Long>> = mutableListOf(),
    ): ScheduleRescheduler = ScheduleRescheduler(
        listOverdueEnabled = { overdue },
        isRunOrphan = { runId -> runId in orphanRunIds },
        clearOrphanRunning = { id -> cleared += id },
        enqueue = { id, fireAt -> enqueued += id to fireAt },
        now = { 10_000L },
    )

    @Test
    fun `overdue enabled schedules are re-enqueued at their next fire time`() = runBlocking {
        val a = snapshot(nextFireAt = 2_000L)
        val b = snapshot(nextFireAt = 5_000L)
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val rescheduler = rescheduler(overdue = listOf(a, b), enqueued = enqueued)

        rescheduler.rescheduleOverdue()

        assertEquals(
            listOf(a.id to 2_000L, b.id to 5_000L),
            enqueued,
        )
    }

    @Test
    fun `an orphan running id is cleared before the schedule is re-enqueued`() = runBlocking {
        val orphanRun = Uuid.random()
        val pinned = snapshot(nextFireAt = 2_000L, runningTaskRunId = orphanRun)
        val cleared = mutableListOf<Uuid>()
        val enqueued = mutableListOf<Pair<Uuid, Long>>()
        val rescheduler = rescheduler(
            overdue = listOf(pinned),
            orphanRunIds = setOf(orphanRun),
            cleared = cleared,
            enqueued = enqueued,
        )

        rescheduler.rescheduleOverdue()

        // The orphan marker is cleared so a future claim is not blocked by a dead fire...
        assertEquals(listOf(pinned.id), cleared)
        // ...and the schedule is still re-enqueued so it actually fires again.
        assertEquals(listOf(pinned.id to 2_000L), enqueued)
    }

    @Test
    fun `a live running id is NOT cleared`() = runBlocking {
        val liveRun = Uuid.random()
        val running = snapshot(nextFireAt = 2_000L, runningTaskRunId = liveRun)
        val cleared = mutableListOf<Uuid>()
        val rescheduler = rescheduler(
            overdue = listOf(running),
            orphanRunIds = emptySet(), // liveRun is not orphan: a real in-flight run owns it.
            cleared = cleared,
        )

        rescheduler.rescheduleOverdue()

        assertTrue("a live in-flight run must not have its marker cleared", cleared.isEmpty())
    }
}
