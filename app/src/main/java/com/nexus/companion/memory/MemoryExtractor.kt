package com.nexus.companion.memory

import android.util.Log
import org.json.JSONArray

/**
 * Intelligent memory extraction system.
 *
 * Three layers:
 * 1. Regex patterns (fast, reliable for structured facts)
 * 2. LLM-based extraction (catches everything regex misses)
 * 3. Conversation summaries (long-term compressed memory)
 *
 * Improvements over v1:
 * - Accumulating lists (likes/dislikes grow, never overwrite)
 * - Extracts from BOTH user and assistant messages
 * - Emotional state tracking
 * - Relevance-based retrieval (keyword matching + core identity)
 * - Conversation summaries for long-term context
 */
class MemoryExtractor(private val memoryDao: MemoryDao) {

    companion object {
        private const val TAG = "MemoryExtractor"

        /** Prompt injected into the LLM to extract memories from conversation */
        const val EXTRACTION_PROMPT = """Extract key personal facts from this conversation turn.
Return ONLY a list of key:value pairs, one per line. If nothing personal, return NONE.
Categories: name, age, location, job, like, dislike, hobby, family, pet, fact, emotion.
For emotions use: happy, sad, tired, excited, stressed, neutral.

Examples:
- name: Max
- like: Pizza
- emotion: happy
- pet: Katze namens Luna

Conversation:"""
    }

    // ===== Regex patterns (Layer 1 — fast, structured extraction) =====

    private val patternsDE = listOf(
        Pat("""(?:ich (?:heiße|bin|nenne mich)|mein name ist|man nennt mich|ich bin der|ich bin die)\s+(\w+)""", "name", "name"),
        Pat("""(?:ich (?:arbeite als|bin (?:von beruf )?)|mein (?:beruf|job) ist|beruflich bin ich)\s+(.+?)(?:\.|,|!|\?|$)""", "job", "job"),
        Pat("""ich bin (\d{1,3}) (?:jahre? alt|j\.)""", "fact", "age"),
        Pat("""ich (?:wohne|lebe|komme|bin) (?:in|aus|bei)\s+(.+?)(?:\.|,|!|\?|$)""", "fact", "location"),
        // Accumulating patterns (isList = true)
        Pat("""ich (?:mag|liebe|stehe auf|finde .+ (?:toll|super|geil|klasse|cool))\s+(.+?)(?:\.|,|!|\?|$)""", "interest", "likes", isList = true),
        Pat("""ich (?:hasse|mag kein(?:e?n?)|kann .+ nicht (?:leiden|ausstehen))\s+(.+?)(?:\.|,|!|\?|$)""", "preference", "dislikes", isList = true),
        Pat("""mein(?:e)? lieblings(\w+)\s+(?:ist|sind|heißt)\s+(.+?)(?:\.|,|!|\?|$)""", "preference", "favorite_\$1"),
        Pat("""(?:mein(?:e)? hobbys? (?:ist|sind)|ich mache gerne|in meiner freizeit)\s+(.+?)(?:\.|,|!|\?|$)""", "interest", "hobbies", isList = true),
        Pat("""ich habe (?:eine?(?:n)?)\s+(\w+)\s+(?:namens|der|die|das|names?)\s+(\w+)""", "fact", "pet"),
        Pat("""(?:mein(?:e)?|ich habe (?:eine?(?:n)?)?) (?:frau|mann|freundin|freund|partner(?:in)?|kind|tochter|sohn)\s+(?:heißt|ist|namens)\s+(\w+)""", "fact", "family"),
        // Emotional state
        Pat("""(?:ich bin|ich fühle mich|mir geht(?:'s| es))\s+(gut|schlecht|müde|traurig|glücklich|gestresst|super|einsam|aufgeregt|genervt|wütend)""", "emotion", "mood"),
        Pat("""(?:heute war|der tag war|ich hatte)\s+(?:ein(?:en)?)\s+(guten|schlechten|tollen|harten|stressigen|schönen|langen)\s+tag""", "emotion", "day_mood"),
        // Broader interest patterns
        Pat("""(?:ich interessiere mich für|mich interessiert)\s+(.+?)(?:\.|,|!|\?|$)""", "interest", "interests", isList = true),
        Pat("""ich (?:spiele|höre|lese|schaue|koche|esse|trinke) (?:gerne?|oft|viel)\s+(.+?)(?:\.|,|!|\?|$)""", "interest", "activities", isList = true),
    )

