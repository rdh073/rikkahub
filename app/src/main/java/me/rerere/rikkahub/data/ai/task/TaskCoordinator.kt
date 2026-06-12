package me.rerere.rikkahub.data.ai.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.runtime.GenerationChunk
import me.rerere.ai.runtime.contract.TurnConfig
import me.rerere.ai.runtime.subagent.extractFinalAssistantText
import me.rerere.ai.runtime.subagent.filterToolsForSubagent
import me.rerere.ai.runtime.subagent.resolveSubagentModel
import me.rerere.ai.runtime.task.TaskBudget
import me.rerere.ai.runtime.task.TaskBudgetUsage
import me.rerere.ai.runtime.task.TaskEvent
import me.rerere.ai.runtime.task.TaskSpec
import me.rerere.ai.runtime.task.TaskState
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.runtime.toAssistantConfig
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import me.rerere.rikkahub.data.ai.subagent.SubagentGenerate
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * The lifecycle-aware orchestrator that runs one self-contained sub-task against another
 * [Assistant] (SPEC.md M4). It is the product replacement for `SubagentRunner`: same final-text
 * return so the spawn tool's output still lands in `UIMessagePart.Tool`, but with three additions
 * the runner lacked —
 *
 *  1. **A persisted task run.** Every run creates a [TaskState.Created] row via [store] and drives
 *     it through the pure `TaskStateReducer` (Enqueued -> SlotClaimed -> ChildProgressed ->
 *     FinalResult, or a failure/budget edge). The store is the single state authority; the
 *     coordinator only *emits* events, never writes a tag itself — so the persisted state can
 *     never disagree with TASK_STATE_LEGAL.
 *  2. **Budget + concurrency enforcement.** A [Semaphore] of [TaskBudget.globalConcurrency] caps
 *     in-flight children process-wide; a per-parent [Mutex] caps them at
 *     [TaskBudget.perParentConcurrency] per spawning tool call. Acquiring a slot is the natural
 *     coroutine queue: a run that cannot claim immediately simply suspends in [TaskState.Queued]
 *     until one frees — no spin, no sleep. Step/token/wall-time caps are checked against the
 *     child's reported usage; the first breach fires [TaskEvent.BudgetExceeded] and stops the run
 *     in [TaskState.BudgetExhausted].
 *  3. **It never bypasses `generateText`.** The engine seam is [GenerationHandler.generateText]
 *     (injected as [generate]); driving the child through it preserves the PreToolUse hook
 *     dispatch `GenerationHandler` already wires into `ChatTurnRuntime` (SPEC re-grounding row 2).
 *
 * SoC: like the runner it replaces, this is intentionally NOT the heavy `ChatService`. It calls the
 * agentic engine directly and collects the resulting [kotlinx.coroutines.flow.Flow] inline in the
 * caller's coroutine, so cancellation is inherited via structured concurrency (parent generation
 * job cancelled => this collection cancelled). Conversation persistence lives only in
 * `ChatService.saveConversation`, never in `generateText`, so a child never touches the
 * conversation Room tables.
 */
