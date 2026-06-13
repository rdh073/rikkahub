package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.runtime.contract.ScheduleDraft
import me.rerere.ai.runtime.contract.ScheduleKind
import me.rerere.ai.runtime.contract.ScheduleMutationResult
import me.rerere.ai.runtime.contract.ScheduleOwner
import me.rerere.ai.runtime.schedule.RecurrenceSpec
import me.rerere.ai.runtime.schedule.RecurrenceUnit
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.fakes.FakeBoardTransactions
import me.rerere.rikkahub.data.repository.fakes.FakeTaskScheduleDAO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Tests for [TaskScheduleRepository] (SPEC.md M3 / task T4): the SINGLE legality path UI and tools
 * share. Each create-gate has one test; list and delete are proven scoped to the bound conversation
 * (a foreign id rejects, never silently deletes cross-conversation). Domain rejections come back as
 * [ScheduleMutationResult.Rejected] — never an exception — so a rejected schedule edit cannot abort
 * the chat turn that attempted it.
 */
class TaskScheduleRepositoryTest {

    /** A monotone test clock so updated_at strictly advances per write. */
    private class MutableClock {
        private var t = 1_000L
        fun current(): Long = ++t
    }

    private class Fixture {
        val dao = FakeTaskScheduleDAO()
        val spawnable = Assistant(id = Uuid.random(), name = "agent", spawnable = true)
        val notSpawnable = Assistant(id = Uuid.random(), name = "config", spawnable = false)
        private val registry = mapOf(
            spawnable.id to spawnable,
            notSpawnable.id to notSpawnable,
        )
        val repository = TaskScheduleRepository(
            dao = dao,
            transactions = FakeBoardTransactions(),
            resolveAssistant = { id -> registry[id] },
            now = MutableClock()::current,
        )
    }

    private val zone = "UTC"

    private fun oneShotDraft(
        target: Uuid,
        prompt: String = "remind me",
        firstFireAt: Long = 10_000L,
    ): ScheduleDraft = ScheduleDraft(
        targetAssistantId = target,
        prompt = prompt,
        kind = ScheduleKind.ONE_SHOT,
        firstFireAt = firstFireAt,
        timeZoneId = zone,
    )

    private fun recurringDraft(
        target: Uuid,
        every: Int,
        unit: RecurrenceUnit,
        prompt: String = "morning briefing",
    ): ScheduleDraft = ScheduleDraft(
        targetAssistantId = target,
        prompt = prompt,
        kind = ScheduleKind.RECURRING,
        firstFireAt = 10_000L,
        timeZoneId = zone,
        recurrenceSpec = Json.encodeToString(RecurrenceSpec(every = every, unit = unit)),
    )

    // --- create gates ---------------------------------------------------------------------------

    @Test
    fun create_accepts_a_spawnable_one_shot() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()

