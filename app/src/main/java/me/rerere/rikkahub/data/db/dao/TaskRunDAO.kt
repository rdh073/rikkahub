package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.TaskRunEntity

/**
 * Row-level access to persisted task runs (SPEC.md M2). Lifecycle invariants (legal transitions,
 * single-active-handle on resume) live in the repository/domain layer, not in queries here.
 */
@Dao
interface TaskRunDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(run: TaskRunEntity)

    @Query("SELECT * FROM task_runs WHERE id = :id")
    suspend fun getById(id: String): TaskRunEntity?

    @Query("SELECT * FROM task_runs WHERE id = :id")
    fun getByIdFlow(id: String): Flow<TaskRunEntity?>

    @Query("SELECT * FROM task_runs WHERE conversation_id = :conversationId ORDER BY created_at ASC")
    fun listByConversationFlow(conversationId: String): Flow<List<TaskRunEntity>>

    /** Recovery scan: rows whose persisted state is in [states] (TaskRunStateTag names). */
    @Query("SELECT * FROM task_runs WHERE latest_state IN (:states)")
    suspend fun listByStates(states: Set<String>): List<TaskRunEntity>

    @Query("DELETE FROM task_runs WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** Cascade with conversation cleanup (retention, M6). */
    @Query("DELETE FROM task_runs WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String): Int
}
