package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.json.JsonInstant
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization-compatibility GATE for the #243 `:ai-runtime` carve-out (slice 4/10).
 *
 * The upcoming package moves (slices 5-10) relocate `@Serializable` model types across modules.
 * kotlinx-serialization keys polymorphic (sealed) members and the default class discriminator off
 * STRING names, not the Kotlin FQCN:
 *   - the default `classDiscriminator` is the literal key `"type"`,
 *   - each sealed subtype is tagged by its `@SerialName(...)` value.
 * Neither is affected by which package the class lives in — UNLESS a move accidentally edits a
 * `@SerialName`, drops one, or swaps the discriminator key. Any of those silently breaks every
 * already-persisted on-disk blob (a DataStore Settings/Assistant sub-blob or a Room Conversation/
 * MessageNode column), because the OLD bytes carry the OLD discriminator string.
 *
 * This test pins the OLD on-disk shape as FROZEN string literals (never built from the live data
 * classes, so they cannot drift when a model changes) and asserts the production-persistence
 * serializer `me.rerere.common.json.JsonInstant` (ConversationRepository persists Conversation/
 * MessageNode through it; PreferencesStore persists the providers/assistants sub-blobs through it):
 *   (a) DECODES the frozen fixture without throwing (guards MissingFieldException + unknown
 *       discriminator), and
 *   (b) RE-ENCODES it while keeping every nested polymorphic `"type"` discriminator value intact.
 *
 * The assertion is structural, NOT raw-string identity: app `JsonInstant` uses the default
 * `explicitNulls = true`, so re-encode emits omitted nulls and reorders keys — expected, not a
 * regression. So the test walks the re-encoded tree and compares the SET of `"type"` discriminator
 * values found at the polymorphic paths against the frozen expected set.
 *
 * Pure JVM JUnit4, no Robolectric: none of these types touch android.* at encode/decode time
 * (Uri/Log live only in non-serialized helpers/@Transient fields; kotlin.uuid.Uuid serializes as a
 * plain string).
 */
class RuntimeSerializationCompatibilityTest {

    // ---- helpers -------------------------------------------------------------------------------

    private fun reencodeTree(decodedReencoded: String): JsonObject =
        JsonInstant.parseToJsonElement(decodedReencoded) as JsonObject

    /** Collect every `"type"` discriminator value anywhere in the tree (recursively). */
    private fun collectDiscriminators(element: JsonElement, acc: MutableList<String>) {
        when (element) {
            is JsonObject -> {
                element["type"]?.let { t ->
                    runCatching { t.jsonPrimitive.content }.getOrNull()?.let { acc.add(it) }
                }
                element.values.forEach { collectDiscriminators(it, acc) }
            }

            is JsonArray -> element.forEach { collectDiscriminators(it, acc) }
            else -> {}
        }
    }

    private fun discriminatorsOf(json: JsonObject): Set<String> {
        val acc = mutableListOf<String>()
        collectDiscriminators(json, acc)
        return acc.toSet()
    }

    // ---- (1) Settings --------------------------------------------------------------------------

