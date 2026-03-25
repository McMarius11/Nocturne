package com.nexus.companion.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE category = :category ORDER BY updatedAt DESC")
    fun getByCategory(category: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memories")
    suspend fun clearAll()

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<MemoryEntity>

    /** Search memories by keyword in key or value fields */
    @Query("SELECT * FROM memories WHERE `key` LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%' ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int = 10): List<MemoryEntity>

    /** Get core identity memories (name, age, job, location) — always included */
    @Query("SELECT * FROM memories WHERE category IN ('name', 'job') OR `key` IN ('name', 'age', 'location', 'job') ORDER BY updatedAt DESC")
    suspend fun getCoreIdentity(): List<MemoryEntity>

    /** Get the latest emotional state */
    @Query("SELECT * FROM memories WHERE category = 'emotion' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestEmotion(): MemoryEntity?

    /** Get conversation summaries */
    @Query("SELECT * FROM memories WHERE category = 'summary' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getSummaries(limit: Int = 5): List<MemoryEntity>

    /** Count all memories */
    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
}
