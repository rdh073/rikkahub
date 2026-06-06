package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import me.rerere.common.android.SensitiveLogPolicy

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildRedactedStackTrace(thread.name, throwable)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }

    // Issue #100: the persisted crash trace is surfaced and copyable via SafeModeActivity,
    // so it is an exported diagnostic sink, not just a developer's logcat. throwable
    // .stackTraceToString() prefixes each exception with throwable.toString() (class +
    // ": " + message); that message can embed the exact secrets/JSON-input snippets the
    // redaction policy exists to keep out of logs (e.g. a JsonDecodingException's message
    // carries the offending body). We keep the stack FRAMES (class/method/line — useful,
    // non-secret) but strip the message: persist class identity only, via the shared
    // SensitiveLogPolicy. Extracted as a pure function so it is JVM-unit-testable without
    // an emulator (markCrashed itself only does SharedPreferences I/O).
    internal fun buildRedactedStackTrace(threadName: String, throwable: Throwable): String {
        return buildString {
            appendLine("Thread: $threadName")
            appendThrowable(throwable, prefix = "Exception")
        }.take(MAX_STACKTRACE_LENGTH)
    }

    private fun StringBuilder.appendThrowable(throwable: Throwable, prefix: String) {
        appendLine("$prefix: ${SensitiveLogPolicy.safeExceptionMessage(throwable)}")
        throwable.stackTrace.forEach { frame ->
            appendLine("\tat $frame")
        }
        throwable.cause?.let { cause ->
            appendThrowable(cause, prefix = "Caused by")
        }
    }
}
