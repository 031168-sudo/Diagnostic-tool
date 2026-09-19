package com.example.diagnostictool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.example.diagnostictool.ui.theme.DiagGray
import com.example.diagnostictool.ui.theme.DiagLightGray
import com.example.diagnostictool.ui.theme.DiagRed
import com.example.diagnostictool.ui.theme.DiagnosticTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val OBD_DISCONNECTED_TEXT = "OBD-адаптер не подключен"

class AlfaMainActivity : ComponentActivity() {
    private lateinit var obd: TargetElm327Ble
    private var recorder: PublicSessionRecorder? = null
    private val carStore by lazy { CarStore(this) }
    private val recordStore by lazy { DiagnosticRecordStore(this) }
    private var lastSessionUris: List<Uri> = emptyList()
    private val obdPermissionRequest = 10
    private val recordPermissionRequest = 11

    private var currentCar by mutableStateOf<Car?>(null)
    private var statusText by mutableStateOf(OBD_DISCONNECTED_TEXT)
    private var obdValuesText by mutableStateOf(ObdValues().toDisplay())
    private var errorCodes by mutableStateOf<List<String>>(emptyList())
    private var errorRaw by mutableStateOf("")
    private var recording by mutableStateOf(false)
    private var recordStatusText by mutableStateOf("Запись остановлена")

