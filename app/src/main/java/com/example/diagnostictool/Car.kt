package com.example.diagnostictool

import org.json.JSONObject

data class Car(
    val id: String,
    var make: String,
    var model: String,
    var year: String = "",
    var engine: String = "",
    var fuel: String = "",
    var transmission: String = "",
    var drive: String = "",
    var vin: String = "",
    var plate: String = "",
    var mileage: String = "",
    var notes: String = "",
    var obdMac: String = ""
) {
    fun title(): String = listOf(make.trim(), model.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    fun subtitle(): String = listOf(year, engine, fuel).filter { it.isNotBlank() }.joinToString(" • ")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("make", make); put("model", model); put("year", year)
        put("engine", engine); put("fuel", fuel); put("transmission", transmission)
        put("drive", drive); put("vin", vin); put("plate", plate); put("mileage", mileage)
        put("notes", notes); put("obdMac", obdMac)
    }

    companion object {
        fun fromJson(o: JSONObject): Car = Car(
            id = o.optString("id"), make = o.optString("make"), model = o.optString("model"),
            year = o.optString("year"), engine = o.optString("engine"), fuel = o.optString("fuel"),
            transmission = o.optString("transmission"), drive = o.optString("drive"),
            vin = o.optString("vin"), plate = o.optString("plate"), mileage = o.optString("mileage"),
            notes = o.optString("notes"), obdMac = o.optString("obdMac")
        )
    }
}
