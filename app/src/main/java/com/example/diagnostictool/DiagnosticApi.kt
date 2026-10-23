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
        val document: String = "",
        val transcript: List<Pair<String, String>> = emptyList()
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

    data class Account(
        val accountId: String,
        val token: String,
        val credits: Int,
        val subscriptionUntil: String,
        val spentMicros: Long = 0
    )

    data class TransferCode(val code: String, val expiresAt: String)

    fun upload(
        context: Context,
        car: Car,
        sessionName: String,
        complaint: String,
        files: List<Uri>,
        accountToken: String?,
        onProgress: ((Int) -> Unit)? = null,
        callback: (Result<String>) -> Unit
    ) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val boundary = "----AlfaDiagnostic${UUID.randomUUID()}"

                val head = java.io.ByteArrayOutputStream()
                writeField(head, boundary, "car", car.toJson().toString())
                writeField(head, boundary, "sessionName", sessionName)
                writeField(head, boundary, "complaint", complaint)
                val headBytes = head.toByteArray()

                val parts = files.map { uri ->
                    val name = (displayName(context, uri) ?: "session-file").replace("\"", "_")
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    FilePart(uri, name, mime, fileSize(context, uri))
                }
                val closing = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)
                val total = headBytes.size.toLong() + parts.sumOf { it.header(boundary).size.toLong() + it.size + 2L } + closing.size

                val c = (URL("$baseUrl/v1/diagnostics").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 30_000; readTimeout = 120_000
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                    applyAccountHeader(this, accountToken)
                }
                var lastPct = -1
                val counting = ProgressOutputStream(c.outputStream) { written ->
                    if (onProgress != null && total > 0) {
                        val pct = ((written * 100) / total).toInt().coerceIn(0, 99)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
                DataOutputStream(counting).use { out ->
                    out.write(headBytes)
                    parts.forEach { part ->
                        out.write(part.header(boundary))
                        context.contentResolver.openInputStream(part.uri)?.use { input -> input.copyTo(out) }
                        out.write("\r\n".toByteArray(Charsets.UTF_8))
                    }
                    out.write(closing)
                }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                onProgress?.invoke(100)
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

    fun retry(id: String, callback: (Result<Unit>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/diagnostics/$id/retry").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                }
                c.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(Unit))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun finalize(id: String, callback: (Result<String>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/diagnostics/$id/finalize").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 180_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                }
                c.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(JSONObject(body).optJSONObject("document")?.toString() ?: ""))
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

    fun accountRegister(callback: (Result<Account>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/account/register").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                }
                c.outputStream.use { }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(parseAccount(body)))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun accountMe(token: String, callback: (Result<Account>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/account/me").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this); applyAccountHeader(this, token)
                }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(parseAccount(body)))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun accountTransferCreate(token: String, callback: (Result<TransferCode>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/account/transfer/create").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this); applyAccountHeader(this, token)
                }
                c.outputStream.use { }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                val o = JSONObject(body)
                callback(Result.success(TransferCode(o.optString("code"), o.optString("expiresAt"))))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    fun accountTransferRedeem(code: String, callback: (Result<Account>) -> Unit) {
        executor.execute {
            try {
                require(baseUrl.isNotBlank()) { "Адрес сервера диагностики не настроен в этой сборке" }
                val c = (URL("$baseUrl/v1/account/transfer/redeem").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    applyHeaders(this)
                }
                c.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)) }
                val body = readResponse(c)
                if (c.responseCode !in 200..299) error("Сервер: ${c.responseCode} $body")
                callback(Result.success(parseAccount(body)))
            } catch (e: Exception) { callback(Result.failure(e)) }
        }
    }

    private fun parseAccount(body: String): Account {
        val o = JSONObject(body)
        return Account(
            accountId = o.optString("accountId"),
            token = o.optString("token"),
            credits = o.optInt("credits", 0),
            subscriptionUntil = o.optString("subscriptionUntil"),
            spentMicros = o.optLong("spentMicros", 0)
        )
    }

    private fun parseStatus(body: String): Status {
        val o = JSONObject(body); val a = o.optJSONArray("options") ?: JSONArray()
        val options = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        val ta = o.optJSONArray("transcript") ?: JSONArray()
        val transcript = (0 until ta.length()).mapNotNull { i ->
            val item = ta.optJSONObject(i) ?: return@mapNotNull null
            val text = item.optString("text")
            if (text.isBlank()) null else item.optString("role") to text
        }
        return Status(o.optString("id"), o.optString("state"), o.optString("stage"), o.optString("message"), o.optString("question"), options, o.optString("conclusion"), o.optString("pdfUrl"), o.optJSONObject("document")?.toString() ?: "", transcript)
    }

    private fun writeField(out: java.io.OutputStream, boundary: String, name: String, value: String) {
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(value.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private class FilePart(val uri: Uri, val name: String, val mime: String, val size: Long) {
        fun header(boundary: String): ByteArray =
            "--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
    }

    private class ProgressOutputStream(
        private val delegate: java.io.OutputStream,
        private val onBytes: (Long) -> Unit
    ) : java.io.OutputStream() {
        private var written = 0L
        private var lastReport = 0L
        override fun write(b: Int) { delegate.write(b); written++; report() }
        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); written += len; report() }
        private fun report() { if (written - lastReport >= 32_768) { lastReport = written; onBytes(written) } }
        override fun flush() { delegate.flush() }
        override fun close() { delegate.close() }
    }

    private fun fileSize(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).coerceAtLeast(0L) else 0L
        } ?: 0L
    } catch (e: Exception) { 0L }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) return it.getString(0) }
        return uri.lastPathSegment
    }

    private fun applyHeaders(c: HttpURLConnection) {
        val token = BuildConfig.DIAGNOSTIC_API_TOKEN
        if (token.isNotBlank()) c.setRequestProperty("X-Api-Key", token)
    }

    private fun applyAccountHeader(c: HttpURLConnection, token: String?) {
        if (!token.isNullOrBlank()) c.setRequestProperty("X-Account-Token", token)
    }

    private fun readResponse(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return BufferedReader(InputStreamReader(stream ?: error("Пустой ответ"), Charsets.UTF_8)).use { it.readText() }
    }
}
