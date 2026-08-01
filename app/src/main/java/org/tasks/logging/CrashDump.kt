package org.tasks.logging

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes crash reports somewhere reachable without adb.
 *
 * The rotating log lives in the cache directory, which is unreadable on an unrooted device, and a
 * crash during startup means the in-app "send logs" screen can never be opened. This drops a plain
 * text file into the public Downloads folder instead, which every file manager can browse.
 */
object CrashDump {

    private const val DIRECTORY = "tasks-debug-logs"

    fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
        val name = "tasks-crash-$stamp.txt"
        val body = buildString {
            appendLine("Tasks crash report")
            appendLine("time:    $stamp")
            appendLine("thread:  ${thread.name}")
            appendLine("package: ${context.packageName}")
            appendLine("device:  ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(throwable.stackTraceToString())
            var cause = throwable.cause
            while (cause != null) {
                appendLine("--- caused by ---")
                appendLine(cause.stackTraceToString())
                cause = cause.cause
            }
        }
        // Best effort by definition - this runs while the process is already dying, and a failure
        // here must never replace the original crash.
        runCatching { writeToDownloads(context, name, body) }
        runCatching { writeToExternalFiles(context, name, body) }
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
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DIRECTORY")
        }
        val resolver = context.contentResolver
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
        }
    }

    /** Fallback for devices where the Downloads insert is refused. */
    private fun writeToExternalFiles(context: Context, name: String, body: String) {
        val dir = File(context.getExternalFilesDir(null) ?: return, "crashes")
        dir.mkdirs()
        File(dir, name).writeText(body)
    }
}
