package com.nexus.companion.memory

/**
 * Extracts facts from user messages and stores them in the memory database.
 * Uses pattern matching to identify personal information.
 */
class MemoryExtractor(private val memoryDao: MemoryDao) {

    private val patterns = listOf(
        // Name patterns
        ExtractionPattern(
            regex = Regex("""(?:ich (?:heiße|bin|nenne mich)|mein name ist)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "name",
            key = "name"
        ),
        // Job patterns
        ExtractionPattern(
            regex = Regex("""(?:ich (?:arbeite als|bin (?:von beruf)?)|mein (?:beruf|job) ist)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "job",
            key = "beruf"
        ),
        // Age
        ExtractionPattern(
            regex = Regex("""ich bin (\d{1,3}) (?:jahre? alt|j\.)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "alter"
        ),
        // Location
        ExtractionPattern(
            regex = Regex("""ich (?:wohne|lebe|komme) (?:in|aus)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "wohnort"
        ),
        // Likes
        ExtractionPattern(
            regex = Regex("""ich (?:mag|liebe|stehe auf|finde .+ toll)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "mag"
        ),
        // Favorite things
        ExtractionPattern(
            regex = Regex("""mein(?:e)? lieblings(\w+)\s+(?:ist|sind)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "preference",
            key = "lieblings_\$1"  // dynamic key
        ),
        // Hobbies
        ExtractionPattern(
            regex = Regex("""(?:mein(?:e)? hobbys? (?:ist|sind)|ich mache gerne)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "hobby"
        ),
        // Pet
        ExtractionPattern(
            regex = Regex("""ich habe (?:eine?(?:n)?)\s+(\w+)\s+(?:namens|der|die|das)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "haustier"
        ),
    )

    suspend fun extractAndStore(userMessage: String) {
        for (pattern in patterns) {
            val match = pattern.regex.find(userMessage) ?: continue

            val value = if (match.groupValues.size > 2) {
                // For patterns with multiple groups (like favorites)
                match.groupValues.drop(1).joinToString(" ")
            } else {
                match.groupValues[1].trim()
            }

            if (value.isBlank()) continue

            val key = if (pattern.key.contains("\$1") && match.groupValues.size > 1) {
                pattern.key.replace("\$1", match.groupValues[1].lowercase())
            } else {
                pattern.key
            }

            val existing = memoryDao.getByKey(key)
            val memory = MemoryEntity(
                id = existing?.id ?: 0,
                category = pattern.category,
                key = key,
                value = value,
                confidence = 1.0f,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            memoryDao.upsert(memory)
        }
    }

    suspend fun getMemoryContext(): String {
        val memories = memoryDao.getRecent(20)
        if (memories.isEmpty()) return ""

        return memories.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }

    private data class ExtractionPattern(
        val regex: Regex,
        val category: String,
        val key: String
    )
}
