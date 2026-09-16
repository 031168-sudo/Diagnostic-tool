package com.example.diagnostictool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewMainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var obdValues: TextView
    private lateinit var recordButton: Button
    private lateinit var recordStatus: TextView
    private lateinit var currentCarText: TextView
    private lateinit var connectButton: Button
    private lateinit var obd: TargetElm327Ble
    private var recorder: PublicSessionRecorder? = null
    private val carStore by lazy { CarStore(this) }
    private val recordStore by lazy { DiagnosticRecordStore(this) }
    private val requestCode = 10
    private var pendingConnect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        obdValues = findViewById(R.id.obdValues)
        recordButton = findViewById(R.id.record)
        recordStatus = findViewById(R.id.recordStatus)
        currentCarText = findViewById(R.id.currentCar)
        connectButton = findViewById(R.id.connect)

        findViewById<Button>(R.id.cars).setOnClickListener { showCarsDialog() }
        findViewById<Button>(R.id.addCar).setOnClickListener { showCarEditor(carStore.create()) }
        findViewById<Button>(R.id.history).setOnClickListener { showHistory() }
        connectButton.setOnClickListener { requestAndConnect() }
        recordButton.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.openFolder).setOnClickListener { openDownloads() }

        refreshCarUi()
    }

    private fun refreshCarUi() {
        val car = carStore.selected()
        if (car == null) {
            currentCarText.text = "Автомобиль не выбран\nСначала добавьте автомобиль"
            connectButton.isEnabled = false
            recordButton.isEnabled = false
            return
        }
        currentCarText.text = "${car.title()}\n${car.subtitle().ifBlank { "Параметры не заполнены" }}"
        connectButton.isEnabled = true
        recordButton.isEnabled = true
        configureObd(car)
    }

    private fun configureObd(car: Car) {
        if (::obd.isInitialized) obd.close()
        val mac = car.obdMac.trim().ifBlank { TargetElm327Ble.DEFAULT_TARGET_MAC }
        obd = TargetElm327Ble(this, object : TargetElm327Ble.Listener {
            override fun onState(text: String) = runOnUiThread { status.text = text }
            override fun onData(values: ObdValues, monotonicNs: Long) {
                runOnUiThread { obdValues.text = values.toDisplay() }
                recorder?.onObd(values, monotonicNs)
            }
        }, mac)
        status.text = "OBD-адаптер: $mac"
    }

    private fun showCarsDialog() {
        val cars = carStore.all()
        if (cars.isEmpty()) { showCarEditor(carStore.create()); return }
        val labels = cars.map { "${it.title()}${it.subtitle().let { s -> if (s.isBlank()) "" else "\n$s" }}" }.toTypedArray()
        val selected = cars.indexOfFirst { it.id == carStore.selectedId() }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Автомобили")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                carStore.select(cars[which].id); refreshCarUi(); dialog.dismiss()
            }
            .setNeutralButton("Изменить") { _, _ -> showCarEditor(cars[selected]) }
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Добавить") { _, _ -> showCarEditor(carStore.create()) }
            .show()
    }

    private fun showCarEditor(car: Car) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 0) }
        fun field(hint: String, value: String = ""): EditText = EditText(this).apply { this.hint = hint; setText(value); setSingleLine(false); form.addView(this) }
        val make = field("Марка", car.make); val model = field("Модель", car.model); val year = field("Год выпуска", car.year)
        val engine = field("Двигатель / объём", car.engine); val fuel = field("Топливо", car.fuel); val transmission = field("Коробка передач", car.transmission)
        val drive = field("Привод", car.drive); val mileage = field("Пробег, км", car.mileage); val vin = field("VIN (необязательно)", car.vin)
        val plate = field("Госномер (необязательно)", car.plate); val obdMac = field("MAC ELM327", car.obdMac.ifBlank { TargetElm327Ble.DEFAULT_TARGET_MAC }); val notes = field("Дополнительная информация", car.notes)
        val scroll = android.widget.ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this).setTitle(if (car.make.isBlank() && car.model.isBlank()) "Добавить автомобиль" else "Автомобиль")
            .setView(scroll)
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Удалить") { _, _ -> if (carStore.all().any { it.id == car.id }) { carStore.delete(car.id); refreshCarUi() } }
            .setPositiveButton("Сохранить") { _, _ ->
                car.make = make.text.toString().trim(); car.model = model.text.toString().trim(); car.year = year.text.toString().trim(); car.engine = engine.text.toString().trim()
                car.fuel = fuel.text.toString().trim(); car.transmission = transmission.text.toString().trim(); car.drive = drive.text.toString().trim(); car.mileage = mileage.text.toString().trim()
                car.vin = vin.text.toString().trim(); car.plate = plate.text.toString().trim(); car.obdMac = obdMac.text.toString().trim(); car.notes = notes.text.toString().trim()
                if (car.make.isBlank() || car.model.isBlank()) return@setPositiveButton
                carStore.save(car); carStore.select(car.id); refreshCarUi()
            }.show()
    }

    private fun showHistory() {
        val car = carStore.selected() ?: run { showCarsDialog(); return }
        val records = recordStore.forCar(car.id)
        val text = if (records.isEmpty()) "Диагностик пока нет." else records.joinToString("\n\n") {
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(it.createdAt))
            "${date}\n${it.complaint.ifBlank { "Без описания неисправности" }}\nСессия: ${it.sessionName}"
        }
        AlertDialog.Builder(this).setTitle("История — ${car.title()}").setMessage(text).setPositiveButton("Закрыть", null).show()
    }

    private fun requestAndConnect() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) { permissions += Manifest.permission.BLUETOOTH_SCAN; permissions += Manifest.permission.BLUETOOTH_CONNECT }
        permissions += Manifest.permission.RECORD_AUDIO
        pendingConnect = true
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(permissions.toTypedArray(), requestCode) else obd.connect()
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == requestCode && results.all { it == PackageManager.PERMISSION_GRANTED } && pendingConnect) obd.connect()
        pendingConnect = false
    }

    private fun toggleRecording() {
        val car = carStore.selected() ?: run { showCarsDialog(); return }
        if (recorder == null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestAndConnect(); return }
            val r = PublicSessionRecorder(this, car) { text -> runOnUiThread { recordStatus.text = text } }
            recorder = r; r.start(); recordButton.text = "ОСТАНОВИТЬ ЗАПИСЬ"
        } else {
            val r = recorder ?: return
            r.stop(); recorder = null; recordButton.text = "НАЧАТЬ ЗАПИСЬ"
            showComplaintDialog(car, r.sessionName)
        }
    }

    private fun showComplaintDialog(car: Car, sessionName: String) {
        val input = EditText(this).apply { hint = "Опишите неисправность своими словами"; minLines = 5; gravity = android.view.Gravity.TOP }
        AlertDialog.Builder(this).setTitle("Описание неисправности").setView(input)
            .setNegativeButton("Пропустить") { _, _ -> recordStore.add(car.id, sessionName, "") }
            .setPositiveButton("Сохранить") { _, _ -> recordStore.add(car.id, sessionName, input.text.toString().trim()) }
            .show()
    }

    private fun openDownloads() {
        try { startActivity(Intent("android.intent.action.VIEW_DOWNLOADS")) } catch (_: Exception) { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
    }

    override fun onDestroy() {
        recorder?.stop(); recorder = null
        if (::obd.isInitialized) obd.close()
        super.onDestroy()
    }
}