class TaskCoordinator(
    private val generate: SubagentGenerate,
    private val store: TaskRunStore = NoopTaskRunStore,
    private val defaultBudget: TaskBudget = TaskBudget(),
    private val monotonicNow: () -> Duration = { Duration.ZERO },
) {
    /** DI/composition-root constructor: bind the engine to [GenerationHandler.generateText]. */
    constructor(
        generationHandler: GenerationHandler,
        store: TaskRunStore,
        defaultBudget: TaskBudget = TaskBudget(),
        monotonicNow: () -> Duration = { Duration.ZERO },
    ) : this(
        generate = { settings, model, messages, assistant, tools, maxSteps, processingStatus ->
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = tools,
                maxSteps = maxSteps,
                processingStatus = processingStatus,
            )
        },
        store = store,
        defaultBudget = defaultBudget,
        monotonicNow = monotonicNow,
    )

    /** Process-wide concurrency gate. Recomputed per cap so a test/override budget is honored. */
    private val globalSemaphores = ConcurrentHashMap<Int, Semaphore>()

    /** Per-parent-tool-call concurrency gate. Keyed by the spawn tool call id. */
    private val parentMutexes = ConcurrentHashMap<String, Mutex>()

    private fun globalSemaphore(permits: Int): Semaphore =
        globalSemaphores.getOrPut(permits) { Semaphore(permits.coerceAtLeast(1)) }

    /**
     * Run a sub-task to completion and return its final text (parity with `SubagentRunner.run`).
     *
     * @param parentToolCallId the spawn tool call that owns this child; the per-parent concurrency
     *   cap is keyed on it (a `null`/blank id means "no parent grouping" — only the global cap
     *   applies). Defaults blank so callers that don't track it still get global gating.
     * @param tools the child's tool pool; [filterToolsForSubagent] strips the spawn tool from it
     *   unconditionally (the depth-1 recursion guard, TASK_DEPTH_ONE) before it reaches the engine.
     */
    suspend fun run(
        sub: Assistant,
        prompt: String,
        parentModelId: Uuid?,
        settings: Settings,
        tools: List<Tool> = emptyList(),
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        parentConversationId: Uuid = Uuid.random(),
        parentToolCallId: String = "",
        taskId: Uuid = Uuid.random(),
        budget: TaskBudget = defaultBudget,
    ): String {
        val modelId = resolveSubagentModel(
            sub = sub.toAssistantConfig(),
            parentModelId = parentModelId,
            turn = TurnConfig(
                defaultModelId = settings.chatModelId,
                providers = emptyList(),
                assistants = emptyList(),
            ),
        )
        val model = settings.findModelById(modelId)
            ?: error("Subagent model not found for id $modelId")

        // Force memory OFF on the ephemeral sub so a throwaway child can never read/write the
        // PARENT's memory (the C1 data-integrity hazard); identical to the runner it replaces.
        val ephemeralSub = sub.copy(
            chatModelId = model.id,
            enableMemory = false,
            enableRecentChatsReference = false,
        )

        val maxSteps = sub.maxSteps ?: budget.maxSteps
        val messages = listOf(UIMessage.user(prompt))
        // The depth-1 recursion guard (TASK_DEPTH_ONE): the spawn tool is stripped from EVERY child
        // pool unconditionally, regardless of how the caller assembled it.
        val childTools = filterToolsForSubagent(tools, SPAWN_TOOL_NAME)

        store.create(
            TaskSpec(
                taskId = taskId,
                parentConversationId = parentConversationId,
                parentToolCallId = parentToolCallId,
                agentTypeId = sub.id.toString(),
                prompt = prompt,
                parentModelId = parentModelId,
                budget = budget,
            )
        )
        // Created -> Queued: the spawn tool call was accepted; the run now waits for a slot.
        store.applyEvent(taskId, TaskEvent.Enqueued)

        val parentMutex = if (parentToolCallId.isNotBlank()) {
            parentMutexes.getOrPut(parentToolCallId) { Mutex() }
        } else {
            null
        }

        // Acquire the global slot first, then (optionally) the per-parent slot. Acquisition is the
        // queue: a child with no free slot suspends here in TaskState.Queued. Ordering global-then-
        // parent consistently for every run prevents a lock-acquisition cycle.
        return globalSemaphore(budget.globalConcurrency).withPermit {
            if (parentMutex != null) {
                parentMutex.withLock { execute(taskId, settings, model, messages, ephemeralSub, childTools, maxSteps, processingStatus, budget) }
            } else {
                execute(taskId, settings, model, messages, ephemeralSub, childTools, maxSteps, processingStatus, budget)
            }
        }
    }

    /**
     * The slot-acquired body: drive the child through the engine seam, fold usage against the
     * budget, and terminate the run. Separated from [run] so the concurrency wrappers stay thin.
     */
    private suspend fun execute(
        taskId: Uuid,
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        ephemeralSub: Assistant,
        childTools: List<Tool>,
        maxSteps: Int,
        processingStatus: MutableStateFlow<String?>,
        budget: TaskBudget,
    ): String {
        // Queued -> Starting: a concurrency slot is now held.
        store.applyEvent(taskId, TaskEvent.SlotClaimed)
        val startedAt = monotonicNow()
        var finalMessages: List<UIMessage> = messages
        var progressed = false

        return try {
            generate(settings, model, messages, ephemeralSub, childTools, maxSteps, processingStatus).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        finalMessages = chunk.messages
                        if (!progressed) {
                            // First child event: Starting -> Running.
                            store.applyEvent(taskId, TaskEvent.ChildProgressed)
                            progressed = true
                        }
                        // Fold the child's CUMULATIVE usage (steps from assistant turns, tokens from
                        // the message usage counters, elapsed from the monotonic clock) and stop the
                        // run on the first cap breach.
                        val breach = store.recordUsage(taskId, usageOf(chunk.messages, startedAt), budget)
                        if (breach != null) {
                            store.applyEvent(taskId, TaskEvent.BudgetExceeded(breach))
                            return@collect
                        }
                    }
                }
            }
            // If a budget breach already terminated the run, a FinalResult on the absorbing
            // BudgetExhausted terminal is a reducer no-op — but extracting text is still correct as
            // the parent-visible partial summary.
            val result = extractFinalAssistantText(finalMessages)
            store.applyEvent(taskId, TaskEvent.FinalResult(result))
            result
        } catch (cancellation: CancellationException) {
            // Structured cancellation (parent generation stopped): surface the cancel to the
            // lifecycle, then rethrow so the coroutine tree tears down correctly.
            store.applyEvent(taskId, TaskEvent.CancelRequested)
            throw cancellation
        } catch (error: Throwable) {
            // Provider/unexpected error: Failed terminal, surfaced to the parent as a structured
            // tool-error string (existing tool-error-as-output behavior) rather than a thrown
            // exception that would abort the parent turn.
            val reason = error.message ?: error::class.simpleName.orEmpty()
            store.applyEvent(taskId, TaskEvent.ExecutionFailed(reason))
            "Subagent failed: $reason"
        }
    }

    /**
     * The child's cumulative usage at this point in the stream: one step per assistant message,
     * total tokens summed across message usage counters, elapsed time from the monotonic clock.
     * Cumulative (not per-chunk) so [TaskBudgetUsage.record]'s component-wise max is a no-op merge
     * and stale/out-of-order chunks stay harmless (TASK_BUDGET_MONOTONE).
     */
    private fun usageOf(messages: List<UIMessage>, startedAt: Duration): TaskBudgetUsage {
        val steps = messages.count { it.role == MessageRole.ASSISTANT }
        val tokens = messages.sumOf { (it.usage?.totalTokens ?: 0).toLong() }
        val elapsed = (monotonicNow() - startedAt).coerceAtLeast(Duration.ZERO)
        return TaskBudgetUsage(steps = steps, tokens = tokens, elapsed = elapsed)
    }
}
