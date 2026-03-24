package com.nexus.companion.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,  // name, job, interest, preference, fact
    val key: String,        // e.g. "name", "lieblingsfarbe"
    val value: String,      // e.g. "Max", "blau"
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
