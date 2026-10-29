package com.example.diagnostictool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

data class ServiceMeta(
    val carId: String,
    val currentMileage: Int,
    val currentDate: String,
    val toInterval: Int = 15000,
    val oilInterval: Int = 10000,
    val notifyThreshold: Int = 500
)

data class ServiceDue(
    val kind: String,
    val lastMileage: Int,
    val nextMileage: Int,
    val remaining: Int,
    val overdue: Boolean,
    val dueSoon: Boolean
)

class ServiceStore(context: Context) {
    private val prefs = context.getSharedPreferences("service_book", Context.MODE_PRIVATE)
    private val itemsKey = "items"
    private val metaKey = "meta"

    companion object {
        val KINDS = listOf("ТО", "Замена масла", "Ремонт", "Прочее")

        fun emoji(kind: String): String = when (kind) {
            "ТО" -> "🛠️"
            "Замена масла" -> "🛢️"
            "Ремонт" -> "🔧"
            else -> "📝"
        }
    }

    fun all(): List<ServiceEntry> {
        val a = JSONArray(prefs.getString(itemsKey, "[]"))
        return (0 until a.length()).map { ServiceEntry.fromJson(a.getJSONObject(it)) }
    }

    /** Сначала по дате (новые сверху), затем по пробегу. */
    fun forCar(carId: String): List<ServiceEntry> =
        all().filter { it.carId == carId }
            .sortedWith(
                compareByDescending<ServiceEntry> { dateValue(it.date) }
                    .thenByDescending { it.mileage.trim().toIntOrNull() ?: 0 }
            )

    fun create(carId: String): ServiceEntry =
        ServiceEntry(UUID.randomUUID().toString(), carId, today(), "", "ТО", "", "")

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
        prefs.edit().putString(itemsKey, a.toString()).apply()
    }

    // --- Текущий пробег/дата и интервалы ---

    /**
     * Метаданные сервисной книжки. При первом обращении пробег берётся из профиля
     * автомобиля (пробег, введённый при добавлении), дата — сегодняшняя.
     */
    fun meta(car: Car): ServiceMeta {
        val root = JSONObject(prefs.getString(metaKey, "{}"))
        val o = root.optJSONObject(car.id)
        if (o != null) {
            return ServiceMeta(
                carId = car.id,
                currentMileage = o.optInt("currentMileage", initialMileage(car)),
                currentDate = o.optString("currentDate", today()),
                toInterval = o.optInt("toInterval", 15000),
                oilInterval = o.optInt("oilInterval", 10000),
                notifyThreshold = o.optInt("notifyThreshold", 500)
            )
        }
        return ServiceMeta(
            carId = car.id,
            currentMileage = initialMileage(car),
            currentDate = today()
        ).also { saveMeta(it) }
    }

    fun ensureMeta(car: Car) { meta(car) }

    fun saveMeta(m: ServiceMeta) {
        val root = JSONObject(prefs.getString(metaKey, "{}"))
        root.put(m.carId, JSONObject().apply {
            put("currentMileage", m.currentMileage)
            put("currentDate", m.currentDate)
            put("toInterval", m.toInterval)
            put("oilInterval", m.oilInterval)
            put("notifyThreshold", m.notifyThreshold)
        })
        prefs.edit().putString(metaKey, root.toString()).apply()
    }

    /** Сроки ТО и замены масла относительно текущего пробега. */
    fun dues(car: Car): List<ServiceDue> {
        val m = meta(car)
        val entries = forCar(car.id)
        val rules = listOf("ТО" to m.toInterval, "Замена масла" to m.oilInterval)
        return rules.map { (kind, interval) ->
            val relevant = entries.filter { e ->
                if (kind == "Замена масла") {
                    e.kind == "Замена масла" || e.title.contains("масл", ignoreCase = true)
                } else {
                    e.kind == "ТО" && !e.title.contains("масл", ignoreCase = true)
                }
            }
            val last = relevant.mapNotNull { it.mileage.trim().toIntOrNull() }.maxOrNull()
            val base = last ?: m.currentMileage
            val next = base + interval
            val remaining = next - m.currentMileage
            ServiceDue(
                kind = kind,
                lastMileage = last ?: 0,
                nextMileage = next,
                remaining = remaining,
                overdue = remaining < 0,
                dueSoon = remaining in 0..m.notifyThreshold
            )
        }
    }

    private fun initialMileage(car: Car): Int =
        car.mileage.filter { it.isDigit() }.toIntOrNull() ?: 0

    private fun today(): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())

    private fun dateValue(s: String): Long = runCatching {
        SimpleDateFormat("dd.MM.yyyy", Locale.US).apply { isLenient = false }.parse(s)?.time ?: 0L
    }.getOrDefault(0L)
}
