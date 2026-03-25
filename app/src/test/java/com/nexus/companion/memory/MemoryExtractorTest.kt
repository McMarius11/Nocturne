package com.nexus.companion.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for MemoryExtractor regex patterns and keyword extraction.
 * Note: Database-dependent tests are in androidTest.
 */
class MemoryExtractorTest {

    @Test
    fun extractionPromptIsNotEmpty() {
        assertTrue(MemoryExtractor.EXTRACTION_PROMPT.isNotBlank())
        assertTrue(MemoryExtractor.EXTRACTION_PROMPT.contains("key:value"))
    }

    @Test
    fun memoryEntityHasListSupport() {
        val mem = MemoryEntity(
            category = "interest",
            key = "likes",
            value = "[\"Pizza\",\"Sushi\"]",
            isList = true,
            source = "user"
        )
        assertTrue(mem.isList)
        assertEquals("user", mem.source)
        assertTrue(mem.value.contains("Pizza"))
        assertTrue(mem.value.contains("Sushi"))
    }

    @Test
    fun memoryEntitySourceField() {
        val userMem = MemoryEntity(category = "name", key = "name", value = "Max", source = "user")
        val assistantMem = MemoryEntity(category = "fact", key = "age", value = "25", source = "assistant")
        val inferredMem = MemoryEntity(category = "emotion", key = "mood", value = "happy", source = "inferred")

        assertEquals("user", userMem.source)
        assertEquals("assistant", assistantMem.source)
        assertEquals("inferred", inferredMem.source)
    }

    @Test
    fun memoryEntityDefaultValues() {
        val mem = MemoryEntity(category = "fact", key = "test", value = "value")
        assertEquals(false, mem.isList)
        assertEquals("user", mem.source)
        assertEquals(1.0f, mem.confidence, 0.001f)
    }

    @Test
    fun emotionCategoriesAreTracked() {
        // Verify the extraction prompt mentions emotion categories
        val prompt = MemoryExtractor.EXTRACTION_PROMPT
        assertTrue(prompt.contains("emotion"))
        assertTrue(prompt.contains("happy"))
        assertTrue(prompt.contains("sad"))
        assertTrue(prompt.contains("tired"))
    }

    @Test
    fun buildExtractionPromptFormatsCorrectly() {
        val extractor = createTestExtractor()
        val prompt = extractor.buildExtractionPrompt("Ich heiße Max", "Hallo Max!")
        assertTrue(prompt.contains("User: Ich heiße Max"))
        assertTrue(prompt.contains("Assistant: Hallo Max!"))
        assertTrue(prompt.contains("Extract key personal facts"))
    }

    /**
     * Create a MemoryExtractor with a no-op DAO for unit testing regex patterns.
     * Integration tests with real DB are in androidTest.
     */
    private fun createTestExtractor(): MemoryExtractor {
        // We can construct with any MemoryDao since we only test non-DB methods
        // For methods that need the DAO, use instrumented tests
        return MemoryExtractor(NoOpMemoryDao())
    }

    /** Minimal no-op DAO for unit testing */
    private class NoOpMemoryDao : MemoryDao {
        override fun getAllMemories() = throw UnsupportedOperationException()
        override fun getByCategory(category: String) = throw UnsupportedOperationException()
        override suspend fun getByKey(key: String): MemoryEntity? = null
        override suspend fun upsert(memory: MemoryEntity) {}
        override suspend fun delete(id: Long) {}
        override suspend fun clearAll() {}
        override suspend fun getRecent(limit: Int) = emptyList<MemoryEntity>()
        override suspend fun search(query: String, limit: Int) = emptyList<MemoryEntity>()
        override suspend fun getCoreIdentity() = emptyList<MemoryEntity>()
        override suspend fun getLatestEmotion(): MemoryEntity? = null
        override suspend fun getSummaries(limit: Int) = emptyList<MemoryEntity>()
        override suspend fun count() = 0
    }
}
