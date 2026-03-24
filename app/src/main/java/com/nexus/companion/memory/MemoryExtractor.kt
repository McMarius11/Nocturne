package com.nexus.companion.memory

class MemoryExtractor(private val memoryDao: MemoryDao) {

    private val patternsDE = listOf(
        ExtractionPattern(
            regex = Regex("""(?:ich (?:heiße|bin|nenne mich)|mein name ist)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "name",
            key = "name"
        ),
        ExtractionPattern(
            regex = Regex("""(?:ich (?:arbeite als|bin (?:von beruf)?)|mein (?:beruf|job) ist)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "job",
            key = "job"
        ),
        ExtractionPattern(
            regex = Regex("""ich bin (\d{1,3}) (?:jahre? alt|j\.)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "age"
        ),
        ExtractionPattern(
            regex = Regex("""ich (?:wohne|lebe|komme) (?:in|aus)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "location"
        ),
        ExtractionPattern(
            regex = Regex("""ich (?:mag|liebe|stehe auf|finde .+ toll)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "likes"
        ),
        ExtractionPattern(
            regex = Regex("""mein(?:e)? lieblings(\w+)\s+(?:ist|sind)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "preference",
            key = "favorite_\$1"
        ),
        ExtractionPattern(
            regex = Regex("""(?:mein(?:e)? hobbys? (?:ist|sind)|ich mache gerne)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "hobby"
        ),
        ExtractionPattern(
            regex = Regex("""ich habe (?:eine?(?:n)?)\s+(\w+)\s+(?:namens|der|die|das)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "pet"
        ),
    )

    private val patternsEN = listOf(
        ExtractionPattern(
            regex = Regex("""(?:my name is|i'm called|i am|i'm)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "name",
            key = "name"
        ),
        ExtractionPattern(
            regex = Regex("""(?:i (?:work as|am) (?:a |an )?|my (?:job|profession) is)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "job",
            key = "job"
        ),
        ExtractionPattern(
            regex = Regex("""i(?:'m| am) (\d{1,3}) years? old""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "age"
        ),
        ExtractionPattern(
            regex = Regex("""i (?:live|am) (?:in|from)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "location"
        ),
        ExtractionPattern(
            regex = Regex("""i (?:like|love|enjoy|adore)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "likes"
        ),
        ExtractionPattern(
            regex = Regex("""my fav(?:ou?rite)?\s+(\w+)\s+(?:is|are)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "preference",
            key = "favorite_\$1"
        ),
        ExtractionPattern(
            regex = Regex("""my hobb(?:y|ies) (?:is|are|include)\s+(.+?)(?:\.|,|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "hobby"
        ),
        ExtractionPattern(
            regex = Regex("""i have (?:a |an )?(\w+)\s+(?:named|called)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "pet"
        ),
    )

    // Always run both DE and EN patterns for bilingual support
    private val allPatterns = patternsDE + patternsEN

    suspend fun extractAndStore(userMessage: String) {
        for (pattern in allPatterns) {
            val match = pattern.regex.find(userMessage) ?: continue

            val value = if (match.groupValues.size > 2) {
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
