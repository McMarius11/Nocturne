# JNI bridge classes — must not be obfuscated (native code finds them by name)
-keep class com.nexus.companion.llm.LlamaJni { *; }
-keep class com.nexus.companion.llm.LlamaJni$TokenCallback { *; }
-keep class com.nexus.companion.tts.NeuTtsJni { *; }

# Room database classes — prevent obfuscation of entities and DAOs
-keep class com.nexus.companion.data.MessageEntity { *; }
-keep class com.nexus.companion.data.ChatDatabase { *; }
-keep interface com.nexus.companion.data.MessageDao { *; }
-keep class com.nexus.companion.memory.MemoryEntity { *; }
-keep interface com.nexus.companion.memory.MemoryDao { *; }
