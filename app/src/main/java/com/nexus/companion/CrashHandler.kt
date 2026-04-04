package com.nexus.companion

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught exceptions, writes the stacktrace to a file,
 * and re-throws so the system still shows the crash dialog.
 *
 * On next app start, [getLastCrashLog] returns the crash report
 * so it can be shown in the Debug Log screen.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    companion object {
        private const val TAG = "CrashHandler"
        private const val CRASH_FILE = "last_crash.txt"

        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }

        /** Returns the last crash log (or null if no crash). Clears it after reading. */
        fun getLastCrashLog(context: Context): String? {
            val file = File(context.filesDir, CRASH_FILE)
            if (!file.exists()) return null
            return try {
                val content = file.readText()
                file.delete()
                content.ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val report = buildCrashReport(thread, throwable)
            val file = File(context.filesDir, CRASH_FILE)
            file.writeText(report)
            Log.e(TAG, "Crash saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash report", e)
        }

        // Let the default handler finish the crash (shows system dialog)
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            appendLine("=== CRASH REPORT ===")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("Exception: ${throwable.javaClass.simpleName}: ${throwable.message}")
            appendLine()
            appendLine("Stacktrace:")
            appendLine(sw.toString())
        }
    }
}
