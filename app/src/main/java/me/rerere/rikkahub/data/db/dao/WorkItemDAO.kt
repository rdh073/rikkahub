package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkItemDependencyEntity
import me.rerere.rikkahub.data.db.entity.WorkItemEntity

/**
 * Row-level access to the per-conversation work-item board: items plus their normalized
 * dependency edges, one aggregate (SPEC.md M2). Board invariants — atomic claims,
 * SingleOwnerClaim, cycle rejection, legal transitions — are enforced in the repository layer
 * inside Room transactions composed from these primitives; tool calls and the UI share that
 * single path (maintainer decision #4).
 */
@Dao
interface WorkItemDAO {

    // --- items ------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: WorkItemEntity)

    @Update
    suspend fun update(item: WorkItemEntity): Int

    @Query("SELECT * FROM work_items WHERE id = :id")
    suspend fun getById(id: String): WorkItemEntity?

    @Query("SELECT * FROM work_items WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun listByConversationFlow(conversationId: String): Flow<List<WorkItemEntity>>

    @Query("SELECT * FROM work_items WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    suspend fun listByConversation(conversationId: String): List<WorkItemEntity>

    /** Orphan recovery: every claim held by a (dead) execution handle (decision #5). */
    @Query("SELECT * FROM work_items WHERE owner_handle_id = :ownerHandleId")
    suspend fun listByOwner(ownerHandleId: String): List<WorkItemEntity>

    @Query("DELETE FROM work_items WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** Cascade with conversation cleanup (retention, M6). */
    @Query("DELETE FROM work_items WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int

    // --- dependency edges ---------------------------------------------------------------------

    /** Returns -1 when the identical edge already exists (composite-PK conflict ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependency(edge: WorkItemDependencyEntity): Long

    @Query("SELECT * FROM work_item_dependencies WHERE conversation_id = :conversationId")
    suspend fun listDependencies(conversationId: String): List<WorkItemDependencyEntity>

    /** The claim path's gate: who still blocks [blockedId]? */
    @Query("SELECT * FROM work_item_dependencies WHERE conversation_id = :conversationId AND blocked_id = :blockedId")
    suspend fun listBlockersOf(conversationId: String, blockedId: String): List<WorkItemDependencyEntity>

    @Query(
        "DELETE FROM work_item_dependencies WHERE conversation_id = :conversationId " +
            "AND blocker_id = :blockerId AND blocked_id = :blockedId"
    )
    suspend fun deleteDependency(conversationId: String, blockerId: String, blockedId: String): Int

    /** Item deletion removes every edge touching it so dependents unblock (DeleteUnblocksDependents). */
    @Query(
        "DELETE FROM work_item_dependencies WHERE conversation_id = :conversationId " +
            "AND (blocker_id = :itemId OR blocked_id = :itemId)"
    )
    suspend fun deleteDependenciesTouching(conversationId: String, itemId: String): Int

    @Query("DELETE FROM work_item_dependencies WHERE conversation_id = :conversationId")
    suspend fun deleteDependenciesByConversationId(conversationId: String): Int
}
