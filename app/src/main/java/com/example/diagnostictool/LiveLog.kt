package com.example.diagnostictool

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

class LiveLog(context: Context) {
    private val appContext = context.applicationContext
    private val fileName = "obd_live_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
    private val relativeDir = "Download/DiagnosticTool/logs"
    private var publicUri: Uri? = null
    private val queue = LinkedBlockingQueue<String>()

    init {
        val t = Thread {
            var out: OutputStream? = null
            while (true) {
                val line = try { queue.take() } catch (_: InterruptedException) { break }
                try {
                    if (out == null) out = open()
                    out?.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out?.flush()
                } catch (_: Exception) {
                    try { out?.close() } catch (_: Exception) {}
                    out = null
                }
            }
        }
        t.isDaemon = true
        t.name = "obd-live-log"
        t.start()
    }

    private fun open(): OutputStream? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                publicUri = uri
                return appContext.contentResolver.openOutputStream(uri, "wa")
            }
        }
        val dir = appContext.getExternalFilesDir("logs") ?: appContext.filesDir
        dir.mkdirs()
        return FileOutputStream(File(dir, fileName), true)
    }

    fun append(line: String) {
        queue.offer(line)
    }

    fun displayPath(): String = if (Build.VERSION.SDK_INT >= 29) "$relativeDir/$fileName" else File(appContext.getExternalFilesDir("logs") ?: appContext.filesDir, fileName).absolutePath

    fun resolveUri(): Uri? {
        publicUri?.let { return it }
        if (Build.VERSION.SDK_INT < 29) return null
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
            arrayOf(fileName, "$relativeDir/"),
            null
        )?.use { if (it.moveToFirst()) return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString()) }
        return null
    }
}
