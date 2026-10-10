package com.example.diagnostictool

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

class LiveLog(context: Context) {
    private val dir: File = context.getExternalFilesDir("logs") ?: context.filesDir
    val file: File = File(dir, "obd_live_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt")
    private val queue = LinkedBlockingQueue<String>()

    init {
        dir.mkdirs()
        val t = Thread {
            var out: OutputStream? = null
            while (true) {
                val line = try { queue.take() } catch (_: InterruptedException) { break }
                try {
                    if (out == null) out = FileOutputStream(file, true)
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (_: Throwable) {
                    try { out?.close() } catch (_: Throwable) {}
                    out = null
                }
            }
        }
        t.isDaemon = true
        t.name = "obd-live-log"
        t.start()
    }

    fun append(line: String) {
        queue.offer(line)
    }

    fun displayPath(): String = file.absolutePath
}