        val result = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))

        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
        val snapshot = (result as ScheduleMutationResult.Accepted).snapshot
        assertEquals(f.spawnable.id, snapshot.targetAssistantId)
        assertEquals(ScheduleKind.ONE_SHOT, snapshot.kind)
        assertEquals(ScheduleOwner.USER, snapshot.owner)
        assertTrue(snapshot.enabled)
        // The row landed scoped to the conversation.
        assertNotNull(f.dao.getById(snapshot.id.toString()))
    }

    @Test
    fun create_rejects_an_unknown_target() = runBlocking {
        val f = Fixture()
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(Uuid.random()))
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_a_non_spawnable_target() = runBlocking {
        val f = Fixture()
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.notSpawnable.id))
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_over_the_per_conversation_cap() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
            val r = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            assertTrue("seed $it should be accepted", r is ScheduleMutationResult.Accepted)
        }
        val overflow = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(overflow is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_over_the_per_user_cap() = runBlocking {
        val f = Fixture()
        // Spread enabled USER schedules across many conversations so the per-conversation cap never
        // trips first; only the per-user cap can reject here.
        var created = 0
        var conversationCount = 0
        while (created < TaskScheduleRepository.MAX_ACTIVE_PER_USER) {
            val conversationId = Uuid.random()
            conversationCount++
            repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
                if (created < TaskScheduleRepository.MAX_ACTIVE_PER_USER) {
                    val r = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
                    assertTrue(r is ScheduleMutationResult.Accepted)
                    created++
                }
            }
        }
        val overflow = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(overflow is ScheduleMutationResult.Rejected)
    }

    @Test
    fun per_owner_cap_isolates_agent_from_user() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        // Fill the per-conversation cap with AGENT schedules.
        repeat(TaskScheduleRepository.MAX_ACTIVE_PER_CONVERSATION) {
            f.repository.create(conversationId, ScheduleOwner.AGENT, oneShotDraft(f.spawnable.id))
        }
        // The per-conversation cap counts all enabled schedules regardless of owner, so a USER add
        // on the SAME conversation is still over the conversation cap.
        val sameConv = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(sameConv is ScheduleMutationResult.Rejected)
        // But a USER add on a DIFFERENT conversation is fine: the AGENT fill did not consume the
        // USER per-user quota.
        val otherConv = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
        assertTrue(otherConv is ScheduleMutationResult.Accepted)
    }

    @Test
    fun create_rejects_a_sub_minimum_recurring_interval() = runBlocking {
        val f = Fixture()
        // 1 minute < 15-minute floor.
        val result = f.repository.create(
            Uuid.random(),
            ScheduleOwner.USER,
            recurringDraft(f.spawnable.id, every = 1, unit = RecurrenceUnit.MINUTES),
        )
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_accepts_a_recurring_interval_at_the_minimum() = runBlocking {
        val f = Fixture()
        // Exactly the 15-minute floor is allowed (boundary).
        val result = f.repository.create(
            Uuid.random(),
            ScheduleOwner.USER,
            recurringDraft(f.spawnable.id, every = 15, unit = RecurrenceUnit.MINUTES),
        )
        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
    }

    @Test
    fun create_rejects_a_recurring_draft_with_no_spec() = runBlocking {
        val f = Fixture()
        val draft = ScheduleDraft(
            targetAssistantId = f.spawnable.id,
            prompt = "x",
            kind = ScheduleKind.RECURRING,
            firstFireAt = 10_000L,
            timeZoneId = zone,
            recurrenceSpec = null,
        )
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, draft)
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_rejects_an_over_length_prompt() = runBlocking {
        val f = Fixture()
        val tooLong = "a".repeat(TaskScheduleRepository.MAX_PROMPT_CHARS + 1)
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id, prompt = tooLong))
        assertTrue(result is ScheduleMutationResult.Rejected)
    }

    @Test
    fun create_accepts_a_prompt_at_the_max_length() = runBlocking {
        val f = Fixture()
        val atMax = "a".repeat(TaskScheduleRepository.MAX_PROMPT_CHARS)
        val result = f.repository.create(Uuid.random(), ScheduleOwner.USER, oneShotDraft(f.spawnable.id, prompt = atMax))
        assertTrue("expected Accepted, got $result", result is ScheduleMutationResult.Accepted)
    }

    // --- list / delete scoping ------------------------------------------------------------------

    @Test
    fun list_is_scoped_to_the_bound_conversation() = runBlocking {
        val f = Fixture()
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()
        f.repository.create(conversationA, ScheduleOwner.USER, oneShotDraft(f.spawnable.id, firstFireAt = 30_000L))
        f.repository.create(conversationA, ScheduleOwner.USER, oneShotDraft(f.spawnable.id, firstFireAt = 20_000L))
        f.repository.create(conversationB, ScheduleOwner.USER, oneShotDraft(f.spawnable.id, firstFireAt = 99_000L))

        val listA = f.repository.list(conversationA)

        assertEquals(2, listA.size)
        assertTrue("list must only contain conversationA's schedules", listA.all { snapshot ->
            f.dao.getById(snapshot.id.toString())!!.conversationId == conversationA.toString()
        })
        // Presentation order is by next_fire_at ascending.
        assertEquals(listOf(20_000L, 30_000L), listA.map { it.nextFireAt })
    }

    @Test
    fun delete_removes_a_schedule_in_the_bound_conversation() = runBlocking {
        val f = Fixture()
        val conversationId = Uuid.random()
        val created = f.repository.create(conversationId, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted

        val result = f.repository.delete(conversationId, created.snapshot.id)

        assertTrue(result is ScheduleMutationResult.Accepted)
        assertNull(f.dao.getById(created.snapshot.id.toString()))
        assertTrue(f.repository.list(conversationId).isEmpty())
    }

    @Test
    fun delete_of_a_foreign_conversation_id_rejects_and_does_not_delete() = runBlocking {
        val f = Fixture()
        val owningConversation = Uuid.random()
        val foreignConversation = Uuid.random()
        val created = f.repository.create(owningConversation, ScheduleOwner.USER, oneShotDraft(f.spawnable.id))
            as ScheduleMutationResult.Accepted

        // Deleting through a DIFFERENT conversation must reject and leave the row intact.
        val result = f.repository.delete(foreignConversation, created.snapshot.id)

        assertTrue(result is ScheduleMutationResult.Rejected)
        assertNotNull("foreign delete must not remove the row", f.dao.getById(created.snapshot.id.toString()))
    }

    @Test
    fun delete_of_an_unknown_id_rejects() = runBlocking {
        val f = Fixture()
        val result = f.repository.delete(Uuid.random(), Uuid.random())
        assertTrue(result is ScheduleMutationResult.Rejected)
    }
}
