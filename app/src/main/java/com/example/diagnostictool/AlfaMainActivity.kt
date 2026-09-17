package com.example.diagnostictool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.diagnostictool.ui.theme.DiagGray
import com.example.diagnostictool.ui.theme.DiagLightGray
import com.example.diagnostictool.ui.theme.DiagnosticTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlfaMainActivity : ComponentActivity() {
    private lateinit var obd: TargetElm327Ble
    private var recorder: PublicSessionRecorder? = null
    private val carStore by lazy { CarStore(this) }
    private val recordStore by lazy { DiagnosticRecordStore(this) }
    private var lastSessionUris: List<Uri> = emptyList()
    private val permissionRequest = 10

    private var currentCar by mutableStateOf<Car?>(null)
    private var statusText by mutableStateOf("OBD-адаптер не подключён — запись возможна с GPS")
    private var obdValuesText by mutableStateOf(ObdValues().toDisplay())
    private var recording by mutableStateOf(false)
    private var recordStatusText by mutableStateOf("Запись остановлена")

    private var firstCarDialog by mutableStateOf(false)
    private var carsDialog by mutableStateOf(false)
    private var carEditor by mutableStateOf<CarEditorState?>(null)
    private var obdDevices by mutableStateOf<List<TargetElm327Ble.DeviceInfo>?>(null)
    private var historyDialog by mutableStateOf(false)
    private var postRecordDialog by mutableStateOf<PostRecordState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureObd()
        setContent {
            DiagnosticTheme {
                MainScreen(
                    carTitle = currentCar?.title() ?: "Автомобиль не выбран",
                    carEnabled = currentCar != null,
                    statusText = statusText,
                    obdValuesText = obdValuesText,
                    recording = recording,
                    recordStatusText = recordStatusText,
                    onChangeCar = { showCarsDialog() },
                    onAddCar = { showCarEditor(carStore.create(), true) },
                    onHistory = { showHistory() },
                    onConnect = { requestObd() },
                    onToggleRecord = { toggleRecording() }
                )

                if (firstCarDialog) FirstCarDialog(onAdd = { firstCarDialog = false; showCarEditor(carStore.create(), true) })

                if (carsDialog) CarsDialog(
                    cars = carStore.all(), selectedCar = currentCar,
                    onSelect = { car -> carStore.select(car.id); currentCar = carStore.selected(); carsDialog = false },
                    onEditSelected = { currentCar?.let { showCarEditor(it, false) }; carsDialog = false },
                    onAdd = { showCarEditor(carStore.create(), true); carsDialog = false },
                    onClose = { carsDialog = false }
                )

                carEditor?.let { state ->
                    CarEditorDialog(
                        state = state,
                        onVinLookup = { lookupVin(state) },
                        onSave = { saveCarEditor(state) },
                        onDismiss = { dismissCarEditor(state) }
                    )
                }

                obdDevices?.let { devices ->
                    ObdDevicesDialog(devices = devices, onSelect = { d -> obd.connect(d.device); obdDevices = null }, onClose = { obdDevices = null })
                }

                if (historyDialog) currentCar?.let { car ->
                    HistoryDialog(
                        carTitle = car.title(), records = recordStore.forCar(car.id),
                        onSendAi = { record -> startHistoricalDiagnostic(car, record) },
                        onOpenDiagnostic = { record -> reopenDiagnostic(record) },
                        onOpenPdf = { record -> openPdf(record) },
                        onClose = { historyDialog = false }
                    )
                }

                postRecordDialog?.let { state ->
                    PostRecordDialog(state = state, onClose = { closePostRecord(state) }, onStartAi = { startAiFromPostRecord(state) })
                }
            }
        }
        enterCarFlow()
    }

    private fun enterCarFlow() {
        val cars = carStore.all()
        when (cars.size) {
            0 -> firstCarDialog = true
            1 -> { carStore.select(cars[0].id); currentCar = carStore.selected() }
            else -> carsDialog = true
        }
    }

    fun hasLocationPermission(): Boolean = if (Build.VERSION.SDK_INT < 23) true else
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun configureObd() {
        if (::obd.isInitialized) obd.close()
        obd = TargetElm327Ble(this, object : TargetElm327Ble.Listener {
            override fun onState(text: String) {
                runOnUiThread {
                    statusText = text
                    if (text.contains("ELM327") && text.contains("готов")) recorder?.setObdActive(true)
                    if (text.contains("отключён", true)) recorder?.setObdActive(false)
                }
            }
            override fun onData(values: ObdValues, monotonicNs: Long) {
                runOnUiThread { obdValuesText = values.toDisplay() }
                recorder?.onObd(values, monotonicNs)
            }
            override fun onDevices(devices: List<TargetElm327Ble.DeviceInfo>) = runOnUiThread { obdDevices = devices }
        })
        statusText = "OBD-адаптер не подключён — запись возможна с GPS"
    }

    private fun showCarsDialog() {
        if (carStore.all().isEmpty()) { firstCarDialog = true; return }
        carsDialog = true
    }

    private fun showCarEditor(car: Car, returnToDiagnostic: Boolean) {
        carEditor = CarEditorState(car.id, returnToDiagnostic, car)
    }

    private fun dismissCarEditor(state: CarEditorState) {
        carEditor = null
        if (state.returnToDiagnostic && carStore.all().isEmpty()) firstCarDialog = true
    }

    private fun saveCarEditor(state: CarEditorState) {
        val required = linkedMapOf("make" to state.make, "model" to state.model, "year" to state.year, "engine" to state.engine, "fuel" to state.fuel, "transmission" to state.transmission, "drive" to state.drive, "mileage" to state.mileage)
        val missing = required.filterValues { it.isBlank() }.keys
        if (missing.isNotEmpty()) {
            state.errorFields = missing
            Toast.makeText(this, "Заполните: ${missing.joinToString(", ") { fieldLabel(it) }}", Toast.LENGTH_LONG).show()
            return
        }
        val car = Car(state.carId, state.make.trim(), state.model.trim(), state.year.trim(), state.engine.trim(), state.fuel.trim(), state.transmission.trim(), state.drive.trim(), state.vin.trim(), state.mileage.trim(), state.notes.trim())
        carStore.save(car); carStore.select(car.id); currentCar = carStore.selected(); carEditor = null
    }

    private fun fieldLabel(key: String) = when (key) {
        "make" -> "Марка"; "model" -> "Модель"; "year" -> "Год выпуска"; "engine" -> "Двигатель / объём"
        "fuel" -> "Топливо"; "transmission" -> "Коробка передач"; "drive" -> "Привод"; "mileage" -> "Пробег"; else -> key
    }

    private fun lookupVin(state: CarEditorState) {
        val vin = state.vin.trim().uppercase().replace(" ", "")
        if (vin.length != 17) { Toast.makeText(this, "VIN должен содержать 17 символов", Toast.LENGTH_LONG).show(); return }
        state.vinBusy = true
        DiagnosticApi.vin(vin) { result ->
            runOnUiThread {
                state.vinBusy = false
                result.onSuccess { info ->
                    if (info.make.isNotBlank()) state.make = info.make
                    if (info.model.isNotBlank()) state.model = info.model
                    if (info.year.isNotBlank()) state.year = info.year
                    if (info.engine.isNotBlank()) state.engine = info.engine
                    if (info.fuel.isNotBlank()) state.fuel = info.fuel
                    if (info.transmission.isNotBlank()) state.transmission = info.transmission
                    if (info.drive.isNotBlank()) state.drive = info.drive
                    val filled = listOf(info.make, info.model, info.year).count { it.isNotBlank() }
                    val text = when {
                        info.message.isNotBlank() -> info.message
                        filled > 0 -> "Данные заполнены по VIN"
                        else -> "По VIN найдены только базовые данные"
                    }
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                }.onFailure { e -> Toast.makeText(this, "Не удалось определить: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showHistory() {
        if (currentCar == null) { firstCarDialog = true; return }
        historyDialog = true
    }

    private fun openPdf(record: DiagnosticRecordStore.Record) {
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(record.aiResponseUri), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
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
        val car = currentCar ?: run { firstCarDialog = true; return }
        if (recorder == null) {
            val needed = if (Build.VERSION.SDK_INT >= 23) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.RECORD_AUDIO)
            if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestPermissions(needed, permissionRequest); return }
            recorder = PublicSessionRecorder(this, car) { text -> runOnUiThread { recordStatusText = text } }
            recorder!!.start(); recording = true
        } else {
            val r = recorder!!; r.stop(); lastSessionUris = r.sessionUris(); recorder = null; recording = false
            postRecordDialog = PostRecordState(car, r.sessionName)
        }
    }

    private fun closePostRecord(state: PostRecordState) {
        recordStore.add(state.car.id, state.sessionName, state.complaint.trim())
        postRecordDialog = null
    }

    private fun startAiFromPostRecord(state: PostRecordState) {
        val complaint = state.complaint.trim()
        recordStore.add(state.car.id, state.sessionName, complaint)
        postRecordDialog = null
        uploadDiagnostic(state.car, state.sessionName, complaint, lastSessionUris)
    }

    override fun onDestroy() { recorder?.stop(); recorder = null; if (::obd.isInitialized) obd.close(); super.onDestroy() }
}

private class CarEditorState(val carId: String, val returnToDiagnostic: Boolean, car: Car) {
    val isNew = car.make.isBlank() && car.model.isBlank()
    var make by mutableStateOf(car.make)
    var model by mutableStateOf(car.model)
    var year by mutableStateOf(car.year)
    var engine by mutableStateOf(car.engine)
    var fuel by mutableStateOf(car.fuel)
    var transmission by mutableStateOf(car.transmission)
    var drive by mutableStateOf(car.drive)
    var mileage by mutableStateOf(car.mileage)
    var vin by mutableStateOf(car.vin)
    var notes by mutableStateOf(car.notes)
    var vinBusy by mutableStateOf(false)
    var errorFields by mutableStateOf<Set<String>>(emptySet())
}

private class PostRecordState(val car: Car, val sessionName: String) {
    var complaint by mutableStateOf("")
}

@Composable
private fun MainScreen(
    carTitle: String,
    carEnabled: Boolean,
    statusText: String,
    obdValuesText: String,
    recording: Boolean,
    recordStatusText: String,
    onChangeCar: () -> Unit,
    onAddCar: () -> Unit,
    onHistory: () -> Unit,
    onConnect: () -> Unit,
    onToggleRecord: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("Alfa Diagnostic", color = MaterialTheme.colorScheme.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp))
            Text(carTitle, color = MaterialTheme.colorScheme.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Button(onClick = onChangeCar, modifier = Modifier.weight(1f)) { Text("Сменить") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAddCar, modifier = Modifier.weight(1f)) { Text("+ Добавить") }
            }
            Button(onClick = onHistory, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = DiagGray)) { Text("История диагностики") }
            HorizontalDivider(Modifier.padding(top = 14.dp), color = DiagGray)
            Text("Сбор информации", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Text(statusText, color = DiagLightGray, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
            Button(onClick = onConnect, enabled = carEnabled, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Подключить ELM327") }
            Text(obdValuesText, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, modifier = Modifier.padding(top = 12.dp))
            Button(onClick = onToggleRecord, enabled = carEnabled, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text(if (recording) "ОСТАНОВИТЬ ЗАПИСЬ" else "НАЧАТЬ ЗАПИСЬ") }
            Text(recordStatusText, color = DiagLightGray, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        }
    }
}

@Composable
private fun FirstCarDialog(onAdd: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text("Добавьте автомобиль") },
        text = { Text("Чтобы начать сбор информации, сначала добавьте автомобиль.") },
        confirmButton = { TextButton(onClick = onAdd) { Text("Добавить автомобиль") } }
    )
}

