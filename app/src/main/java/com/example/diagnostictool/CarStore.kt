package com.example.diagnostictool

import android.content.Context
import org.json.JSONArray
import java.util.UUID

class CarStore(context: Context) {
    private val prefs = context.getSharedPreferences("cars", Context.MODE_PRIVATE)
    private val carsKey = "items"
    private val selectedKey = "selected_id"

    fun all(): MutableList<Car> {
        val array = JSONArray(prefs.getString(carsKey, "[]"))
        return MutableList(array.length()) { Car.fromJson(array.getJSONObject(it)) }
    }

    fun save(car: Car) {
        val cars = all()
        val index = cars.indexOfFirst { it.id == car.id }
        if (index >= 0) cars[index] = car else cars.add(car)
        write(cars)
        if (selectedId() == null) select(car.id)
    }

    fun create(): Car = Car(UUID.randomUUID().toString(), "", "")

    fun delete(id: String) {
        val cars = all().filter { it.id != id }
        write(cars)
        if (selectedId() == id) {
            prefs.edit().putString(selectedKey, cars.firstOrNull()?.id).apply()
        }
    }

    fun select(id: String) { prefs.edit().putString(selectedKey, id).apply() }

    fun selectedId(): String? = prefs.getString(selectedKey, null)

    fun selected(): Car? = all().firstOrNull { it.id == selectedId() }

    private fun write(cars: List<Car>) {
        val array = JSONArray()
        cars.forEach { array.put(it.toJson()) }
        prefs.edit().putString(carsKey, array.toString()).apply()
    }
}
