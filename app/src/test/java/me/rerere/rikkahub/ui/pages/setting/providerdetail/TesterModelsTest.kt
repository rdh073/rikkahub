package me.rerere.rikkahub.ui.pages.setting.providerdetail

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Regression test for: "newly added model not visible in the connection tester until reload"
 * (issue #208).
 *
 * The connection tester must list the LIVE persisted model set, not the Config-tab draft's
 * stale snapshot. The draft is re-keyed on provider.id only, so a model added in the Models
 * tab persists into [provider] (persisted) while the draft keeps its old model list — the
 * tester, fed the draft, never showed the new model until exit+re-enter re-seeded the draft.
 *
 * The fix feeds the tester [mergeConfigKeepingModels] (draft config + persisted models), so
 * its model source becomes the persisted list. This asserts that primitive — the exact one
 * the production wiring passes to ProviderConnectionTester. The Compose state plumbing (draft
 * fed into the live tester composable) has no pure JVM seam; it is covered by
 * compileDebugKotlin + lintDebug and a manual on-device check documented in the PR body.
 */
class TesterModelsTest {

    @Test
    fun testerModelSource_isPersistedSet_notStaleDraftSnapshot() {
        val id = Uuid.random()
        val modelA = Model(modelId = "a", displayName = "A")
        // modelB = the model just added in the Models tab; Model() defaults type = CHAT.
        val modelB = Model(modelId = "b", displayName = "B")

        // Draft = the Config-tab edit with its STALE model snapshot [A] (pre-add).
        val draft = ProviderSetting.OpenAI(
            id = id,
            apiKey = "edited-key",
            baseUrl = "https://edited",
            models = listOf(modelA),
        )
        // Persisted = settings right now: model B was added in the Models tab.
        val persisted = ProviderSetting.OpenAI(
            id = id,
            apiKey = "saved-key",
            baseUrl = "https://api.openai.com/v1",
            models = listOf(modelA, modelB),
        )

        val testerModels = mergeConfigKeepingModels(draft, persisted).models

        // Pre-fix the tester read draft.models ([A]) — B absent. The fix reads the merged
        // (= persisted) models, so the just-added B is visible immediately.
        assertTrue(
            "tester must see the newly-added model B",
            testerModels.any { it.id == modelB.id },
        )
        assertEquals(persisted.models, testerModels)
    }

    @Test
    fun testerModelSet_equalsPersistedSet_forAnyDraftSnapshot() {
        val id = Uuid.random()
        val modelA = Model(modelId = "a", displayName = "A")
        val modelB = Model(modelId = "b", displayName = "B")
        val modelC = Model(modelId = "c", displayName = "C")
        val ghost = Model(modelId = "ghost", displayName = "deleted elsewhere")

        // The live persisted model set the tester must always reflect.
        val persisted = ProviderSetting.OpenAI(
            id = id,
            apiKey = "saved-key",
            baseUrl = "https://api.openai.com/v1",
            models = listOf(modelA, modelB, modelC),
        )

        // Invariant property: regardless of what the draft's snapshot is — empty, reordered,
        // or carrying a stale model that no longer exists — the tester model set tracks
        // the persisted set exactly.
        val draftSnapshots = listOf(
            emptyList(),
            listOf(modelC, modelA, modelB),
            listOf(modelA, ghost),
        )
        for (snapshot in draftSnapshots) {
            val draft = ProviderSetting.OpenAI(
                id = id,
                apiKey = "edited-key",
                baseUrl = "https://edited",
                models = snapshot,
            )
            val testerModels = mergeConfigKeepingModels(draft, persisted).models
            assertEquals(
                persisted.models.map { it.id },
                testerModels.map { it.id },
            )
        }
    }
}
