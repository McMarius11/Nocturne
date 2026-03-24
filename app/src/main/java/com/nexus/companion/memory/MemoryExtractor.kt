package com.nexus.companion.memory

class MemoryExtractor(private val memoryDao: MemoryDao) {

    private val patternsDE = listOf(
        // Name patterns - more variations
        ExtractionPattern(
            regex = Regex("""(?:ich (?:heiße|bin|nenne mich)|mein name ist|man nennt mich|ich bin der|ich bin die)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "name",
            key = "name"
        ),
        // Job patterns
        ExtractionPattern(
            regex = Regex("""(?:ich (?:arbeite als|bin (?:von beruf )?)|mein (?:beruf|job) ist|beruflich bin ich)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "job",
            key = "job"
        ),
        // Age patterns
        ExtractionPattern(
            regex = Regex("""ich bin (\d{1,3}) (?:jahre? alt|j\.)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "age"
        ),
        // Location patterns
        ExtractionPattern(
            regex = Regex("""ich (?:wohne|lebe|komme|bin) (?:in|aus|bei)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "location"
        ),
        // Likes patterns - broader matching
        ExtractionPattern(
            regex = Regex("""ich (?:mag|liebe|stehe auf|finde .+ (?:toll|super|geil|klasse|cool))\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "likes"
        ),
        // Dislikes
        ExtractionPattern(
            regex = Regex("""ich (?:hasse|mag kein(?:e?n?)|kann .+ nicht (?:leiden|ausstehen))\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "preference",
            key = "dislikes"
        ),
        // Favorites
        ExtractionPattern(
            regex = Regex("""mein(?:e)? lieblings(\w+)\s+(?:ist|sind|heißt)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "preference",
            key = "favorite_\$1"
        ),
        // Hobbies
        ExtractionPattern(
            regex = Regex("""(?:mein(?:e)? hobbys? (?:ist|sind)|ich mache gerne|in meiner freizeit)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "hobby"
        ),
        // Pets
        ExtractionPattern(
            regex = Regex("""ich habe (?:eine?(?:n)?)\s+(\w+)\s+(?:namens|der|die|das|names?)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "pet"
        ),
        // Family
        ExtractionPattern(
            regex = Regex("""(?:mein(?:e)?|ich habe (?:eine?(?:n)?)?) (?:frau|mann|freundin|freund|partner(?:in)?|kind|tochter|sohn)\s+(?:heißt|ist|namens)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "family"
        ),
    )

    private val patternsEN = listOf(
        // Name patterns - more variations
        ExtractionPattern(
            regex = Regex("""(?:my name is|i'm called|call me|i'm|i am|the name's|they call me)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "name",
            key = "name"
        ),
        // Job patterns
        ExtractionPattern(
            regex = Regex("""(?:i (?:work as|am) (?:a |an )?|my (?:job|profession|occupation) is|i do)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "job",
            key = "job"
        ),
        // Age patterns
        ExtractionPattern(
            regex = Regex("""i(?:'m| am) (\d{1,3}) years? old""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "age"
        ),
        // Location patterns
        ExtractionPattern(
            regex = Regex("""i (?:live|am|come|grew up) (?:in|from|near)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "location"
        ),
        // Likes patterns
        ExtractionPattern(
            regex = Regex("""i (?:like|love|enjoy|adore|really dig|am into|am a fan of)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "likes"
        ),
        // Dislikes
        ExtractionPattern(
            regex = Regex("""i (?:hate|dislike|can't stand|don't like|despise)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "preference",
            key = "dislikes"
        ),
        // Favorites
        ExtractionPattern(
            regex = Regex("""my fav(?:ou?rite)?\s+(\w+)\s+(?:is|are)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "preference",
            key = "favorite_\$1"
        ),
        // Hobbies
        ExtractionPattern(
            regex = Regex("""(?:my hobb(?:y|ies) (?:is|are|include)|in my (?:free|spare) time i)\s+(.+?)(?:\.|,|!|\?|$)""", RegexOption.IGNORE_CASE),
            category = "interest",
            key = "hobby"
        ),
        // Pets
        ExtractionPattern(
            regex = Regex("""i have (?:a |an )?(\w+)\s+(?:named|called)\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "pet"
        ),
        // Family
        ExtractionPattern(
            regex = Regex("""my (?:wife|husband|girlfriend|boyfriend|partner|daughter|son|kid|child)(?:'s name)? is\s+(\w+)""", RegexOption.IGNORE_CASE),
            category = "fact",
            key = "family"
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

            if (value.isBlank() || value.length > 100) continue

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