    private val patternsEN = listOf(
        Pat("""(?:my name is|i'm called|call me|i'm|i am|the name's|they call me)\s+(\w+)""", "name", "name"),
        Pat("""(?:i (?:work as|am) (?:a |an )?|my (?:job|profession|occupation) is|i do)\s+(.+?)(?:\.|,|!|\?|$)""", "job", "job"),
        Pat("""i(?:'m| am) (\d{1,3}) years? old""", "fact", "age"),
        Pat("""i (?:live|am|come|grew up) (?:in|from|near)\s+(.+?)(?:\.|,|!|\?|$)""", "fact", "location"),
        // Accumulating
        Pat("""i (?:like|love|enjoy|adore|really dig|am into|am a fan of)\s+(.+?)(?:\.|,|!|\?|$)""", "interest", "likes", isList = true),
        Pat("""i (?:hate|dislike|can't stand|don't like|despise)\s+(.+?)(?:\.|,|!|\?|$)""", "preference", "dislikes", isList = true),
        Pat("""my fav(?:ou?rite)?\s+(\w+)\s+(?:is|are)\s+(.+?)(?:\.|,|!|\?|$)""", "preference", "favorite_\$1"),
        Pat("""(?:my hobb(?:y|ies) (?:is|are|include)|in my (?:free|spare) time i)\s+(.+?)(?:\.|,|!|\?|$)""", "interest", "hobbies", isList = true),
        Pat("""i have (?:a |an )?(\w+)\s+(?:named|called)\s+(\w+)""", "fact", "pet"),
        Pat("""my (?:wife|husband|girlfriend|boyfriend|partner|daughter|son|kid|child)(?:'s name)? is\s+(\w+)""", "fact", "family"),
        // Emotional state
        Pat("""i(?:'m| am| feel)\s+(happy|sad|tired|excited|stressed|great|lonely|bored|anxious|angry|good|bad|okay)""", "emotion", "mood"),
        Pat("""(?:today was|it's been|i had)\s+(?:a )?(good|bad|great|terrible|rough|stressful|long|wonderful)\s+day""", "emotion", "day_mood"),
        // Broader interests
        Pat("""i(?:'m| am) (?:interested in|passionate about|curious about)\s+(.+?)(?:\.|,|!|\?|$)""", "interest", "interests", isList = true),
        Pat("""i (?:play|listen to|read|watch|cook|eat|drink) (?:a lot of |often )?(.+?)(?:\.|,|!|\?|$)""", "interest", "activities", isList = true),
    )

    private val allPatterns = patternsDE + patternsEN

    // ===== Public API =====

    /**
     * Extract memories from a user message using regex patterns.
     * Called for every user message.
     */
    suspend fun extractAndStore(userMessage: String) {
        extractFromText(userMessage, source = "user")
    }

    /**
     * Extract memories from an assistant response.
     * Catches implicit confirmations and facts mentioned in context.
     */
    suspend fun extractFromAssistant(assistantMessage: String) {
        // Only extract factual mentions, not emotions
        extractFromText(assistantMessage, source = "assistant", skipEmotions = true)
    }

