package me.rerere.rikkahub.data.ai.subagent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * The shape of the agentic engine the runner drives. This is the abstraction the runner depends
 * on (DIP); the concrete is [GenerationHandler.generateText], injected at the composition root.
 * Keeping it a function type (not the concrete handler) is what makes the runner JVM-unit-testable
 * with a fake flow — no Context / Provider / network required.
 */
typealias SubagentGenerate = (
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
    assistant: Assistant,
    maxSteps: Int,
    processingStatus: MutableStateFlow<String?>,
) -> Flow<GenerationChunk>

/**
 * The lean orchestrator that runs one self-contained sub-task against another [Assistant] and
 * returns just its final text (issue #201, slice 3).
 *
 * SoC: this is intentionally NOT the heavy [me.rerere.rikkahub.service.ChatService]. It calls the
 * agentic engine ([GenerationHandler.generateText]) DIRECTLY and collects the resulting [Flow]
 * inline in the caller's coroutine. Conversation persistence lives only in
 * `ChatService.saveConversation`, NOT in `generateText`, so direct collection has no
 * conversation-write path — a sub-task never touches Room, never auto-compacts, and never creates a
 * [me.rerere.rikkahub.data.model.Conversation]. Running inline means cancellation is inherited via
 * structured concurrency: when the parent generation's Job is cancelled, this collection is too.
 */
class SubagentRunner(
    private val generate: SubagentGenerate,
) {
    /** DI/composition-root constructor: bind the engine to [GenerationHandler.generateText]. */
    constructor(generationHandler: GenerationHandler) : this(
        generate = { settings, model, messages, assistant, maxSteps, processingStatus ->
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messages,
                assistant = assistant,
                tools = emptyList(),
                maxSteps = maxSteps,
                processingStatus = processingStatus,
            )
        }
    )

    suspend fun run(
        sub: Assistant,
        prompt: String,
        parentModelId: Uuid?,
        settings: Settings,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    ): String {
        val modelId = resolveSubagentModel(sub, parentModelId, settings)
        val model = settings.findModelById(modelId)
            ?: error("Subagent model not found for id $modelId")

        // Force memory OFF on the ephemeral sub-Assistant so a throwaway sub-task can never
        // mutate (or read from) the PARENT's memory. GenerationHandler gates both the
        // create/update/delete memory tools (enableMemory) and the recent-chats prompt
        // (enableRecentChatsReference) on these flags; a subagent must not write into either.
        val ephemeralSub = sub.copy(
            chatModelId = model.id,
            enableMemory = false,
            enableRecentChatsReference = false,
        )

        // A subagent gets a conservative step budget (vs the main loop's 256) to bound runaway
        // spend; a sub-Assistant may raise it via its own maxSteps.
        val maxSteps = sub.maxSteps ?: SUBAGENT_DEFAULT_MAX_STEPS

        // A fresh, throwaway message list — the sub-task starts from just the prompt.
        val messages = listOf(UIMessage.user(prompt))

        var finalMessages: List<UIMessage> = messages
        generate(settings, model, messages, ephemeralSub, maxSteps, processingStatus).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> finalMessages = chunk.messages
            }
        }
        return extractFinalAssistantText(finalMessages)
    }

    companion object {
        /** Default agentic step ceiling for a subagent run when the sub-Assistant pins none. */
        const val SUBAGENT_DEFAULT_MAX_STEPS: Int = 64
    }
}
