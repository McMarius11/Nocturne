package com.nexus.companion.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,       // name, job, interest, preference, fact, emotion, summary
    val key: String,            // e.g. "name", "likes", "mood"
    val value: String,          // Single value or JSON array for lists: ["Pizza","Sushi"]
    val isList: Boolean = false, // If true, value is a JSON array that accumulates
    val source: String = "user", // "user", "assistant", "inferred"
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