    /**
     * Use LLM output to store extracted memories.
     * Call this with the raw LLM extraction output after generating with EXTRACTION_PROMPT.
     */
    suspend fun storeFromLlmExtraction(llmOutput: String) {
        if (llmOutput.isBlank() || llmOutput.trim().equals("NONE", ignoreCase = true)) return

        for (line in llmOutput.lines()) {
            val trimmed = line.trim().removePrefix("- ").trim()
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) continue

            val key = trimmed.substring(0, colonIdx).trim().lowercase()
            val value = trimmed.substring(colonIdx + 1).trim()
            if (value.isBlank() || value.length > 200) continue

            val category = when (key) {
                "name" -> "name"
                "age", "location", "pet", "family" -> "fact"
                "job" -> "job"
                "like", "likes" -> "interest"
                "dislike", "dislikes" -> "preference"
                "hobby", "hobbies" -> "interest"
                "emotion", "mood" -> "emotion"
                else -> "fact"
            }

            val isList = key in listOf("like", "likes", "dislike", "dislikes", "hobby", "hobbies", "interest", "interests", "activities")
            val normalizedKey = when (key) {
                "like" -> "likes"
                "dislike" -> "dislikes"
                "hobby" -> "hobbies"
                "interest" -> "interests"
                "mood" -> "mood"
                else -> key
            }

            storeMemory(normalizedKey, value, category, isList, source = "inferred")
        }
    }

    /**
     * Store a conversation summary for long-term memory.
     * Should be called periodically (e.g. every 10 exchanges).
     */
    suspend fun storeSummary(summary: String) {
        val timestamp = System.currentTimeMillis()
        val memory = MemoryEntity(
            category = "summary",
            key = "conversation_$timestamp",
            value = summary,
            source = "inferred",
            createdAt = timestamp,
            updatedAt = timestamp
        )
        memoryDao.upsert(memory)
        Log.d(TAG, "Stored conversation summary")
    }

    /**
     * Build context string for the LLM prompt.
     * Uses relevance-based retrieval: core identity + keyword-matched + recent.
     */
    suspend fun getMemoryContext(currentMessage: String = ""): String {
        val parts = mutableListOf<String>()

        // 1. Always include core identity (name, job, age, location)
        val identity = memoryDao.getCoreIdentity()
        if (identity.isNotEmpty()) {
            parts.add("About the user:")
            identity.forEach { mem ->
                parts.add("- ${formatMemory(mem)}")
            }
        }

        // 2. Include latest emotional state
        val emotion = memoryDao.getLatestEmotion()
        if (emotion != null) {
            val ageMinutes = (System.currentTimeMillis() - emotion.updatedAt) / 60_000
            if (ageMinutes < 60) { // Only if emotion is recent (< 1 hour)
                parts.add("Current mood: ${emotion.value}")
            }
        }

        // 3. Keyword-relevant memories (if current message provided)
        if (currentMessage.isNotBlank()) {
            val keywords = extractKeywords(currentMessage)
            val relevant = mutableSetOf<MemoryEntity>()
            for (keyword in keywords) {
                val matches = memoryDao.search(keyword, limit = 5)
                relevant.addAll(matches)
            }
            // Remove duplicates with identity
            val identityIds = identity.map { it.id }.toSet()
            val uniqueRelevant = relevant.filter { it.id !in identityIds && it.category != "emotion" }
            if (uniqueRelevant.isNotEmpty()) {
                parts.add("Related memories:")
                uniqueRelevant.take(10).forEach { mem ->
                    parts.add("- ${formatMemory(mem)}")
                }
            }
        }

        // 4. Recent interests/preferences (top 10, not already included)
        val recent = memoryDao.getRecent(15)
        val alreadyIncluded = parts.joinToString("\n")
        val additional = recent.filter { mem ->
            mem.category !in listOf("name", "job", "emotion", "summary") &&
                !alreadyIncluded.contains(mem.value)
        }.take(8)
        if (additional.isNotEmpty()) {
            parts.add("Other things known:")
            additional.forEach { mem ->
                parts.add("- ${formatMemory(mem)}")
            }
        }

        // 5. Latest conversation summary (if any)
        val summaries = memoryDao.getSummaries(2)
        if (summaries.isNotEmpty()) {
            parts.add("Previous conversations:")
            summaries.forEach { parts.add("- ${it.value}") }
        }

        return parts.joinToString("\n")
    }

    /**
     * Build the LLM extraction prompt for a conversation turn.
     */
    fun buildExtractionPrompt(userMessage: String, assistantResponse: String): String {
        return "$EXTRACTION_PROMPT\nUser: $userMessage\nAssistant: $assistantResponse"
    }

    /**
     * Check if it's time to generate a conversation summary.
     * Returns true every ~10 messages.
     */
    suspend fun shouldSummarize(messageCount: Int): Boolean {
        return messageCount > 0 && messageCount % 10 == 0
    }

    // ===== Internal =====

    private suspend fun extractFromText(text: String, source: String, skipEmotions: Boolean = false) {
        for (pattern in allPatterns) {
            if (skipEmotions && pattern.category == "emotion") continue

            val match = pattern.regex.find(text) ?: continue

            // For patterns with $1 in key (e.g. favorite_$1), use the LAST group as value
            val value = if (pattern.key.contains("\$1") && match.groupValues.size > 2) {
                match.groupValues.last().trim()
            } else if (match.groupValues.size > 2) {
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

            storeMemory(key, value, pattern.category, pattern.isList, source)
        }
    }

    private suspend fun storeMemory(
        key: String,
        value: String,
        category: String,
        isList: Boolean,
        source: String
    ) {
        val existing = memoryDao.getByKey(key)

        val finalValue = if (isList && existing != null) {
            // Accumulate into list
            appendToList(existing.value, value, existing.isList)
        } else {
            value
        }

        val memory = MemoryEntity(
            id = existing?.id ?: 0,
            category = category,
            key = key,
            value = finalValue,
            isList = isList,
            source = source,
            confidence = 1.0f,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        memoryDao.upsert(memory)
        Log.d(TAG, "Stored memory: $key = $finalValue (source=$source, list=$isList)")
    }

    private fun appendToList(existingValue: String, newValue: String, wasAlreadyList: Boolean): String {
        val items = if (wasAlreadyList) {
            try {
                val arr = JSONArray(existingValue)
                (0 until arr.length()).map { arr.getString(it) }.toMutableList()
            } catch (_: Exception) {
                mutableListOf(existingValue)
            }
        } else {
            mutableListOf(existingValue)
        }

        // Don't add duplicates (case-insensitive)
        val newLower = newValue.lowercase().trim()
        if (items.none { it.lowercase().trim() == newLower }) {
            items.add(newValue.trim())
        }

        // Keep max 20 items per list
        val trimmed = if (items.size > 20) items.takeLast(20) else items
        return JSONArray(trimmed).toString()
    }

    private fun formatMemory(mem: MemoryEntity): String {
        val displayValue = if (mem.isList) {
            try {
                val arr = JSONArray(mem.value)
                (0 until arr.length()).joinToString(", ") { arr.getString(it) }
            } catch (_: Exception) {
                mem.value
            }
        } else {
            mem.value
        }
        return "${mem.key}: $displayValue"
    }

    private fun extractKeywords(text: String): List<String> {
        // Simple keyword extraction — split on whitespace, filter short/stop words
        val stopWords = setOf(
            "ich", "du", "er", "sie", "es", "wir", "ihr", "und", "oder", "aber",
            "der", "die", "das", "ein", "eine", "ist", "bin", "hat", "haben",
            "was", "wie", "wo", "wer", "nicht", "auch", "noch", "schon", "mal",
            "i", "you", "he", "she", "it", "we", "they", "the", "a", "an",
            "is", "am", "are", "was", "were", "do", "does", "did", "have", "has",
            "what", "how", "where", "who", "not", "also", "just", "can", "will",
            "mit", "für", "von", "zu", "auf", "in", "an", "with", "for", "to",
            "my", "your", "his", "her", "our", "their", "mein", "dein", "sein"
        )

        return text.lowercase()
            .replace(Regex("[^a-zäöüß0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(5)
    }

    private data class Pat(
        val pattern: String,
        val category: String,
        val key: String,
        val isList: Boolean = false
    ) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }
}
