package org.tasks.logging

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes crash reports somewhere reachable without adb.
 *
 * The rotating log lives in the cache directory, which is unreadable on an unrooted device, and a
 * crash during startup means the in-app "send logs" screen can never be opened. This drops plain
 * text files into the public Downloads folder instead, which every file manager can browse.
 *
 * [install] and [logStartup] are called from `attachBaseContext`, which is the first Application
 * callback there is - earlier than content provider creation and earlier than Hilt injection, both
 * of which are prime suspects for a crash on launch.
 */
object CrashDump {

    private const val DIRECTORY = "tasks-debug-logs"
    private const val MIME = "text/plain"
    private const val TRACE_LIMIT = 64 * 1024

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { Timber.e(throwable, "Uncaught exception in thread $thread") }
            runCatching { write(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable) ?: throw throwable
        }
    }

    /**
     * Records that the process started, and why the previous one died.
     *
     * The breadcrumb is the point of this: if a startup file appears but no crash file does, the
     * write path works and the crash is somewhere the handler cannot see (native, ANR, or before
     * this runs). If no file appears at all, the app is dying before it gets this far.
     */
    fun logStartup(context: Context) {
        runCatching {
            val stamp = stamp()
            val body = buildString {
                appendLine("Tasks startup")
                appendLine("time:    $stamp")
                append(describeEnvironment(context))
                appendLine()
                appendLine(previousExits(context))
            }
            writeEverywhere(context, "tasks-startup-$stamp.txt", body)
        }
    }

    fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = stamp()
        val body = buildString {
            appendLine("Tasks crash report")
            appendLine("time:    $stamp")
            appendLine("thread:  ${thread.name}")
            append(describeEnvironment(context))
            appendLine()
            appendLine(throwable.stackTraceToString())
            var cause = throwable.cause
            while (cause != null) {
                appendLine("--- caused by ---")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
            }
        }
        writeEverywhere(context, "tasks-crash-$stamp.txt", body)
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())

    private fun describeEnvironment(context: Context) = buildString {
        appendLine("package: ${context.packageName}")
        appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
    }

    /**
     * Reads why previous processes died.
     *
     * This is the only channel that survives a crash the in-process handler never sees, because the
     * system records it rather than the app. Anything before [install] runs - a failing content
     * provider, a native crash, the linker - shows up here on the next launch instead.
     */
    private fun previousExits(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "previous exits: needs Android 11+"
        }
        val result = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(null, 0, 5)
        }.getOrElse { return "previous exits: unavailable (${it.javaClass.simpleName}: ${it.message})" }
        if (result.isEmpty()) {
            return "previous exits: none recorded"
        }
        return buildString {
            appendLine("previous exits (newest first):")
            result.forEach { info ->
                appendLine()
                appendLine("  when:        ${Date(info.timestamp)}")
                appendLine("  process:     ${info.processName}")
                appendLine("  reason:      ${reasonName(info.reason)} (${info.reason})")
                appendLine("  status:      ${info.status}")
                appendLine("  description: ${info.description}")
                readTrace(info)?.let {
                    appendLine("  --- system trace ---")
                    appendLine(it.prependIndent("  "))
                }
            }
        }
    }

    private fun readTrace(info: ApplicationExitInfo): String? = runCatching {
        info.traceInputStream?.use { stream ->
            String(stream.readBytes().take(TRACE_LIMIT).toByteArray())
        }
    }.getOrNull()

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (unhandled java exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN"
    }

    private fun writeEverywhere(context: Context, name: String, body: String) {
        // Best effort by definition - the crash path runs while the process is already dying, and a
        // failure here must never replace the original crash. The Downloads failure is appended to
        // the private copy rather than swallowed, so a broken write path can still be diagnosed.
        val downloads = runCatching { writeToDownloads(context, name, body) }
        val note = downloads.exceptionOrNull()
            ?.let { "\n\n(Downloads write failed: ${it.javaClass.name}: ${it.message})\n" }
            ?: ""
        runCatching { writeToExternalFiles(context, name, body + note) }
    }

    private fun writeToDownloads(context: Context, name: String, body: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIRECTORY)
            dir.mkdirs()
            File(dir, name).writeText(body)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused the insert")
        resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            ?: throw IllegalStateException("MediaStore returned no output stream")
    }

    /** Fallback for devices where the Downloads insert is refused. */
    private fun writeToExternalFiles(context: Context, name: String, body: String) {
        val dir = File(context.getExternalFilesDir(null) ?: return, "crashes")
        dir.mkdirs()
        File(dir, name).writeText(body)
    }
}
