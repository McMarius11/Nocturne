# JNI bridge classes — must not be obfuscated (native code finds them by name)
-keep class com.nexus.companion.llm.LlamaJni { *; }
-keep class com.nexus.companion.llm.LlamaJni$TokenCallback { *; }

# sherpa-onnx TTS JNI classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Apache Commons Compress — keep bzip2/tar classes, ignore missing optional deps
-keep class org.apache.commons.compress.archivers.tar.** { *; }
-keep class org.apache.commons.compress.compressors.bzip2.** { *; }
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

# Room database classes
-keep class com.nexus.companion.data.MessageEntity { *; }
-keep class com.nexus.companion.data.ChatDatabase { *; }
-keep interface com.nexus.companion.data.MessageDao { *; }
-keep class com.nexus.companion.memory.MemoryEntity { *; }
-keep interface com.nexus.companion.memory.MemoryDao { *; }
