package com.example.diagnostictool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlfaMainActivity : AppCompatActivity() {
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
    private var lastSessionUris: List<Uri> = emptyList()
    private val permissionRequest = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        val root = findViewById<ScrollView>(R.id.mainRoot)
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 0
        root.setPadding(root.paddingLeft, statusBarHeight, root.paddingRight, root.paddingBottom)
        status = findViewById(R.id.status)
        obdValues = findViewById(R.id.obdValues)
        recordButton = findViewById(R.id.record)
        recordStatus = findViewById(R.id.recordStatus)
        currentCarText = findViewById(R.id.currentCar)
        connectButton = findViewById(R.id.connect)
        findViewById<Button>(R.id.changeCar).setOnClickListener { showCarsDialog() }
        findViewById<Button>(R.id.addCar).setOnClickListener { showCarEditor(carStore.create(), true) }
        findViewById<Button>(R.id.history).setOnClickListener { showHistory() }
        connectButton.setOnClickListener { requestObd() }
        recordButton.setOnClickListener { toggleRecording() }
        configureObd()
        enterCarFlow()
    }

    private fun enterCarFlow() {
        val cars = carStore.all()
        when (cars.size) {
            0 -> showFirstCarDialog()
            1 -> { carStore.select(cars[0].id); refreshCarUi() }
            else -> showCarsDialog()
        }
    }

    private fun showFirstCarDialog() {
        AlertDialog.Builder(this).setTitle("Добавьте автомобиль")
            .setMessage("Чтобы начать сбор информации, сначала добавьте автомобиль.")
            .setPositiveButton("Добавить автомобиль") { _, _ -> showCarEditor(carStore.create(), true) }
            .setCancelable(false).show()
    }

    fun hasLocationPermission(): Boolean = if (Build.VERSION.SDK_INT < 23) true else
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun refreshCarUi() {
        val car = carStore.selected()
        currentCarText.text = car?.title() ?: "Автомобиль не выбран"
        connectButton.isEnabled = car != null
        recordButton.isEnabled = car != null
    }

    private fun configureObd() {
        if (::obd.isInitialized) obd.close()
        obd = TargetElm327Ble(this, object : TargetElm327Ble.Listener {
            override fun onState(text: String) {
                runOnUiThread { status.text = text }
                if (text.contains("ELM327") && text.contains("готов")) recorder?.setObdActive(true)
                if (text.contains("отключён", true)) recorder?.setObdActive(false)
            }
            override fun onData(values: ObdValues, monotonicNs: Long) {
                runOnUiThread { obdValues.text = values.toDisplay() }
                recorder?.onObd(values, monotonicNs)
            }
            override fun onDevices(devices: List<TargetElm327Ble.DeviceInfo>) = runOnUiThread { showObdDevices(devices) }
        })
        status.text = "OBD-адаптер не подключён — запись возможна с GPS"
    }

    private fun showObdDevices(devices: List<TargetElm327Ble.DeviceInfo>) {
        val labels = devices.map { it.label() }.toTypedArray()
        if (labels.isEmpty()) { Toast.makeText(this, "OBD-адаптеры не найдены", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Выберите OBD-адаптер")
            .setSingleChoiceItems(labels, -1) { dialog, which -> dialog.dismiss(); obd.connect(devices[which].device) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun showCarsDialog() {
        val cars = carStore.all()
        if (cars.isEmpty()) { showFirstCarDialog(); return }
        val labels = cars.map { it.title() }.toTypedArray()
        val selected = cars.indexOfFirst { it.id == carStore.selectedId() }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Выберите автомобиль")
            .setSingleChoiceItems(labels, selected) { dialog, which -> carStore.select(cars[which].id); refreshCarUi(); dialog.dismiss() }
            .setNeutralButton("Изменить") { _, _ -> showCarEditor(cars[selected], false) }
            .setPositiveButton("Добавить") { _, _ -> showCarEditor(carStore.create(), true) }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun showCarEditor(car: Car, returnToDiagnostic: Boolean) {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 8, 40, 0) }
        scroll.addView(form)
        fun field(hint: String, value: String): EditText = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine(true); form.addView(this)
            setOnFocusChangeListener { view, hasFocus -> if (hasFocus) scroll.post { scroll.smoothScrollTo(0, view.bottom + 100) } }
        }
        val make = field("Марка *", car.make)
        val model = field("Модель *", car.model)
        val year = field("Год выпуска *", car.year)
        val engine = field("Двигатель / объём *", car.engine)
        val fuel = field("Топливо *", car.fuel)
        val transmission = field("Коробка передач *", car.transmission)
        val drive = field("Привод *", car.drive)
        val mileage = field("Пробег, км *", car.mileage)
        val vin = field("VIN (необязательно)", car.vin)
        val notes = field("Дополнительная информация (необязательно)", car.notes)
        val dialog = AlertDialog.Builder(this).setTitle(if (car.make.isBlank() && car.model.isBlank()) "Добавить автомобиль" else "Автомобиль")
            .setView(scroll).setNegativeButton("Отмена") { _, _ -> if (returnToDiagnostic && carStore.all().isEmpty()) showFirstCarDialog() }
            .setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val fields = listOf(make to "Марка", model to "Модель", year to "Год выпуска", engine to "Двигатель / объём", fuel to "Топливо", transmission to "Коробка передач", drive to "Привод", mileage to "Пробег")
                val missing = fields.filter { it.first.text.isNullOrBlank() }
                if (missing.isNotEmpty()) {
                    missing.forEach { it.first.error = "Обязательное поле" }
                    Toast.makeText(this, "Заполните: ${missing.joinToString(", ") { it.second }}", Toast.LENGTH_LONG).show()
                    missing.first().first.requestFocus(); return@setOnClickListener
                }
                car.make = make.text.toString().trim(); car.model = model.text.toString().trim(); car.year = year.text.toString().trim()
                car.engine = engine.text.toString().trim(); car.fuel = fuel.text.toString().trim(); car.transmission = transmission.text.toString().trim()
                car.drive = drive.text.toString().trim(); car.mileage = mileage.text.toString().trim(); car.vin = vin.text.toString().trim(); car.notes = notes.text.toString().trim()
                carStore.save(car); carStore.select(car.id); refreshCarUi(); dialog.dismiss()
            }
        }
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun showHistory() {
        val car = carStore.selected() ?: run { showFirstCarDialog(); return }
        val records = recordStore.forCar(car.id)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        if (records.isEmpty()) box.addView(TextView(this).apply { text = "Диагностик пока нет."; textSize = 16f })
        records.forEach { record ->
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(record.createdAt))
            box.addView(TextView(this).apply { text = "$date\n${record.sessionName}\n${record.complaint.ifBlank { "Жалоба не указана" }}"; textSize = 16f })
            box.addView(TextView(this).apply { text = when { record.aiResponseUri != null -> "ИИ: заключение получено"; record.aiSent -> "ИИ: ответ ожидается"; else -> "ИИ: не отправлено" }; textSize = 14f; setPadding(0, 4, 0, 4) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (!record.aiSent) row.addView(Button(this).apply { text = "Отправить ИИ"; setOnClickListener { startHistoricalDiagnostic(car, record) } })
            if (record.aiSent && record.diagnosticId != null) row.addView(Button(this).apply { text = "Открыть диагностику"; setOnClickListener { reopenDiagnostic(record) } })
            if (record.aiResponseUri != null) row.addView(Button(this).apply { text = "Открыть PDF"; setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(record.aiResponseUri), "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } })
            box.addView(row); box.addView(TextView(this).apply { setPadding(0, 0, 0, 16) })
        }
        AlertDialog.Builder(this).setTitle("История диагностики — ${car.title()}").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Закрыть", null).show()
    }

    private fun findSessionUris(sessionName: String): List<Uri> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val result = mutableListOf<Uri>()
        for (name in listOf("audio.wav", "obd.csv", "gps.csv", "sensors.csv", "session.json")) {
            contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(name, "Download/DiagnosticTool/sessions/$sessionName"), null)?.use {
                if (it.moveToFirst()) result += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString())
            }
        }
        return result
    }

    private fun startHistoricalDiagnostic(car: Car, record: DiagnosticRecordStore.Record) {
        val uris = findSessionUris(record.sessionName)
        if (uris.isEmpty()) { Toast.makeText(this, "Файлы этой сессии не найдены", Toast.LENGTH_LONG).show(); return }
        uploadDiagnostic(car, record.sessionName, record.complaint, uris)
    }

    private fun uploadDiagnostic(car: Car, sessionName: String, complaint: String, uris: List<Uri>) {
        Toast.makeText(this, "Отправляю диагностическую сессию…", Toast.LENGTH_LONG).show()
        DiagnosticApi.upload(this, car, sessionName, complaint, uris) { result ->
            runOnUiThread {
                result.onSuccess { id ->
                    recordStore.markAiSent(sessionName, id)
                    startActivity(Intent(this, DiagnosticChatActivity::class.java).apply {
                        putExtra("diagnostic_id", id); putExtra("session_name", sessionName)
                    })
                }.onFailure { e -> Toast.makeText(this, "Не удалось отправить: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun reopenDiagnostic(record: DiagnosticRecordStore.Record) {
        startActivity(Intent(this, DiagnosticChatActivity::class.java).apply {
            putExtra("diagnostic_id", record.diagnosticId); putExtra("session_name", record.sessionName)
        })
    }

    private fun requestObd() {
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(permissions, permissionRequest)
        else { configureObd(); obd.scan() }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == permissionRequest && results.all { it == PackageManager.PERMISSION_GRANTED }) { configureObd(); obd.scan() }
    }

    private fun toggleRecording() {
        val car = carStore.selected() ?: run { showFirstCarDialog(); return }
        if (recorder == null) {
            val needed = if (Build.VERSION.SDK_INT >= 23) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.RECORD_AUDIO)
            if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestPermissions(needed, permissionRequest); return }
            recorder = PublicSessionRecorder(this, car) { text -> runOnUiThread { recordStatus.text = text } }
            recorder!!.start(); recordButton.text = "ОСТАНОВИТЬ ЗАПИСЬ"
        } else {
            val r = recorder!!; r.stop(); lastSessionUris = r.sessionUris(); recorder = null; recordButton.text = "НАЧАТЬ ЗАПИСЬ"; showPostRecordDialog(car, r.sessionName)
        }
    }

    private fun showPostRecordDialog(car: Car, sessionName: String) {
        val input = EditText(this).apply { hint = "Что вас беспокоит? (необязательно)"; minLines = 4; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("Диагностическая сессия завершена")
            .setMessage("${car.title()}\nСессия: $sessionName")
            .setView(input)
            .setNegativeButton("Закрыть") { _, _ -> recordStore.add(car.id, sessionName, input.text.toString().trim()) }
            .setPositiveButton("НАЧАТЬ ДИАГНОСТИКУ ИИ") { _, _ ->
                val complaint = input.text.toString().trim()
                recordStore.add(car.id, sessionName, complaint)
                uploadDiagnostic(car, sessionName, complaint, lastSessionUris)
            }.show()
    }

    override fun onDestroy() { recorder?.stop(); recorder = null; if (::obd.isInitialized) obd.close(); super.onDestroy() }
}
