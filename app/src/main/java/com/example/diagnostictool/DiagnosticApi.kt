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
    private val executor = Executors.newCachedThreadPool()
    private val baseUrl: String
        get() = BuildConfig.DIAGNOSTIC_API_URL.trim().trimEnd('/')

    data class Status(
        val id: String,
        val state: String,
        val stage: String,
        val message: String,
        val question: String,
        val options: List<String>,
        val conclusion: String,
        val pdfUrl: String,
        val document: String = ""
    )

    data class VinInfo(
        val make: String,
        val model: String,
        val year: String,
        val engine: String,
        val fuel: String,
        val transmission: String,
        val drive: String,
        val body: String,
        val message: String
    )

    fun upload(context: Context, car: Car, sessionName: String, complaint: String, files: List<Uri>, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val boundary = "----AlfaDiagnostic${UUID.randomUUID()}"
                val c = (URL("$baseUrl/v1/diagnostics").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 120_000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
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
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/diagnostics/$id").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 30_000
                    applyHeaders(this)
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
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/diagnostics/$id/messages").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                }
                c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(Unit))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun chat(id: String, text: String, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/diagnostics/$id/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                }
                c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)) }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(JSONObject(body).optString("answer")))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun vin(vin: String, callback: (Result<VinInfo>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/vin/$vin").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                val o = JSONObject(body)
                callback(Result.success(VinInfo(
                    make = o.optString("make"), model = o.optString("model"), year = o.optString("year"),
                    engine = o.optString("engine"), fuel = o.optString("fuel"),
                    transmission = o.optString("transmission"), drive = o.optString("drive"), body = o.optString("body"),
                    message = o.optString("message")
                )))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    private fun parseStatus(body: String): Status {
        val o = JSONObject(body); val a = o.optJSONArray("options") ?: JSONArray()
        val options = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        return Status(o.optString("id"), o.optString("state"), o.optString("stage"), o.optString("message"), o.optString("question"), options, o.optString("conclusion"), o.optString("pdfUrl"), o.optJSONObject("document")?.toString() ?: "")
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) }
        return uri.lastPathSegment
    }

    private fun applyHeaders(c: HttpURLConnection) {
        val token = BuildConfig.DIAGNOSTIC_API_TOKEN
        if (token.isNotBlank()) c.setRequestProperty("X-Api-Key", token)
    }

    private fun readResponse(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return BufferedReader(InputStreamReader(stream ?: error("Пустой ответ"), Charsets.UTF_8)).use { it.readText() }
    }
}
