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
}
