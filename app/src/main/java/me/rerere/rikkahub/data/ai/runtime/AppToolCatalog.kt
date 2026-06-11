package me.rerere.rikkahub.data.ai.runtime

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.ToolCatalog
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.subagent.filterToolsForSubagent
import me.rerere.rikkahub.service.mapMcpTool
import kotlin.uuid.Uuid

/**
 * App-side [ToolCatalog] reproducing the security-relevant tool-assembly policy of
 * `ChatService.buildGenerationTools` (issue #243 slice 3, ChatService.kt §buildGenerationTools), so
 * the runtime can depend on the neutral [ToolCatalog] abstraction. Behavior-preserving: this slice
 * only ADDS the adapter + its tests; ChatService is NOT rewired onto it (that is slice 10).
 *
 * The catalog reproduces the THREE invariants the policy tests pin, delegating to the already-shared
 * seams so no policy is duplicated:
 *  - MCP tools are selected off the TARGET assistant ([mcpToolsForAssistant], keyed on
 *    `ctx.targetAssistant`) and named `mcp__…` via [mapMcpTool] — the §C3 by-target invariant + the
 *    canonical prefix.
 *  - the spawn tool is added only on a Main turn with `includeSpawnTool` true ([spawnTool] non-null)
 *    — the structural recursion guard ([filterToolsForSubagent] additionally strips it on subagent
 *    pools, belt-and-suspenders).
 *  - `needsApproval` tools are stripped when `allowApprovalTools` is false (subagent pools), matching
 *    the spawn-site `.filterNot { it.needsApproval }`.
 *
 * The non-security base pool (local / search / workspace / skill / automation tools) is provided via
 * the injected [baseTools] seam — the portion ChatService rewires onto this catalog in slice 10.
 * Reproducing that closure state here would duplicate policy slice 10 must then delete, so it is
 * injected, not copied.
 */
class AppToolCatalog(
    private val baseTools: suspend (target: AssistantConfig, mode: TurnMode) -> List<Tool>,
    private val mcpToolsForAssistant: (target: AssistantConfig) -> List<Pair<Uuid, McpTool>>,
    private val mcpCall: suspend (serverId: Uuid, toolName: String, args: JsonObject) -> List<UIMessagePart>,
    private val spawnTool: (parentModelId: Uuid?) -> Tool?,
) : ToolCatalog {

    override suspend fun tools(ctx: ToolAssemblyContext): List<Tool> {
        val pool = buildList {
            addAll(baseTools(ctx.targetAssistant, ctx.mode))
            mcpToolsForAssistant(ctx.targetAssistant).forEach { (serverId, tool) ->
                add(mapMcpTool(serverId, tool) { sid, name, args -> mcpCall(sid, name, args) })
            }
            if (ctx.mode == TurnMode.Main && ctx.includeSpawnTool) {
                spawnTool(ctx.parentModelId)?.let { add(it) }
            }
        }
        // Subagent pools (allowApprovalTools=false) drop approval-gated tools, and never carry the
        // spawn tool regardless of source (recursion guard).
        return if (ctx.allowApprovalTools) {
            pool
        } else {
            filterToolsForSubagent(pool).filterNot { it.needsApproval }
        }
    }
}
