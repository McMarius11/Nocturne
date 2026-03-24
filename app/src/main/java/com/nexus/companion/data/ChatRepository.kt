package com.nexus.companion.data

import kotlinx.coroutines.flow.Flow

class ChatRepository(private val messageDao: MessageDao) {

    val allMessages: Flow<List<MessageEntity>> = messageDao.getAllMessages()

    suspend fun sendMessage(role: String, content: String) {
        messageDao.insert(
            MessageEntity(role = role, content = content)
        )
    }

    suspend fun getRecentHistory(limit: Int = 20): List<Pair<String, String>> {
        val messages = messageDao.getRecent(limit).reversed()
        val pairs = mutableListOf<Pair<String, String>>()

        var i = 0
        while (i < messages.size - 1) {
            if (messages[i].role == "user" && messages[i + 1].role == "assistant") {
                pairs.add(messages[i].content to messages[i + 1].content)
                i += 2
            } else {
                i++
            }
        }
        return pairs
    }

    suspend fun clearChat() {
        messageDao.clearAll()
    }
}