@Composable
private fun CarsDialog(cars: List<Car>, selectedCar: Car?, onSelect: (Car) -> Unit, onEditSelected: () -> Unit, onAdd: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Выберите автомобиль") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                cars.forEach { car ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(car) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = car.id == selectedCar?.id, onClick = { onSelect(car) })
                        Spacer(Modifier.width(8.dp))
                        Text(car.title())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Добавить") } },
        dismissButton = {
            Row {
                TextButton(onClick = onEditSelected, enabled = selectedCar != null) { Text("Изменить") }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
    )
}

@Composable
private fun ObdDevicesDialog(devices: List<TargetElm327Ble.DeviceInfo>, onSelect: (TargetElm327Ble.DeviceInfo) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Выберите OBD-адаптер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                devices.forEach { d ->
                    Text(d.label(), modifier = Modifier.fillMaxWidth().clickable { onSelect(d) }.padding(vertical = 12.dp))
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
        confirmButton = {}
    )
}

@Composable
private fun HistoryDialog(carTitle: String, records: List<DiagnosticRecordStore.Record>, onSendAi: (DiagnosticRecordStore.Record) -> Unit, onOpenDiagnostic: (DiagnosticRecordStore.Record) -> Unit, onOpenPdf: (DiagnosticRecordStore.Record) -> Unit, onClose: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("История диагностики — $carTitle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (records.isEmpty()) Text("Диагностик пока нет.")
                records.forEach { record ->
                    Text("${dateFormat.format(Date(record.createdAt))}\n${record.sessionName}\n${record.complaint.ifBlank { "Жалоба не указана" }}")
                    Text(
                        when { record.aiResponseUri != null -> "ИИ: заключение получено"; record.aiSent -> "ИИ: ответ ожидается"; else -> "ИИ: не отправлено" },
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                    Row {
                        if (!record.aiSent) TextButton(onClick = { onSendAi(record) }) { Text("Отправить ИИ") }
                        if (record.aiSent && record.diagnosticId != null) TextButton(onClick = { onOpenDiagnostic(record) }) { Text("Открыть диагностику") }
                        if (record.aiResponseUri != null) TextButton(onClick = { onOpenPdf(record) }) { Text("Открыть PDF") }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}

@Composable
private fun PostRecordDialog(state: PostRecordState, onClose: () -> Unit, onStartAi: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Диагностическая сессия завершена") },
        text = {
            Column {
                Text("${state.car.title()}\nСессия: ${state.sessionName}")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = state.complaint, onValueChange = { state.complaint = it }, label = { Text("Что вас беспокоит? (необязательно)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onStartAi) { Text("НАЧАТЬ ДИАГНОСТИКУ ИИ") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}

@Composable
private fun CarEditorDialog(state: CarEditorState, onVinLookup: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.isNew) "Добавить автомобиль" else "Автомобиль") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RequiredField("Марка *", state.make, { state.make = it }, "make" in state.errorFields)
                RequiredField("Модель *", state.model, { state.model = it }, "model" in state.errorFields)
                RequiredField("Год выпуска *", state.year, { state.year = it }, "year" in state.errorFields)
                RequiredField("Двигатель / объём *", state.engine, { state.engine = it }, "engine" in state.errorFields)
                RequiredField("Топливо *", state.fuel, { state.fuel = it }, "fuel" in state.errorFields)
                RequiredField("Коробка передач *", state.transmission, { state.transmission = it }, "transmission" in state.errorFields)
                RequiredField("Привод *", state.drive, { state.drive = it }, "drive" in state.errorFields)
                RequiredField("Пробег, км *", state.mileage, { state.mileage = it }, "mileage" in state.errorFields)
                OutlinedTextField(value = state.vin, onValueChange = { state.vin = it }, label = { Text("VIN (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = onVinLookup, enabled = !state.vinBusy, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (state.vinBusy) "Определяю…" else "Определить по VIN")
                }
                OutlinedTextField(value = state.notes, onValueChange = { state.notes = it }, label = { Text("Дополнительная информация (необязательно)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun RequiredField(label: String, value: String, onChange: (String) -> Unit, isError: Boolean) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = isError, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
}
