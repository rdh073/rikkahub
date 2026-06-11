package me.rerere.rikkahub.data.ai.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.runtime.contract.AssistantConfig
import me.rerere.ai.runtime.contract.ToolAssemblyContext
import me.rerere.ai.runtime.contract.TurnMode
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.subagent.SPAWN_TOOL_NAME
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression guard pinning the REAL [AppToolCatalog] against the production tool-assembly policy
 * (issue #243 slice 3, mirrors `ChatService.buildGenerationTools`). Where `ToolAssemblyPolicyTest`
 * (in `:ai-runtime`) pins the contract with a fake catalog, this test drives the production adapter
 * with fake LocalTools / Mcp / spawn seams so a regression that breaks the four invariants reddens
 * the real adapter, not just a stand-in.
 *
 * Invariants pinned:
 *  - MCP tools are `mcp__`-prefixed (via the real `mapMcpTool` seam).
 *  - MCP selection keys off `ctx.targetAssistant` (subagent does not inherit the parent's servers).
 *  - `needsApproval` tools are stripped on a subagent turn (allowApprovalTools=false).
 *  - the spawn tool is absent on a subagent turn and when `includeSpawnTool` is false (recursion
 *    guard), present on a Main turn that includes it.
 */
class AppToolCatalogPolicyTest {

    private fun assistant(id: Uuid, mcpServers: Set<Uuid>): AssistantConfig = AssistantConfig(
        id = id,
        chatModelId = null,
        systemPrompt = "",
        streamOutput = true,
        enableMemory = false,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        messageTemplate = "{{ message }}",
        regexes = emptyList(),
        reasoningLevel = ReasoningLevel.AUTO,
        maxTokens = null,
        customHeaders = emptyList(),
        customBodies = emptyList(),
        mcpServers = mcpServers,
        localToolIds = emptyList(),
        enabledSkills = emptySet(),
        modeInjectionIds = emptySet(),
        lorebookIds = emptySet(),
        knowledgeBaseId = null,
        description = "",
        spawnable = false,
        subagentMaxSteps = null,
    )

    private fun tool(name: String, needsApproval: Boolean = false): Tool =
        Tool(name = name, description = "", needsApproval = needsApproval, execute = { emptyList() })

    private fun spawnToolStub(): Tool =
        Tool(name = SPAWN_TOOL_NAME, description = "", execute = { emptyList() })

    // serverId -> the McpTool that server exposes.
    private fun catalog(serverTools: Map<Uuid, McpTool>): AppToolCatalog = AppToolCatalog(
        baseTools = { _, _ ->
            listOf(tool("local_read"), tool("dangerous_write", needsApproval = true))
        },
        mcpToolsForAssistant = { target ->
            // The REAL by-target selection idiom: only servers in the TARGET assistant's set.
            target.mcpServers.mapNotNull { id -> serverTools[id]?.let { id to it } }
        },
        mcpCall = { _, _, _ -> emptyList<UIMessagePart>() },
        spawnTool = { spawnToolStub() },
    )

    @Test
    fun mainTurnPrefixesMcpAndKeepsApprovalAndSpawn() = runBlocking {
        val serverA = Uuid.random()
        val catalog = catalog(mapOf(serverA to McpTool(name = "remote_call")))
        val asst = assistant(Uuid.random(), mcpServers = setOf(serverA))

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = asst,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = true,
            )
        ).map { it.name }

        assertTrue("mcp tool carries mcp__ prefix", names.contains("mcp__remote_call"))
        assertTrue("approval tool present in main turn", names.contains("dangerous_write"))
        assertTrue("spawn tool present in main turn", names.contains(SPAWN_TOOL_NAME))
    }

    @Test
    fun subagentTurnStripsApprovalAndSpawn() = runBlocking {
        val serverA = Uuid.random()
        val catalog = catalog(mapOf(serverA to McpTool(name = "remote_call")))
        val sub = assistant(Uuid.random(), mcpServers = setOf(serverA))

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse("approval tool stripped on subagent turn", names.contains("dangerous_write"))
        assertFalse("spawn tool absent on subagent turn", names.contains(SPAWN_TOOL_NAME))
        assertTrue("non-approval mcp tool retained", names.contains("mcp__remote_call"))
    }

    @Test
    fun mcpSelectionKeysOffTargetNotParent() = runBlocking {
        val serverA = Uuid.random()
        val serverB = Uuid.random()
        val catalog = catalog(
            mapOf(serverA to McpTool(name = "alpha"), serverB to McpTool(name = "beta")),
        )
        val sub = assistant(Uuid.random(), mcpServers = setOf(serverB))

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Subagent,
                targetAssistant = sub,
                parentModelId = Uuid.random(),
                allowApprovalTools = false,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertTrue("subagent selects its own server's tool", names.contains("mcp__beta"))
        assertFalse("subagent does not inherit parent's server tool", names.contains("mcp__alpha"))
    }

    @Test
    fun spawnAbsentWhenIncludeFalseOnMain() = runBlocking {
        val catalog = catalog(emptyMap())
        val asst = assistant(Uuid.random(), mcpServers = emptySet())

        val names = catalog.tools(
            ToolAssemblyContext(
                mode = TurnMode.Main,
                targetAssistant = asst,
                parentModelId = null,
                allowApprovalTools = true,
                includeSpawnTool = false,
            )
        ).map { it.name }

        assertFalse(names.contains(SPAWN_TOOL_NAME))
    }
}
