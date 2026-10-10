package com.example.diagnostictool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ServiceEntry(
    val id: String,
    val carId: String,
    val date: String,
    val mileage: String,
    val kind: String,
    val title: String,
    val note: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("carId", carId); put("date", date); put("mileage", mileage)
        put("kind", kind); put("title", title); put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject) = ServiceEntry(
            o.optString("id"), o.optString("carId"), o.optString("date"), o.optString("mileage"),
            o.optString("kind"), o.optString("title"), o.optString("note")
        )
    }
}

class ServiceStore(context: Context) {
    private val prefs = context.getSharedPreferences("service_book", Context.MODE_PRIVATE)
    private val key = "items"

    companion object {
        val KINDS = listOf("ТО", "Ремонт", "Прочее")
        private val INTERVALS = listOf(
            "масло" to 10000,
            "грм" to 90000,
            "ремень" to 90000,
            "свеч" to 30000,
            "фильтр" to 30000,
            "воздушн" to 30000,
            "салон" to 30000,
            "топливн" to 40000,
            "колодк" to 40000,
            "тормоз" to 40000,
            "антифриз" to 60000,
            "жидкост" to 60000,
            "акпп" to 60000,
            "редуктор" to 60000
        )
    }

    fun all(): List<ServiceEntry> {
        val a = JSONArray(prefs.getString(key, "[]"))
        return (0 until a.length()).map { ServiceEntry.fromJson(a.getJSONObject(it)) }
    }

    fun forCar(carId: String): List<ServiceEntry> =
        all().filter { it.carId == carId }.sortedByDescending { it.mileage.toIntOrNull() ?: 0 }

    fun create(carId: String): ServiceEntry =
        ServiceEntry(UUID.randomUUID().toString(), carId, "", "", "ТО", "", "")

    fun save(entry: ServiceEntry) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) list[i] = entry else list.add(entry)
        write(list)
    }

    fun delete(id: String) { write(all().filter { it.id != id }) }

    private fun write(list: List<ServiceEntry>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        prefs.edit().putString(key, a.toString()).apply()
    }

    fun hints(car: Car, entries: List<ServiceEntry>): List<String> {
        val currentMileage = car.mileage.trim().toIntOrNull()
        val year = car.year.trim().toIntOrNull()
        val oldCar = year != null && year <= 2014
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (e in entries.sortedByDescending { it.mileage.toIntOrNull() ?: 0 }) {
            val text = (e.title + " " + e.note).lowercase()
            val rule = INTERVALS.firstOrNull { text.contains(it.first) } ?: continue
            if (rule.first in seen) continue
            seen += rule.first
            val m = e.mileage.trim().toIntOrNull() ?: continue
            var interval = rule.second
            if (rule.first == "масло" && oldCar) interval = 7000
            val next = m + interval
            val label = rule.first.replaceFirstChar { it.uppercase() }
            val line = if (currentMileage != null) {
                val rem = next - currentMileage
                if (rem > 0) "$label: последнее на $m км → следующее ~$next км (осталось $rem км)"
                else "$label: пора (следующее было ~$next км)"
            } else "$label: последнее на $m км → следующее ~$next км"
            result += line
        }
        return result
    }
}
