package me.rerere.rikkahub.data.ai.task

import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetBreach
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import kotlin.uuid.Uuid

/**
 * The narrow persistence seam [TaskCoordinator] depends on (DIP). The concrete is
 * `TaskRunRepository` (Room-backed), bound at the composition root; tests inject an in-memory fake.
 *
 * Keeping this an interface — rather than the concrete repository — is what makes the coordinator
 * JVM-unit-testable without Room: every method here is exactly the repository surface the
 * coordinator drives, no wider.
 */
interface TaskRunStore {
    suspend fun create(spec: TaskSpec): TaskState

    suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState?

    suspend fun appendEventSummary(
        taskId: Uuid,
        summary: String,
        kind: String = "progress",
    ): Long?

    suspend fun recordUsage(
        taskId: Uuid,
        reported: TaskBudgetUsage,
        budget: TaskBudget = TaskBudget(),
    ): TaskBudgetBreach?
}

/**
 * A no-op store for runs that need no persistence (e.g. a unit-test path that only asserts the
 * returned text). Every method is inert: create returns [TaskState.Created], events are dropped,
 * usage never breaches. Used as the [TaskCoordinator] default so the simplest constructor stays
 * usable without wiring a repository.
 */
object NoopTaskRunStore : TaskRunStore {
    override suspend fun create(spec: TaskSpec): TaskState = TaskState.Created
    override suspend fun applyEvent(taskId: Uuid, event: TaskEvent): TaskState? = null
    override suspend fun appendEventSummary(taskId: Uuid, summary: String, kind: String): Long? = null
    override suspend fun recordUsage(taskId: Uuid, reported: TaskBudgetUsage, budget: TaskBudget): TaskBudgetBreach? = null
}