    private var showRecordWarning by mutableStateOf(false)
    private var recordWarningNoShow by mutableStateOf(false)
    private var showWelcome by mutableStateOf(false)
    private val settingsPrefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var firstCarDialog by mutableStateOf(false)
    private var carsDialog by mutableStateOf(false)
    private var carEditor by mutableStateOf<CarEditorState?>(null)
    private var obdDevices by mutableStateOf<List<TargetElm327Ble.DeviceInfo>?>(null)
    private var historyDialog by mutableStateOf(false)
    private var historyVersion by mutableStateOf(0)
    private var deleteRecord by mutableStateOf<DiagnosticRecordStore.Record?>(null)
    private var postRecordDialog by mutableStateOf<PostRecordState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        configureObd()
        setContent {
            DiagnosticTheme {
                Box(Modifier.fillMaxSize()) {
                    MainScreen(
                        carTitle = currentCar?.title() ?: "Автомобиль не выбран",
                        carEnabled = currentCar != null,
                        statusText = statusText,
                        obdValuesText = obdValuesText,
                        errorCodes = errorCodes,
                        errorRaw = errorRaw,
                        recording = recording,
                        recordStatusText = recordStatusText,
                        onChangeCar = { showCarsDialog() },
                        onAddCar = { showCarEditor(carStore.create(), true) },
                        onHistory = { showHistory() },
                        onConnect = { requestObd() },
                        onToggleRecord = { toggleRecording() }
                    )

                    carEditor?.let { state ->
                        CarEditorScreen(
                            state = state,
                            onVinLookup = { lookupVin(state) },
                            onSave = { saveCarEditor(state) },
                            onDismiss = { dismissCarEditor(state) }
                        )
                    }

                    if (showWelcome) WelcomeScreen(onDismiss = {
                        settingsPrefs.edit().putBoolean("welcome_shown", true).apply()
                        showWelcome = false
                        enterCarFlow()
                    })
                }

                if (firstCarDialog) FirstCarDialog(onAdd = { firstCarDialog = false; showCarEditor(carStore.create(), true) })

                if (carsDialog) CarsDialog(
                    cars = carStore.all(), selectedCar = currentCar,
                    onSelect = { car -> carStore.select(car.id); currentCar = carStore.selected(); carsDialog = false },
                    onEditSelected = { currentCar?.let { showCarEditor(it, false) }; carsDialog = false },
                    onAdd = { showCarEditor(carStore.create(), true); carsDialog = false },
                    onClose = { carsDialog = false }
                )

                obdDevices?.let { devices ->
                    ObdDevicesDialog(devices = devices, onSelect = { d -> obd.connect(d.device); obdDevices = null }, onClose = { obdDevices = null; statusText = OBD_DISCONNECTED_TEXT })
                }

                if (historyDialog) currentCar?.let { car ->
                    HistoryDialog(
                        carTitle = car.title(), records = remember(historyVersion) { recordStore.forCar(car.id) },
                        onSendAi = { record -> startHistoricalDiagnostic(car, record) },
                        onOpenDiagnostic = { record -> reopenDiagnostic(record) },
                        onOpenPdf = { record -> openPdf(record) },
                        onDelete = { record -> deleteRecord = record },
                        onClose = { historyDialog = false }
                    )
                }

                deleteRecord?.let { record ->
                    ConfirmDeleteDialog(
                        onConfirm = { deleteRecord = null; performDelete(record); historyVersion++ },
                        onCancel = { deleteRecord = null }
                    )
                }

                postRecordDialog?.let { state ->
                    PostRecordDialog(state = state, onClose = { closePostRecord(state) }, onStartAi = { startAiFromPostRecord(state) })
                }

                if (showRecordWarning) RecordWarningDialog(
                    noShow = recordWarningNoShow,
                    onNoShowChange = { recordWarningNoShow = it },
                    onCancel = {
                        showRecordWarning = false
                        if (recordWarningNoShow) settingsPrefs.edit().putBoolean("hide_record_warning", true).apply()
                    },
                    onStart = {
                        showRecordWarning = false
                        if (recordWarningNoShow) settingsPrefs.edit().putBoolean("hide_record_warning", true).apply()
                        beginRecordingFlow()
                    }
                )
            }
        }
        showWelcome = !settingsPrefs.getBoolean("welcome_shown", false)
        if (!showWelcome) enterCarFlow()
    }

    private fun enterCarFlow() {
        val cars = carStore.all()
        when (cars.size) {
            0 -> firstCarDialog = true
            1 -> { carStore.select(cars[0].id); currentCar = carStore.selected() }
            else -> carsDialog = true
        }
    }

    fun currentErrorCodes(): List<String> = errorCodes
    fun currentErrorRaw(): String = errorRaw

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
            override fun onErrors(codes: List<String>, raw: String) = runOnUiThread { errorCodes = codes; errorRaw = raw }
        })
        statusText = OBD_DISCONNECTED_TEXT
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
        contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf("$sessionName.adp"), null)?.use {
            if (it.moveToFirst()) result += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString())
        }
        return result
    }

    private fun startHistoricalDiagnostic(car: Car, record: DiagnosticRecordStore.Record) {
        val stored = record.sessionUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uris = if (stored != null) listOf(stored) else findSessionUris(record.sessionName)
        if (uris.isEmpty()) { Toast.makeText(this, "Файлы этой сессии не найдены", Toast.LENGTH_LONG).show(); return }
        uploadDiagnostic(car, record.sessionName, record.complaint, uris)
    }

    private fun performDelete(record: DiagnosticRecordStore.Record) {
        val stored = record.sessionUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        (stored?.let { listOf(it) } ?: findSessionUris(record.sessionName)).forEach { deleteUri(it) }
        record.aiResponseUri?.let { runCatching { deleteUri(Uri.parse(it)) } }
        recordStore.delete(record.sessionName)
    }

    private fun deleteUri(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
            else contentResolver.delete(uri, null, null)
        }
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
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(permissions, obdPermissionRequest)
        else { configureObd(); obd.scan() }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        when (request) {
            obdPermissionRequest -> if (results.all { it == PackageManager.PERMISSION_GRANTED }) { configureObd(); obd.scan() }
            recordPermissionRequest -> if (results.all { it == PackageManager.PERMISSION_GRANTED }) startRecording()
        }
    }

    private fun startRecording() {
        val car = currentCar ?: return
        recorder = PublicSessionRecorder(
            activity = this,
            car = car,
            status = { text -> runOnUiThread { recordStatusText = text } },
            onLimitReached = { r -> stopRecording(r, true) }
        )
        recorder!!.start(); recording = true
    }

    private fun toggleRecording() {
        if (currentCar == null) { firstCarDialog = true; return }
        if (recorder != null) { stopRecording(recorder!!, false); return }
        if (settingsPrefs.getBoolean("hide_record_warning", false)) beginRecordingFlow()
        else { recordWarningNoShow = false; showRecordWarning = true }
    }

    private fun beginRecordingFlow() {
        val needed = if (Build.VERSION.SDK_INT >= 23) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.RECORD_AUDIO)
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestPermissions(needed, recordPermissionRequest); return }
        startRecording()
    }

    private fun stopRecording(r: PublicSessionRecorder, limitReached: Boolean) {
        if (recorder !== r) return
        recorder = null
        recording = false
        Thread {
            r.stop()
            val uris = r.sessionUris()
            runOnUiThread {
                lastSessionUris = uris
                postRecordDialog = PostRecordState(r.car, r.sessionName, limitReached)
            }
        }.start()
    }

    private fun closePostRecord(state: PostRecordState) {
        recordStore.add(state.car.id, state.sessionName, state.complaint.trim())
        lastSessionUris.firstOrNull()?.let { recordStore.setSessionUri(state.sessionName, it.toString()) }
        postRecordDialog = null
    }

    private fun startAiFromPostRecord(state: PostRecordState) {
        val complaint = state.complaint.trim()
        recordStore.add(state.car.id, state.sessionName, complaint)
        lastSessionUris.firstOrNull()?.let { recordStore.setSessionUri(state.sessionName, it.toString()) }
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

private class PostRecordState(val car: Car, val sessionName: String, val limitReached: Boolean) {
    var complaint by mutableStateOf("")
}

@Composable
private fun MainScreen(
    carTitle: String,
    carEnabled: Boolean,
    statusText: String,
    obdValuesText: String,
    errorCodes: List<String>,
    errorRaw: String,
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
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(obdValuesText, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("Ошибки:", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    if (errorCodes.isEmpty()) {
                        Text("—", color = DiagLightGray, fontSize = 18.sp)
                    } else {
                        errorCodes.forEach { Text(it, color = DiagRed, fontSize = 18.sp) }
                    }
                }
            }
            Button(onClick = onToggleRecord, enabled = carEnabled, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) { Text(if (recording) "ОСТАНОВИТЬ ЗАПИСЬ" else "НАЧАТЬ ЗАПИСЬ") }
            Text(recordStatusText, color = DiagLightGray, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
            if (errorRaw.isNotBlank()) Text(errorRaw, color = DiagGray, fontSize = 9.sp, modifier = Modifier.padding(bottom = 24.dp))
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
private fun HistoryDialog(carTitle: String, records: List<DiagnosticRecordStore.Record>, onSendAi: (DiagnosticRecordStore.Record) -> Unit, onOpenDiagnostic: (DiagnosticRecordStore.Record) -> Unit, onOpenPdf: (DiagnosticRecordStore.Record) -> Unit, onDelete: (DiagnosticRecordStore.Record) -> Unit, onClose: () -> Unit) {
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (!record.aiSent) TextButton(onClick = { onSendAi(record) }) { Text("Отправить ИИ") }
                        if (record.aiSent && record.diagnosticId != null) TextButton(onClick = { onOpenDiagnostic(record) }) { Text("Открыть диагностику") }
                        if (record.aiResponseUri != null) TextButton(onClick = { onOpenPdf(record) }) { Text("Открыть PDF") }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDelete(record) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = DiagRed)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}

@Composable
private fun ConfirmDeleteDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Удалить запись?") },
        text = { Text("Действительно удалить запись? Запись и её файлы будут удалены с устройства без возможности восстановления.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Да") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Нет") } }
    )
}

@Composable
private fun PostRecordDialog(state: PostRecordState, onClose: () -> Unit, onStartAi: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Диагностическая сессия завершена") },
        text = {
            Column {
                if (state.limitReached) {
                    Text("Запись остановлена автоматически: достигнут лимит 5 минут.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(10.dp))
                }
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
private fun RecordWarningDialog(noShow: Boolean, onNoShowChange: (Boolean) -> Unit, onCancel: () -> Unit, onStart: () -> Unit) {
    val bullets = listOf(
        "Если у вас есть OBD-адаптер (ELM327) — подключите его: так диагностика будет точнее.",
        "Расположите телефон в салоне автомобиля и по возможности закрепите его, чтобы он не двигался и не падал.",
        "Во время записи не разговаривайте и не создавайте лишних звуков: применяется комплексный анализ данных с разных датчиков, включая звук с микрофона.",
        "Оставайтесь на этом экране и не переключайтесь в другие приложения — запись должна идти непрерывно.",
        "Пока идёт запись, экран не будет гаснуть.",
        "Максимальная длительность записи — 5 минут, после чего она остановится автоматически."
    )
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var scrolledToEnd by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState.maxValue) { if (scrollState.maxValue == 0) scrolledToEnd = true }
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 4) scrolledToEnd = true
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Перед началом записи") },
        text = {
            Column {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(scrollState)) {
                    bullets.forEach { Text("• $it", modifier = Modifier.padding(bottom = 6.dp)) }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onNoShowChange(!noShow) }) {
                        Checkbox(checked = noShow, onCheckedChange = onNoShowChange)
                        Text("Больше не показывать")
                    }
                }
                if (!scrolledToEnd) {
                    TextButton(onClick = { scope.launch { scrollState.animateScrollTo(scrollState.maxValue) } }, modifier = Modifier.fillMaxWidth()) {
                        Text("↓", fontSize = 28.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onStart, enabled = scrolledToEnd) { Text("Запись") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}

@Composable
private fun WelcomeScreen(onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    Surface(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scrollState).padding(end = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(20.dp))
                    Text("Добро пожаловать в", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Alfa Diagnostic", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = DiagRed, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    Text("Alfa Diagnostic помогает выявить неисправности автомобиля по данным о его работе и по посторонним звукам.", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    Text("Слышны скрип, скрежет, стук, шорох или гул — спереди или сзади, слева или справа? Приложение запишет звук и одновременно соберёт данные из разных источников:", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    listOf(
                        "микрофон — посторонние звуки в салоне и под капотом;",
                        "OBD-II (ELM327) — обороты, скорость, нагрузка и другие параметры;",
                        "GPS — скорость и перемещение автомобиля;",
                        "датчики телефона — ускорение и повороты."
                    ).forEach { Text("• $it", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) }
                    Spacer(Modifier.height(10.dp))
                    Text("Искусственный интеллект сопоставит всё по единой шкале времени и выдаст заключение: возможные причины, что проверить в первую очередь и как подтвердить диагноз.", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    Text("Выберите автомобиль, нажмите «Начать запись» — и следуйте подсказкам.", fontSize = 16.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
                    AndroidView(
                        factory = { ctx -> ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher); scaleType = ImageView.ScaleType.FIT_CENTER } },
                        modifier = Modifier.size(104.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                }
                VerticalScrollIndicator(scrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Понятно") }
        }
    }
}

@Composable
private fun CarEditorScreen(state: CarEditorState, onVinLookup: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()
    Surface(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.isNew) "Добавить автомобиль" else "Автомобиль", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.fillMaxWidth().verticalScroll(scrollState).padding(end = 14.dp)) {
                    OutlinedTextField(value = state.vin, onValueChange = { state.vin = it }, label = { Text("VIN (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onVinLookup, enabled = !state.vinBusy, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (state.vinBusy) "Определяю…" else "Определить по VIN")
                    }
                    Spacer(Modifier.height(8.dp))
                    RequiredField("Марка *", state.make, { state.make = it }, "make" in state.errorFields)
                    RequiredField("Модель *", state.model, { state.model = it }, "model" in state.errorFields)
                    RequiredField("Год выпуска *", state.year, { state.year = it }, "year" in state.errorFields)
                    RequiredField("Двигатель / объём *", state.engine, { state.engine = it }, "engine" in state.errorFields)
                    RequiredField("Топливо *", state.fuel, { state.fuel = it }, "fuel" in state.errorFields)
                    RequiredField("Коробка передач *", state.transmission, { state.transmission = it }, "transmission" in state.errorFields)
                    RequiredField("Привод *", state.drive, { state.drive = it }, "drive" in state.errorFields)
                    RequiredField("Пробег, км *", state.mileage, { state.mileage = it }, "mileage" in state.errorFields)
                    OutlinedTextField(value = state.notes, onValueChange = { state.notes = it }, label = { Text("Дополнительная информация (необязательно)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                VerticalScrollIndicator(scrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSave) { Text("Сохранить") }
            }
        }
    }
}

@Composable
private fun VerticalScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxHeight.toFloat()
        val max = scrollState.maxValue.toFloat()
        if (max > 0f && viewport > 0f) {
            val density = LocalDensity.current
            val thumbPx = (viewport * viewport / (viewport + max)).coerceAtLeast(24f)
            val offsetPx = (viewport - thumbPx) * (scrollState.value / max)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { offsetPx.toDp() })
                    .width(4.dp)
                    .height(with(density) { thumbPx.toDp() })
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
private fun RequiredField(label: String, value: String, onChange: (String) -> Unit, isError: Boolean) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = isError, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
}
