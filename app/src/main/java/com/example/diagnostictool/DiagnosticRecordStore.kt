package com.example.diagnostictool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticRecordStore(context: Context) {
    data class Record(
        val id: String,
        val carId: String,
        val sessionName: String,
        val complaint: String,
        val createdAt: Long,
        val aiSent: Boolean,
        val aiResponseUri: String?,
        val diagnosticId: String?,
        val sessionUri: String?,
        val transcriptJson: String?
    )

    private val prefs = context.getSharedPreferences("diagnostic_records", Context.MODE_PRIVATE)

    fun add(carId: String, sessionName: String, complaint: String) {
        val a = JSONArray(prefs.getString("items", "[]"))
        val item = JSONObject()
        item.put("id", sessionName)
        item.put("carId", carId)
        item.put("sessionName", sessionName)
        item.put("complaint", complaint)
        item.put("createdAt", System.currentTimeMillis())
        item.put("aiSent", false)
        a.put(item)
        prefs.edit().putString("items", a.toString()).apply()
    }

    fun markAiSent(sessionName: String, diagnosticId: String) { update(sessionName) { it.put("aiSent", true); it.put("diagnosticId", diagnosticId) } }
    fun setAiResponse(sessionName: String, uri: String) { update(sessionName) { it.put("aiSent", true); it.put("aiResponseUri", uri) } }
    fun setSessionUri(sessionName: String, uri: String) { update(sessionName) { it.put("sessionUri", uri) } }
    fun setComplaint(sessionName: String, complaint: String) { update(sessionName) { it.put("complaint", complaint) } }
    fun setTranscript(sessionName: String, json: String) { update(sessionName) { it.put("transcript", json) } }

    fun transcript(sessionName: String): String? {
        val a = JSONArray(prefs.getString("items", "[]"))
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (x.optString("sessionName") == sessionName) return x.optString("transcript").ifBlank { null }
        }
        return null
    }

    fun delete(sessionName: String) {
        val a = JSONArray(prefs.getString("items", "[]"))
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (x.optString("sessionName") != sessionName) out.put(x)
        }
        prefs.edit().putString("items", out.toString()).apply()
    }

    private fun update(sessionName: String, change: (JSONObject) -> Unit) {
        val a = JSONArray(prefs.getString("items", "[]"))
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i)
            if (x.optString("sessionName") == sessionName) { change(x); break }
        }
        prefs.edit().putString("items", a.toString()).apply()
    }

    fun forCar(carId: String): List<Record> = all().filter { it.carId == carId }.reversed()

    fun all(): List<Record> {
        val a = JSONArray(prefs.getString("items", "[]"))
        return (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }

    fun rawJson(): String = prefs.getString("items", "[]") ?: "[]"

    fun replaceRaw(json: String) { prefs.edit().putString("items", json).apply() }

    fun setFileUris(sessionName: String, sessionUri: String?, pdfUri: String?) {
        update(sessionName) {
            if (sessionUri != null) it.put("sessionUri", sessionUri)
            if (pdfUri != null) it.put("aiResponseUri", pdfUri)
        }
    }

    private fun parse(x: JSONObject) = Record(
        x.optString("id"), x.optString("carId"), x.optString("sessionName"), x.optString("complaint"),
        x.optLong("createdAt"), x.optBoolean("aiSent", false),
        x.optString("aiResponseUri").ifBlank { null }, x.optString("diagnosticId").ifBlank { null },
        x.optString("sessionUri").ifBlank { null }, x.optString("transcript").ifBlank { null }
    )
}
