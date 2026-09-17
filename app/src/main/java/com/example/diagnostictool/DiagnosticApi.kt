package com.example.diagnostictool

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

object DiagnosticApi {
    // Set this to the address where the standalone Diagnostic Tool backend is deployed.
    // It must never point to another project's server.
    private const val BASE_URL = "https://<YOUR-DIAGNOSTIC-SERVER>"
    private val executor = Executors.newCachedThreadPool()

    data class Status(
        val id: String,
        val state: String,
        val stage: String,
        val message: String,
        val question: String,
        val options: List<String>,
        val conclusion: String,
        val pdfUrl: String
    )

    fun upload(context: Context, car: Car, sessionName: String, complaint: String, files: List<Uri>, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                val boundary = "----AlfaDiagnostic${UUID.randomUUID()}"
                val url = URL("$BASE_URL/v1/diagnostics")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 120_000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Accept", "application/json")
                }
                DataOutputStream(c.outputStream).use { out ->
                    writeField(out, boundary, "car", car.toJson().toString())
                    writeField(out, boundary, "sessionName", sessionName)
                    writeField(out, boundary, "complaint", complaint)
                    files.forEach { uri ->
                        val name = displayName(context, uri) ?: "session-file"
                        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                        out.writeBytes("--$boundary\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"${name.replace("\"", "_")}\"\r\n")
                        out.writeBytes("Content-Type: $mime\r\n\r\n")
                        context.contentResolver.openInputStream(uri)?.use { input -> input.copyTo(out) }
                        out.writeBytes("\r\n")
                    }
                    out.writeBytes("--$boundary--\r\n")
                }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(JSONObject(body).getString("id")))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun status(id: String, callback: (Result<Status>) -> Unit) {
        executor.execute {
            try {
                val c = (URL("$BASE_URL/v1/diagnostics/$id").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 30_000
                }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(parseStatus(body)))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun answer(id: String, text: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            try {
                val c = (URL("$BASE_URL/v1/diagnostics/$id/messages").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(Unit))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    private fun parseStatus(body: String): Status {
        val o = JSONObject(body); val a = o.optJSONArray("options") ?: JSONArray()
        val options = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        return Status(o.optString("id"), o.optString("state"), o.optString("stage"), o.optString("message"), o.optString("question"), options, o.optString("conclusion"), o.optString("pdfUrl"))
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) }
        return uri.lastPathSegment
    }

    private fun readResponse(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return BufferedReader(InputStreamReader(stream ?: error("Пустой ответ"), Charsets.UTF_8)).use { it.readText() }
    }
}
