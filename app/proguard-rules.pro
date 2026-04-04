# JNI bridge classes — must not be obfuscated (native code finds them by name)
-keep class com.nexus.companion.llm.LlamaJni { *; }
-keep class com.nexus.companion.llm.LlamaJni$TokenCallback { *; }
-keep class com.nexus.companion.tts.NeuTtsJni { *; }
