package com.example.diagnostictool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticRecordStore(context: Context) {
    data class Record(val id: String, val carId: String, val sessionName: String, val complaint: String, val createdAt: Long)

    private val prefs = context.getSharedPreferences("diagnostic_records", Context.MODE_PRIVATE)

    fun add(carId: String, sessionName: String, complaint: String) {
        val a = JSONArray(prefs.getString("items", "[]"))
        a.put(JSONObject().apply {
            put("id", sessionName)
            put("carId", carId)
            put("sessionName", sessionName)
            put("complaint", complaint)
            put("createdAt", System.currentTimeMillis())
        })
        prefs.edit().putString("items", a.toString()).apply()
    }

    fun forCar(carId: String): List<Record> {
        val a = JSONArray(prefs.getString("items", "[]"))
        return (0 until a.length()).map { o ->
            val x = a.getJSONObject(o)
            Record(x.optString("id"), x.optString("carId"), x.optString("sessionName"), x.optString("complaint"), x.optLong("createdAt"))
        }.filter { it.carId == carId }.reversed()
    }
}