    @Test
    fun `settings legacy json round-trips and keeps SerialName discriminator semantics`() {
        // Frozen pre-move Settings exercising the persisted polymorphic members: ProviderSetting
        // (openai/google/claude), Assistant.localTools (time_info/clipboard), a presetMessages
        // UIMessage with a text part, an AssistantRegex, plus a modeInjections entry and a Lorebook
        // entry.
        //
        // NOTE on modeInjections / lorebooks[].entries: their persisted field types are the CONCRETE
        // subtypes (`List<PromptInjection.ModeInjection>` / `List<PromptInjection.RegexInjection>`),
        // NOT the sealed base `PromptInjection`. kotlinx-serialization therefore does NOT emit a
        // "type" discriminator for them on disk (a discriminator is only written when the static
        // type is the sealed base, which never happens in persistence — `List<PromptInjection>` is
        // used only in transient transformer logic). So the frozen fixture omits "type" on those
        // entries, and the discriminator-set assertion below does NOT expect "mode"/"regex"; their
        // compatibility is instead guarded by the value-equality round-trip at the end of this test.
        val legacySettings = """
            {
              "providers": [
                { "type": "openai", "name": "OpenAI", "apiKey": "k", "baseUrl": "https://api.openai.com/v1" },
                { "type": "google", "name": "Google", "apiKey": "k" },
                { "type": "claude", "name": "Claude", "apiKey": "k" }
              ],
              "assistants": [
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "name": "Legacy Assistant",
                  "localTools": [ { "type": "time_info" }, { "type": "clipboard" } ],
                  "presetMessages": [
                    {
                      "id": "22222222-2222-2222-2222-222222222222",
                      "role": "user",
                      "parts": [ { "type": "text", "text": "hello" } ]
                    }
                  ],
                  "regexes": [
                    {
                      "id": "33333333-3333-3333-3333-333333333333",
                      "name": "r",
                      "findRegex": "a",
                      "replaceString": "b",
                      "affectingScope": [ "USER", "ASSISTANT" ]
                    }
                  ]
                }
              ],
              "modeInjections": [
                { "id": "44444444-4444-4444-4444-444444444444", "name": "study" }
              ],
              "lorebooks": [
                {
                  "id": "55555555-5555-5555-5555-555555555555",
                  "name": "lb",
                  "entries": [
                    { "id": "66666666-6666-6666-6666-666666666666", "name": "e" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Settings>(legacySettings)
        val tree = reencodeTree(JsonInstant.encodeToString(decoded))
        val discriminators = discriminatorsOf(tree)

        // The load-bearing assertion: every PERSISTED discriminator survives the round-trip.
        val expected = setOf(
            "openai", "google", "claude", // ProviderSetting
            "time_info", "clipboard",     // LocalToolOption
            "text",                       // UIMessagePart
        )
        assertTrue(
            "missing discriminators after Settings round-trip: ${expected - discriminators}",
            discriminators.containsAll(expected)
        )

        // Value-equality round-trip guards the concrete-subtype fields (modeInjections /
        // lorebooks[].entries) that carry no on-disk "type" discriminator: a package move that
        // broke their structural shape would flip this even without a discriminator change.
        val redecoded = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(decoded))
        assertEquals(decoded.modeInjections, redecoded.modeInjections)
        assertEquals(decoded.lorebooks, redecoded.lorebooks)
        assertEquals(decoded.assistants, redecoded.assistants)
        assertEquals(decoded.providers, redecoded.providers)
    }

    // ---- (2) Assistant -------------------------------------------------------------------------

    @Test
    fun `assistant legacy json round-trips and keeps localTools + affectingScope SerialNames`() {
        // Sparse on purpose (most fields omitted) to prove defaults still apply on decode.
        val legacyAssistant = """
            {
              "id": "77777777-7777-7777-7777-777777777777",
              "name": "Sparse",
              "localTools": [ { "type": "javascript_engine" }, { "type": "ask_user" } ],
              "regexes": [
                {
                  "id": "88888888-8888-8888-8888-888888888888",
                  "affectingScope": [ "USER" ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Assistant>(legacyAssistant)
        // localTools discriminators decoded into the right subtypes.
        assertEquals(
            listOf(
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.JavascriptEngine,
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.AskUser,
            ),
            decoded.localTools
        )
        assertEquals(setOf(AssistantAffectScope.USER), decoded.regexes.single().affectingScope)

        val tree = reencodeTree(JsonInstant.encodeToString(decoded))
        val discriminators = discriminatorsOf(tree)
        assertTrue(
            "localTools discriminators not preserved: ${discriminators}",
            discriminators.containsAll(setOf("javascript_engine", "ask_user"))
        )
        // affectingScope enum SerialNames (enum entries serialize by name) survive.
        val scope = (tree["regexes"] as JsonArray)
            .map { it as JsonObject }
            .single()["affectingScope"] as JsonArray
        assertEquals(listOf("USER"), scope.map { it.jsonPrimitive.content })
    }

    // ---- (3) Conversation ----------------------------------------------------------------------

    @Test
    fun `conversation legacy json round-trips and keeps part + approval + annotation discriminators`() {
        // Cover the persisted UIMessagePart subtypes plus a Tool whose approvalState is the
        // polymorphic Denied/Answered, and a url_citation annotation.
        val legacyConversation = """
            {
              "id": "99999999-9999-9999-9999-999999999999",
              "assistantId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
              "title": "Legacy chat",
              "createAt": "2024-01-01T00:00:00Z",
              "updateAt": "2024-01-02T00:00:00Z",
              "messageNodes": [
                {
                  "id": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                  "selectIndex": 0,
                  "messages": [
                    {
                      "id": "cccccccc-cccc-cccc-cccc-cccccccccccc",
                      "role": "user",
                      "parts": [
                        { "type": "text", "text": "look at this" },
                        { "type": "image", "url": "file:///x.png" },
                        { "type": "document", "url": "file:///d.pdf", "fileName": "d.pdf" }
                      ]
                    },
                    {
                      "id": "dddddddd-dddd-dddd-dddd-dddddddddddd",
                      "role": "assistant",
                      "parts": [
                        { "type": "reasoning", "reasoning": "thinking" },
                        {
                          "type": "tool",
                          "toolCallId": "call_1",
                          "toolName": "t",
                          "input": "{}",
                          "output": [ { "type": "text", "text": "result" } ],
                          "approvalState": { "type": "denied", "reason": "no" }
                        },
                        {
                          "type": "tool",
                          "toolCallId": "call_2",
                          "toolName": "ask",
                          "input": "{}",
                          "output": [],
                          "approvalState": { "type": "answered", "answer": "yes" }
                        }
                      ],
                      "annotations": [
                        { "type": "url_citation", "title": "src", "url": "https://example.com" }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<Conversation>(legacyConversation)
        val tree = reencodeTree(JsonInstant.encodeToString(decoded))
        val discriminators = discriminatorsOf(tree)

        val expected = setOf(
            "text", "image", "document", "reasoning", "tool", // UIMessagePart subtypes
            "denied", "answered",                              // ToolApprovalState
            "url_citation",                                    // UIMessageAnnotation
        )
        assertTrue(
            "missing discriminators after Conversation round-trip: ${expected - discriminators}",
            discriminators.containsAll(expected)
        )
        // Value-equality cross-check: data classes round-trip identically.
        val redecoded = JsonInstant.decodeFromString<Conversation>(JsonInstant.encodeToString(decoded))
        assertEquals(decoded, redecoded)
    }

    // ---- (4) MessageNode (standalone column blob) ----------------------------------------------

    @Test
    fun `messageNode legacy json round-trips and keeps part discriminators`() {
        val legacyNode = """
            {
              "id": "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
              "selectIndex": 0,
              "messages": [
                {
                  "id": "ffffffff-ffff-ffff-ffff-ffffffffffff",
                  "role": "assistant",
                  "parts": [
                    { "type": "reasoning", "reasoning": "r" },
                    { "type": "text", "text": "answer" },
                    {
                      "type": "tool",
                      "toolCallId": "c",
                      "toolName": "t",
                      "input": "{}",
                      "output": [ { "type": "text", "text": "ok" } ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<MessageNode>(legacyNode)
        val tree = reencodeTree(JsonInstant.encodeToString(decoded))
        val discriminators = discriminatorsOf(tree)

        assertTrue(
            "missing part discriminators after MessageNode round-trip",
            discriminators.containsAll(setOf("reasoning", "text", "tool"))
        )
        val redecoded = JsonInstant.decodeFromString<MessageNode>(JsonInstant.encodeToString(decoded))
        assertEquals(decoded, redecoded)
    }
}
