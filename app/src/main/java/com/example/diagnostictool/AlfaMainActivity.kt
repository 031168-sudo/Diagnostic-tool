package com.example.diagnostictool

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.diagnostictool.ui.theme.DiagButtonGray
import com.example.diagnostictool.ui.theme.DiagGray
import com.example.diagnostictool.ui.theme.DiagGreen
import com.example.diagnostictool.ui.theme.DiagLightGray
import com.example.diagnostictool.ui.theme.DiagRed
import com.example.diagnostictool.ui.theme.DiagWhite
import com.example.diagnostictool.ui.theme.DiagnosticTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val OBD_DISCONNECTED_TEXT = "OBD-адаптер не подключен"
private const val AUTO_BACKUP_INTERVAL_MS = 10 * 60 * 1000L

private fun formatDuration(totalSec: Int): String = "%d:%02d".format(totalSec / 60, totalSec % 60)

private fun formatDateTime(millis: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(millis))

class AlfaMainActivity : ComponentActivity() {
    private lateinit var ble: TargetElm327Ble
    private val spp by lazy { SppObd(this, obdListener) }
    private val wifi by lazy { WifiObd(this, obdListener) }
    private var activeKind by mutableStateOf("")
    private var pendingName = ""
    private var pendingAddr = ""
    private var savedAdapter by mutableStateOf("")
    private var obdBusy by mutableStateOf(false)
    private var obdBusyText by mutableStateOf("")
    private val obdBusyHandler = Handler(Looper.getMainLooper())
    private val obdBusyTimeout = Runnable {
        obdBusy = false
        statusText = "Не удалось подключиться"
        Toast.makeText(this@AlfaMainActivity, "Не удалось подключиться", Toast.LENGTH_LONG).show()
    }

    private fun startObdBusy(text: String, timeoutMs: Long) {
        obdBusyText = text
        obdBusy = true
        obdBusyHandler.removeCallbacks(obdBusyTimeout)
        obdBusyHandler.postDelayed(obdBusyTimeout, timeoutMs)
    }

    private fun stopObdBusy() {
        obdBusy = false
        obdBusyHandler.removeCallbacks(obdBusyTimeout)
    }
    private val obdListener = object : ObdListener {
        override fun onState(text: String) {
            runOnUiThread {
                statusText = text
                appendLog("• $text")
                if (text.contains("не найдены") || text.contains("выключен") || text.contains("Ошибка BLE")) scanCount = null
                if (text.contains("ELM327") && text.contains("готов")) recorder?.setObdActive(true)
                if (text.contains("отключён", true)) recorder?.setObdActive(false)
            }
        }
        override fun onData(values: ObdValues, monotonicNs: Long) {
            runOnUiThread { obdValues = values.copy(); obdValuesText = values.toDisplay() }
            recorder?.onObd(values, monotonicNs)
        }
        override fun onDevices(devices: List<ObdDevice>) = runOnUiThread { stopObdBusy(); scanCount = null; obdListHint = ""; obdDevices = devices }
        override fun onErrors(codes: List<String>, raw: String) = runOnUiThread { errorCodes = codes; errorRaw = raw }
        override fun onLog(line: String) = runOnUiThread { appendLog(line) }
        override fun onScanning(found: Int) = runOnUiThread { scanCount = found }
        override fun onConnected(connected: Boolean) = runOnUiThread {
            val wasBusy = obdBusy
            stopObdBusy()
            obdConnected = connected
            if (connected) {
                scanCount = null; showObdLog = true
                Toast.makeText(this@AlfaMainActivity, "Подключено: ${pendingName.ifBlank { "OBD" }}", Toast.LENGTH_SHORT).show()
                if (activeKind.isNotBlank() && pendingAddr.isNotBlank()) {
                    settingsPrefs.edit()
                        .putString("last_kind", activeKind)
                        .putString("last_name", pendingName)
                        .putString("last_addr", pendingAddr)
                        .apply()
                    refreshSavedAdapter()
                }
            } else if (wasBusy) {
                statusText = "Не удалось подключиться"
                Toast.makeText(this@AlfaMainActivity, "Не удалось подключиться", Toast.LENGTH_LONG).show()
            }
        }
    }
    private var recorder: PublicSessionRecorder? = null
    private val carStore by lazy { CarStore(this) }
    private val recordStore by lazy { DiagnosticRecordStore(this) }
    private val serviceStore by lazy { ServiceStore(this) }
    private val accountStore by lazy { AccountStore(this) }
    private var lastSessionUris: List<Uri> = emptyList()
    private val obdPermissionRequest = 10
    private val recordPermissionRequest = 11

    private val backupOpen = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readBackup(it) }
    }

    private var currentCar by mutableStateOf<Car?>(null)
    private var statusText by mutableStateOf(OBD_DISCONNECTED_TEXT)
    private var obdValues by mutableStateOf(ObdValues())
    private var obdValuesText by mutableStateOf(ObdValues().toDisplay())
    private var obdConnected by mutableStateOf(false)
    private var scanCount by mutableStateOf<Int?>(null)
    private var showObdLog by mutableStateOf(false)
    private var dashboardOpen by mutableStateOf(false)
    private var dashboardPro by mutableStateOf(false)
    private var dashboardLandscape by mutableStateOf(false)
    private var tripKm by mutableStateOf(0.0)
    private var tripSec by mutableStateOf(0)
    private var lastTripTick = 0L
    private val tripHandler = Handler(Looper.getMainLooper())
    private val tripTick = object : Runnable {
        override fun run() {
            if (!dashboardOpen) return
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastTripTick == 0L) 0.0 else (now - lastTripTick) / 1000.0
            lastTripTick = now
            tripSec += 1
            obdValues.speed?.let { tripKm += it / 3600.0 * dt }
            tripHandler.postDelayed(this, 1000)
        }
    }
    private var errorCodes by mutableStateOf<List<String>>(emptyList())
    private var errorRaw by mutableStateOf("")
    private val obdLog = mutableStateListOf<String>()
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val liveLog by lazy { LiveLog(this) }
    private var recording by mutableStateOf(false)
    private var recordElapsedSec by mutableStateOf(0)
    private var recordStartElapsed = 0L
    private val recordTimerHandler = Handler(Looper.getMainLooper())
    private val recordTick = object : Runnable {
        override fun run() {
            if (recording) {
                recordElapsedSec = ((SystemClock.elapsedRealtime() - recordStartElapsed) / 1000).toInt()
                recordTimerHandler.postDelayed(this, 500)
            }
        }
    }
    private var recordStatusText by mutableStateOf("Запись остановлена")

    private var showRecordWarning by mutableStateOf(false)
    private var recordWarningNoShow by mutableStateOf(false)
    private var showWelcome by mutableStateOf(false)
    private var helpOpen by mutableStateOf(false)
    private var serviceBookOpen by mutableStateOf(false)
    private var serviceEditor by mutableStateOf<ServiceEntry?>(null)
    private var serviceVersion by mutableStateOf(0)
    private val settingsPrefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var firstCarDialog by mutableStateOf(false)
    private var carsDialog by mutableStateOf(false)
    private var carEditor by mutableStateOf<CarEditorState?>(null)
    private var obdDevices by mutableStateOf<List<ObdDevice>?>(null)
    private var obdListHint by mutableStateOf("")
    private var connectChooser by mutableStateOf(false)
    private var wifiDialog by mutableStateOf(false)
    private var historyDialog by mutableStateOf(false)
    private var historyVersion by mutableStateOf(0)
    private var deleteRecord by mutableStateOf<DiagnosticRecordStore.Record?>(null)
    private var deleteCar by mutableStateOf<Car?>(null)
    private var postRecordDialog by mutableStateOf<PostRecordState?>(null)
    private var historicalComplaint by mutableStateOf<HistoricalComplaintState?>(null)
    private var uploadState by mutableStateOf<UploadState?>(null)

    private var accountDialog by mutableStateOf(false)
    private var accountCredits by mutableStateOf<Int?>(null)
    private var accountSpentMicros by mutableStateOf<Long?>(null)
    private var accountSubscription by mutableStateOf("")
    private var accountTransferCode by mutableStateOf<String?>(null)
    private var accountMessage by mutableStateOf<String?>(null)
    private var accountBusy by mutableStateOf(false)
    private var backupMessage by mutableStateOf<String?>(null)
    private var restoreConfirm by mutableStateOf(false)
    private var lastBackupName by mutableStateOf<String?>(null)
    private var lastBackupTime by mutableStateOf(0L)
    private var lastRestoreTime by mutableStateOf(0L)
    private val backupRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val autoBackupHandler = Handler(Looper.getMainLooper())
    private val autoBackupRunnable = object : Runnable {
        override fun run() {
            createBackup(true)
            autoBackupHandler.postDelayed(this, AUTO_BACKUP_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        configureObd()
        refreshSavedAdapter()
        appendLog("Файл журнала: ${liveLog.displayPath()}")
        setContent {
            DiagnosticTheme {
                Box(Modifier.fillMaxSize()) {
                    MainScreen(
                        carTitle = currentCar?.title() ?: "Автомобиль не выбран",
                        carEnabled = currentCar != null,
                        obdConnected = obdConnected,
                        obdValuesText = obdValuesText,
                        recording = recording,
                        recordElapsedSec = recordElapsedSec,
                        recordStatusText = recordStatusText,
                        onChangeCar = { showCarsDialog() },
                        onAddCar = { showCarEditor(carStore.create(), true) },
                        onHistory = { showHistory() },
                        onHelp = { helpOpen = true },
                        onServiceBook = { serviceBookOpen = true },
                        onAccount = { accountDialog = true; refreshAccount() },
                        savedLabel = savedAdapter,
                        obdBusy = obdBusy,
                        obdBusyText = obdBusyText,
                        onDisconnect = { disconnectObd() },
                        onConnectSaved = { connectSaved() },
                        onChooseNew = { resetObdData(); connectChooser = true },
                        onDashboard = { openDashboard() },
                        onToggleRecord = { toggleRecording() },
                        obdLog = obdLog,
                        showObdLog = showObdLog,
                        onClearLog = { obdLog.clear() },
                        onShareLog = { shareLiveLog() }
                    )

                    carEditor?.let { state ->
                        CarEditorScreen(
                            state = state,
                            onVinLookup = { lookupVin(state) },
                            onSave = { saveCarEditor(state) },
                            onDismiss = { dismissCarEditor(state) }
                        )
                    }

                    if (showWelcome) WelcomeScreen(
                        onDismiss = {
                            settingsPrefs.edit().putBoolean("welcome_shown", true).apply()
                            showWelcome = false
                            enterCarFlow()
                        },
                        onHelp = { helpOpen = true }
                    )

                    if (helpOpen) HelpScreen(onClose = {
                        helpOpen = false
                        if (showWelcome) {
                            settingsPrefs.edit().putBoolean("welcome_shown", true).apply()
                            showWelcome = false
                            enterCarFlow()
                        }
                    })

                    if (serviceBookOpen) currentCar?.let { car ->
                        val entries = remember(serviceVersion, car.id) { serviceStore.forCar(car.id) }
                        ServiceBookScreen(
                            car = car,
                            entries = entries,
                            hints = remember(serviceVersion, car.id) { serviceStore.hints(car, entries) },
                            onAdd = { serviceEditor = serviceStore.create(car.id) },
                            onEdit = { e -> serviceEditor = e },
                            onDelete = { e -> serviceStore.delete(e.id); serviceVersion++ },
                            onBack = { serviceBookOpen = false }
                        )
                    }

                    serviceEditor?.let { entry ->
                        ServiceEntryDialog(
                            entry = entry,
                            onSave = { serviceStore.save(it); serviceVersion++; serviceEditor = null },
                            onDismiss = { serviceEditor = null }
                        )
                    }

                    if (dashboardOpen) {
                        if (dashboardPro) {
                            ProDashboardScreen(
                                values = obdValues,
                                connected = obdConnected,
                                errorCodes = errorCodes,
                                landscape = dashboardLandscape,
                                onBack = { dashboardPro = false; ElmSession.extendedPids = false },
                                onClose = { closeDashboard() }
                            )
                        } else {
                            DashboardScreen(
                                values = obdValues,
                                connected = obdConnected,
                                landscape = dashboardLandscape,
                                tripKm = tripKm,
                                tripSec = tripSec,
                                onToggleOrientation = { toggleDashboardOrientation() },
                                onResetTrip = { tripKm = 0.0; tripSec = 0; lastTripTick = 0L },
                                onOpenPro = { dashboardPro = true; ElmSession.extendedPids = true },
                                onClose = { closeDashboard() }
                            )
                        }
                    }
                }

                if (firstCarDialog) FirstCarDialog(
                    onAdd = { firstCarDialog = false; showCarEditor(carStore.create(), true) },
                    onRestore = { restoreConfirm = true }
                )

                if (carsDialog) CarsDialog(
                    cars = carStore.all(), selectedCar = currentCar,
                    onSelect = { car -> carStore.select(car.id); currentCar = carStore.selected(); carsDialog = false },
                    onEdit = { car -> showCarEditor(car, false); carsDialog = false },
                    onDelete = { car -> deleteCar = car },
                    onAdd = { showCarEditor(carStore.create(), true); carsDialog = false },
                    onClose = { carsDialog = false }
                )

                deleteCar?.let { car ->
                    DeleteCarDialog(
                        car = car,
                        onConfirm = { deleteCarCompletely(car); deleteCar = null },
                        onCancel = { deleteCar = null }
                    )
                }

                obdDevices?.let { devices ->
                    ObdDevicesDialog(devices = devices, hint = obdListHint, onSelect = { d ->
                        settingsPrefs.edit().putString("last_obd", d.address).apply()
                        val bt = d.btDevice
                        if (bt != null) {
                            resetObdData()
                            pendingName = d.title(); pendingAddr = d.address
                            startObdBusy("Подключение…", 20000)
                            if (d.kind == ObdDevice.Kind.BLE) { activeKind = "ble"; ble.connect(bt) }
                            else { activeKind = "spp"; spp.connect(bt) }
                        }
                        obdDevices = null
                    }, onClose = { obdDevices = null; statusText = OBD_DISCONNECTED_TEXT })
                }

                if (connectChooser) ConnectChooserDialog(
                    onBle = { connectChooser = false; requestObd() },
                    onSpp = {
                        connectChooser = false
                        obdListHint = "Если вашего адаптера нет в списке — сначала сопрягите его в настройках Bluetooth телефона, затем вернитесь сюда."
                        obdDevices = spp.bondedDevices()
                    },
                    onWifi = { connectChooser = false; wifiDialog = true },
                    onCancel = { connectChooser = false }
                )

                if (wifiDialog) WifiDialog(
                    initial = settingsPrefs.getString("last_wifi", "192.168.0.10") ?: "192.168.0.10",
                    onConnect = { input ->
                        val host = input.substringBefore(":").trim()
                        val port = input.substringAfter(":", "").trim().toIntOrNull() ?: 35000
                        settingsPrefs.edit().putString("last_wifi", input).apply()
                        wifiDialog = false
                        activeKind = "wifi"
                        resetObdData()
                        pendingName = input; pendingAddr = input
                        startObdBusy("Подключение…", 20000)
                        wifi.connect(host, port)
                    },
                    onCancel = { wifiDialog = false }
                )

                if (accountDialog) AccountDialog(
                    accountId = accountStore.accountId,
                    credits = accountCredits,
                    spentMicros = accountSpentMicros,
                    subscriptionUntil = accountSubscription,
                    transferCode = accountTransferCode,
                    busy = accountBusy,
                    message = accountMessage,
                    backupMessage = backupMessage,
                    lastBackupName = lastBackupName,
                    lastBackupTime = lastBackupTime,
                    lastRestoreTime = lastRestoreTime,
                    backupLocation = backupLocationLabel(),
                    onCreateCode = { createTransferCode() },
                    onRedeem = { redeemTransferCode(it) },
                    onBackup = { backupMessage = null; createBackup(false) },
                    onRestore = { restoreConfirm = true },
                    onClose = { accountDialog = false; accountTransferCode = null; accountMessage = null; backupMessage = null }
                )

                if (restoreConfirm) ConfirmRestoreDialog(
                    onConfirm = {
                        restoreConfirm = false
                        backupOpen.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    onCancel = { restoreConfirm = false }
                )

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

                historicalComplaint?.let { state ->
                    HistoricalComplaintDialog(
                        state = state,
                        onSend = {
                            val c = state.complaint.trim()
                            recordStore.setComplaint(state.record.sessionName, c)
                            historicalComplaint = null
                            doHistoricalUpload(state.car, state.record, c)
                        },
                        onCancel = { historicalComplaint = null }
                    )
                }

                uploadState?.let { state ->
                    UploadProgressDialog(
                        state = state,
                        onRetry = { performUpload(state) },
                        onClose = { uploadState = null }
                    )
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
        if (!accountStore.isRegistered) registerAccount() else refreshAccount()
        loadBackupInfo()
        autoBackupHandler.postDelayed(autoBackupRunnable, 4000)
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

    fun currentJournalJson(): String {
        val car = currentCar ?: return "[]"
        val arr = JSONArray()
        serviceStore.forCar(car.id).forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun appendLog(line: String) {
        val entry = "[${logTimeFormat.format(Date())}] $line"
        obdLog.add(entry)
        while (obdLog.size > 400) obdLog.removeAt(0)
        liveLog.append(entry)
    }

    private fun shareLiveLog() {
        val src = liveLog.file
        if (!src.exists()) { Toast.makeText(this, "Файл журнала ещё не создан", Toast.LENGTH_LONG).show(); return }
        val uri = copyLogToDownloads(src)
        if (uri == null) { Toast.makeText(this, "Не удалось подготовить файл журнала", Toast.LENGTH_LONG).show(); return }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Отправить журнал"))
    }

    private fun copyLogToDownloads(src: File): Uri? = runCatching {
        val name = "obd_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DiagnosticTool/logs")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")
            contentResolver.openOutputStream(uri)?.use { it.write(src.readBytes()) }
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiagnosticTool/logs").apply { mkdirs() }
            val dst = File(dir, name)
            src.copyTo(dst, overwrite = true)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", dst)
        }
    }.getOrNull()

    fun hasLocationPermission(): Boolean = if (Build.VERSION.SDK_INT < 23) true else
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun configureObd() {
        if (::ble.isInitialized) ble.close()
        ble = TargetElm327Ble(this, obdListener)
        statusText = OBD_DISCONNECTED_TEXT
    }

    private fun disconnectObd() {
        when (activeKind) {
            "spp" -> spp.disconnect()
            "wifi" -> wifi.disconnect()
            else -> ble.disconnect()
        }
        activeKind = ""
    }

    private fun resetObdData() {
        obdValuesText = ObdValues().toDisplay()
        errorCodes = emptyList()
        errorRaw = ""
    }

    private fun refreshSavedAdapter() {
        val kind = settingsPrefs.getString("last_kind", "") ?: ""
        savedAdapter = if (kind.isBlank()) "" else
            (settingsPrefs.getString("last_name", "") ?: "").ifBlank { settingsPrefs.getString("last_addr", "") ?: "" }
    }

    private fun connectSaved() {
        val kind = settingsPrefs.getString("last_kind", "") ?: ""
        val name = settingsPrefs.getString("last_name", "") ?: ""
        val addr = settingsPrefs.getString("last_addr", "") ?: ""
        if (kind.isBlank() || addr.isBlank()) { connectChooser = true; return }
        resetObdData()
        pendingName = name.ifBlank { addr }; pendingAddr = addr
        startObdBusy("Подключение…", 20000)
        when (kind) {
            "spp" -> { activeKind = "spp"; spp.connectAddress(addr) }
            "wifi" -> {
                activeKind = "wifi"
                val host = addr.substringBefore(":").trim()
                val port = addr.substringAfter(":", "").trim().toIntOrNull() ?: 35000
                wifi.connect(host, port)
            }
            else -> { activeKind = "ble"; configureObd(); ble.connectAddress(addr) }
        }
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

    private fun registerAccount() {
        accountMessage = "Создаю аккаунт…"
        DiagnosticApi.accountRegister { result ->
            runOnUiThread {
                result.onSuccess { acc ->
                    accountStore.save(acc.accountId, acc.token)
                    accountCredits = acc.credits
                    accountSpentMicros = acc.spentMicros
                    accountSubscription = acc.subscriptionUntil
                    accountMessage = null
                }.onFailure { e -> accountMessage = "Не удалось создать аккаунт: ${e.message}" }
            }
        }
    }

    private fun refreshAccount() {
        val token = accountStore.token
        if (token.isNullOrBlank()) { registerAccount(); return }
        accountBusy = true
        DiagnosticApi.accountMe(token) { result ->
            runOnUiThread {
                accountBusy = false
                result.onSuccess { acc ->
                    accountCredits = acc.credits
                    accountSpentMicros = acc.spentMicros
                    accountSubscription = acc.subscriptionUntil
                }.onFailure { e -> accountMessage = "Не удалось получить данные аккаунта: ${e.message}" }
            }
        }
    }

    private fun createTransferCode() {
        val token = accountStore.token ?: return
        accountBusy = true
        accountMessage = null
        DiagnosticApi.accountTransferCreate(token) { result ->
            runOnUiThread {
                accountBusy = false
                result.onSuccess { accountTransferCode = it.code }
                    .onFailure { e -> accountMessage = "Не удалось создать код: ${e.message}" }
            }
        }
    }

    private fun redeemTransferCode(code: String) {
        if (code.isBlank()) return
        accountBusy = true
        accountMessage = null
        DiagnosticApi.accountTransferRedeem(code) { result ->
            runOnUiThread {
                accountBusy = false
                result.onSuccess { acc ->
                    accountStore.save(acc.accountId, acc.token)
                    accountCredits = acc.credits
                    accountSpentMicros = acc.spentMicros
                    accountSubscription = acc.subscriptionUntil
                    accountTransferCode = null
                    accountMessage = "Аккаунт привязан"
                }.onFailure { e -> accountMessage = "Код не принят: ${e.message}" }
            }
        }
    }

    private fun buildBackupJson(): String {
        val carsPrefs = getSharedPreferences("cars", MODE_PRIVATE)
        val root = JSONObject().apply {
            put("app", "AlfaDiag")
            put("version", 2)
            put("createdAt", System.currentTimeMillis())
            put("cars", JSONArray(carsPrefs.getString("items", "[]")))
            put("selectedCarId", carsPrefs.getString("selected_id", "") ?: "")
            put("records", JSONArray(recordStore.rawJson()))
            put("service", JSONArray(getSharedPreferences("service_book", MODE_PRIVATE).getString("items", "[]")))
            put("settings", JSONObject().apply {
                val sp = getSharedPreferences("settings", MODE_PRIVATE)
                listOf("hide_record_warning", "welcome_shown", "last_obd", "last_kind", "last_name", "last_addr", "last_wifi").forEach { k ->
                    when (val v = sp.all[k]) {
                        is Boolean -> put(k, v)
                        is String -> put(k, v)
                        is Int -> put(k, v)
                    }
                }
            })
        }
        return root.toString(2)
    }

    private fun mediaFiles(relativePath: String, likeName: String): List<Pair<String, Uri>> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val out = mutableListOf<Pair<String, Uri>>()
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection,
            arrayOf("$relativePath%", likeName), null
        )?.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                out += name to Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString())
            }
        }
        return out
    }

    private fun backupSignature(sessions: Int, pdfs: Int): String {
        val cars = getSharedPreferences("cars", MODE_PRIVATE).getString("items", "[]")
        val service = getSharedPreferences("service_book", MODE_PRIVATE).getString("items", "[]")
        return ("$cars|${recordStore.rawJson()}|$service|$sessions|$pdfs").hashCode().toString()
    }

    private fun writeZip(os: java.io.OutputStream, json: String, sessions: List<Pair<String, Uri>>, pdfs: List<Pair<String, Uri>>) {
        ZipOutputStream(BufferedOutputStream(os)).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            sessions.forEach { (name, u) ->
                zip.putNextEntry(ZipEntry("sessions/$name"))
                contentResolver.openInputStream(u)?.use { it.copyTo(zip) }
                zip.closeEntry()
            }
            pdfs.forEach { (name, u) ->
                zip.putNextEntry(ZipEntry("conclusions/$name"))
                contentResolver.openInputStream(u)?.use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun createBackup(auto: Boolean) {
        if (!backupRunning.compareAndSet(false, true)) return
        if (!auto) backupMessage = "Готовлю копию…"
        Thread {
            try {
                val json = buildBackupJson()
                val sessions = mediaFiles("Download/Alfa Diagnostic/sessions", "%.adp")
                val pdfs = mediaFiles("Download/Alfa Diagnostic/conclusions", "Заключение_%.pdf")
                val signature = backupSignature(sessions.size, pdfs.size)
                if (auto && signature == getSharedPreferences("backup", MODE_PRIVATE).getString("last_signature", null)) {
                    backupRunning.set(false)
                    return@Thread
                }
                val name = "alfadiag-backup-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip"
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Alfa Diagnostic/backups")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("не удалось создать файл копии")
                    contentResolver.openOutputStream(uri)?.use { os -> writeZip(os, json, sessions, pdfs) }
                        ?: error("не удалось записать копию")
                    contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    pruneBackups(keep = 3)
                } else {
                    val dir = File(getExternalFilesDir(null), "backups").apply { mkdirs() }
                    File(dir, name).outputStream().use { os -> writeZip(os, json, sessions, pdfs) }
                }
                getSharedPreferences("backup", MODE_PRIVATE).edit()
                    .putString("last_name", name)
                    .putLong("last_time", System.currentTimeMillis())
                    .putString("last_signature", signature)
                    .apply()
                runOnUiThread {
                    loadBackupInfo()
                    if (!auto) {
                        backupMessage = "Копия сохранена"
                        Toast.makeText(this, "Копия сохранена: $name", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!auto) {
                        backupMessage = "Ошибка сохранения: ${e.message}"
                        Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                backupRunning.set(false)
            }
        }.start()
    }

    private fun pruneBackups(keep: Int) {
        if (Build.VERSION.SDK_INT < 29) return
        val items = mutableListOf<Pair<String, Long>>()
        contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("Download/Alfa Diagnostic/backups%", "alfadiag-backup-%.zip"), null
        )?.use {
            while (it.moveToNext()) items += (it.getString(1) ?: "") to it.getLong(0)
        }
        items.sortedByDescending { it.first }.drop(keep).forEach { (_, id) ->
            runCatching { contentResolver.delete(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString()), null, null) }
        }
    }

    private fun loadBackupInfo() {
        val p = getSharedPreferences("backup", MODE_PRIVATE)
        lastBackupName = p.getString("last_name", null)
        lastBackupTime = p.getLong("last_time", 0L)
        lastRestoreTime = p.getLong("last_restore", 0L)
    }

    private fun backupLocationLabel(): String =
        if (Build.VERSION.SDK_INT >= 29) "Download/Alfa Diagnostic/backups"
        else (getExternalFilesDir(null)?.absolutePath ?: "внутренняя память") + "/backups"

    private fun readBackup(uri: Uri) {
        backupMessage = "Восстанавливаю…"
        Thread {
            try {
                val sessionUris = mutableMapOf<String, String>()
                val pdfUris = mutableMapOf<String, String>()
                var jsonText: String? = null
                contentResolver.openInputStream(uri)?.use { raw ->
                    val input = BufferedInputStream(raw)
                    input.mark(2)
                    val b1 = input.read()
                    val b2 = input.read()
                    input.reset()
                    if (b1 == 'P'.code && b2 == 'K'.code) {
                        ZipInputStream(input).use { zin ->
                            var entry = zin.nextEntry
                            while (entry != null) {
                                when {
                                    entry.name == "backup.json" ->
                                        jsonText = zin.readBytes().toString(Charsets.UTF_8)
                                    entry.name.startsWith("sessions/") -> {
                                        val name = entry.name.removePrefix("sessions/")
                                        saveToDownloads("Download/Alfa Diagnostic/sessions", name, "application/octet-stream", zin)
                                            ?.let { sessionUris[name.removeSuffix(".adp")] = it }
                                    }
                                    entry.name.startsWith("conclusions/") -> {
                                        val name = entry.name.removePrefix("conclusions/")
                                        saveToDownloads("Download/Alfa Diagnostic/conclusions", name, "application/pdf", zin)
                                            ?.let { pdfUris[name.removePrefix("Заключение_").removeSuffix(".pdf")] = it }
                                    }
                                }
                                zin.closeEntry()
                                entry = zin.nextEntry
                            }
                        }
                    } else {
                        jsonText = input.readBytes().toString(Charsets.UTF_8)
                    }
                } ?: error("не удалось прочитать файл")
                val text = jsonText ?: error("в архиве нет backup.json")
                runOnUiThread { applyBackup(text, sessionUris, pdfUris) }
            } catch (e: Exception) {
                runOnUiThread { backupMessage = "Ошибка восстановления: ${e.message}" }
            }
        }.start()
    }

    private fun saveToDownloads(relativePath: String, name: String, mime: String, input: InputStream): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "$relativePath%"), null
            )?.use {
                while (it.moveToNext()) {
                    runCatching { contentResolver.delete(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString()), null, null) }
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(u)?.use { input.copyTo(it) }
            contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            u.toString()
        } catch (e: Exception) { null }
    }

    private fun applyBackup(text: String, sessionUris: Map<String, String>, pdfUris: Map<String, String>) {
        try {
            val root = JSONObject(text)
            require(root.optString("app") == "AlfaDiag") { "это не файл резервной копии AlfaDiag" }
            getSharedPreferences("cars", MODE_PRIVATE).edit()
                .putString("items", (root.optJSONArray("cars") ?: JSONArray()).toString())
                .putString("selected_id", root.optString("selectedCarId"))
                .apply()
            recordStore.replaceRaw((root.optJSONArray("records") ?: JSONArray()).toString())
            getSharedPreferences("service_book", MODE_PRIVATE).edit()
                .putString("items", (root.optJSONArray("service") ?: JSONArray()).toString())
                .apply()
            root.optJSONObject("settings")?.let { st ->
                val editor = getSharedPreferences("settings", MODE_PRIVATE).edit()
                st.keys().forEach { k ->
                    when (val v = st.get(k)) {
                        is Boolean -> editor.putBoolean(k, v)
                        is String -> editor.putString(k, v)
                        is Int -> editor.putInt(k, v)
                    }
                }
                editor.apply()
            }
            sessionUris.forEach { (session, u) -> recordStore.setFileUris(session, u, pdfUris[session]) }
            pdfUris.forEach { (session, u) -> if (!sessionUris.containsKey(session)) recordStore.setFileUris(session, null, u) }
            if (carStore.selected() == null) carStore.all().firstOrNull()?.let { carStore.select(it.id) }
            currentCar = carStore.selected()
            if (carStore.all().isNotEmpty()) {
                firstCarDialog = false
                carsDialog = false
            }
            historyVersion++
            serviceVersion++
            getSharedPreferences("backup", MODE_PRIVATE).edit()
                .putLong("last_restore", System.currentTimeMillis())
                .apply()
            loadBackupInfo()
            backupMessage = "Данные и файлы восстановлены"
            Toast.makeText(this, "Данные и файлы восстановлены", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            backupMessage = "Ошибка восстановления: ${e.message}"
            Toast.makeText(this, "Ошибка восстановления: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
            while (it.moveToNext()) result += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString())
        }
        return result
    }

    private fun findPdfUris(sessionName: String): List<Uri> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val result = mutableListOf<Uri>()
        contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf("Заключение_$sessionName.pdf"), null)?.use {
            while (it.moveToNext()) result += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, it.getLong(0).toString())
        }
        return result
    }

    private fun startHistoricalDiagnostic(car: Car, record: DiagnosticRecordStore.Record) {
        if (record.complaint.isBlank()) {
            historicalComplaint = HistoricalComplaintState(car, record)
            return
        }
        doHistoricalUpload(car, record, record.complaint)
    }

    private fun doHistoricalUpload(car: Car, record: DiagnosticRecordStore.Record, complaint: String) {
        val stored = record.sessionUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uris = if (stored != null) listOf(stored) else findSessionUris(record.sessionName)
        if (uris.isEmpty()) { Toast.makeText(this, "Файлы этой сессии не найдены", Toast.LENGTH_LONG).show(); return }
        uploadDiagnostic(car, record.sessionName, complaint, uris)
    }

    private fun performDelete(record: DiagnosticRecordStore.Record) {
        val stored = record.sessionUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        (stored?.let { listOf(it) } ?: findSessionUris(record.sessionName)).forEach { deleteUri(it) }
        record.aiResponseUri?.let { runCatching { deleteUri(Uri.parse(it)) } }
        recordStore.delete(record.sessionName)
    }

    private fun deleteCarCompletely(car: Car) {
        recordStore.forCar(car.id).forEach { record ->
            val stored = record.sessionUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
            val uris = mutableListOf<Uri>()
            stored?.let { uris += it }
            uris += findSessionUris(record.sessionName)
            uris.distinct().forEach { runCatching { deleteUri(it) } }
            record.aiResponseUri?.let { runCatching { deleteUri(Uri.parse(it)) } }
            findPdfUris(record.sessionName).forEach { runCatching { deleteUri(it) } }
            recordStore.delete(record.sessionName)
        }
        serviceStore.forCar(car.id).forEach { serviceStore.delete(it.id) }
        carStore.delete(car.id)
        currentCar = carStore.selected()
        carsDialog = false
        Toast.makeText(this, "Автомобиль и все его данные удалены", Toast.LENGTH_LONG).show()
        if (carStore.all().isEmpty()) firstCarDialog = true
    }

    private fun deleteUri(uri: Uri) {
        runCatching {
            if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
            else contentResolver.delete(uri, null, null)
        }
    }

    private fun uploadDiagnostic(car: Car, sessionName: String, complaint: String, uris: List<Uri>) {
        val state = UploadState(car, sessionName, complaint, uris)
        uploadState = state
        performUpload(state)
    }

    private fun performUpload(state: UploadState) {
        state.percent = 0
        state.error = null
        DiagnosticApi.upload(
            this, state.car, state.sessionName, state.complaint, state.uris, accountStore.token,
            onProgress = { pct -> runOnUiThread { if (pct > state.percent) state.percent = pct } }
        ) { result ->
            runOnUiThread {
                result.onSuccess { id ->
                    state.percent = 100
                    recordStore.markAiSent(state.sessionName, id)
                    uploadState = null
                    startActivity(Intent(this, DiagnosticChatActivity::class.java).apply {
                        putExtra("diagnostic_id", id); putExtra("session_name", state.sessionName)
                    })
                }.onFailure { e -> state.error = e.message ?: "неизвестная ошибка" }
            }
        }
    }

    private fun reopenDiagnostic(record: DiagnosticRecordStore.Record) {
        startActivity(Intent(this, DiagnosticChatActivity::class.java).apply {
            putExtra("diagnostic_id", record.diagnosticId); putExtra("session_name", record.sessionName)
        })
    }

    private fun requestObd() {
        resetObdData()
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(permissions, obdPermissionRequest)
        else { activeKind = "ble"; configureObd(); startObdBusy("Поиск адаптеров…", 12000); ble.scan(settingsPrefs.getString("last_obd", null)) }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        when (request) {
            obdPermissionRequest -> if (results.all { it == PackageManager.PERMISSION_GRANTED }) { activeKind = "ble"; configureObd(); startObdBusy("Поиск адаптеров…", 12000); ble.scan(settingsPrefs.getString("last_obd", null)) }
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
        refreshKeepScreenOn()
        recordStartElapsed = SystemClock.elapsedRealtime(); recordElapsedSec = 0
        recordTimerHandler.removeCallbacks(recordTick); recordTimerHandler.post(recordTick)
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refreshKeepScreenOn() {
        setKeepScreenOn(recording || dashboardOpen)
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
        refreshKeepScreenOn()
        recordTimerHandler.removeCallbacks(recordTick); recordElapsedSec = 0
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

    private fun openDashboard() {
        tripKm = 0.0
        tripSec = 0
        lastTripTick = 0L
        dashboardPro = false
        ElmSession.extendedPids = false
        dashboardLandscape = settingsPrefs.getBoolean("dashboard_landscape", false)
        dashboardOpen = true
        refreshKeepScreenOn()
        applyDashboardOrientation()
        tripHandler.removeCallbacks(tripTick)
        tripHandler.post(tripTick)
    }

    private fun closeDashboard() {
        dashboardOpen = false
        dashboardPro = false
        ElmSession.extendedPids = false
        refreshKeepScreenOn()
        tripHandler.removeCallbacks(tripTick)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun toggleDashboardOrientation() {
        dashboardLandscape = !dashboardLandscape
        settingsPrefs.edit().putBoolean("dashboard_landscape", dashboardLandscape).apply()
        applyDashboardOrientation()
    }

    private fun applyDashboardOrientation() {
        requestedOrientation = if (dashboardLandscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    override fun onDestroy() {
        autoBackupHandler.removeCallbacks(autoBackupRunnable)
        tripHandler.removeCallbacks(tripTick)
        recorder?.stop(); recorder = null; setKeepScreenOn(false)
        if (::ble.isInitialized) ble.close(); spp.close(); wifi.close()
        super.onDestroy()
    }
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

private class HistoricalComplaintState(val car: Car, val record: DiagnosticRecordStore.Record) {
    var complaint by mutableStateOf("")
}

private class UploadState(
    val car: Car,
    val sessionName: String,
    val complaint: String,
    val uris: List<Uri>
) {
    var percent by mutableStateOf(0)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun MainScreen(
    carTitle: String,
    carEnabled: Boolean,
    obdConnected: Boolean,
    obdValuesText: String,
    recording: Boolean,
    recordElapsedSec: Int,
    recordStatusText: String,
    onChangeCar: () -> Unit,
    onAddCar: () -> Unit,
    onHistory: () -> Unit,
    onHelp: () -> Unit,
    onServiceBook: () -> Unit,
    onAccount: () -> Unit,
    savedLabel: String,
    obdBusy: Boolean,
    obdBusyText: String,
    onDisconnect: () -> Unit,
    onConnectSaved: () -> Unit,
    onChooseNew: () -> Unit,
    onDashboard: () -> Unit,
    onToggleRecord: () -> Unit,
    obdLog: List<String>,
    showObdLog: Boolean,
    onClearLog: () -> Unit,
    onShareLog: () -> Unit
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
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(onClick = onHistory, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = DiagGray, contentColor = DiagWhite)) { Text("История") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onServiceBook, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = DiagGray, contentColor = DiagWhite)) { Text("Сервис") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(onClick = onAccount, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = DiagGray, contentColor = DiagWhite)) { Text("Аккаунт") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onHelp, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = DiagGray, contentColor = DiagWhite)) { Text("Помощь") }
            }
            HorizontalDivider(Modifier.padding(top = 14.dp), color = DiagGray)
            if (obdBusy) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(obdBusyText, color = DiagLightGray, fontSize = 16.sp)
                }
            } else {
                when {
                    obdConnected -> Button(
                        onClick = onDisconnect,
                        enabled = carEnabled,
                        colors = ButtonDefaults.buttonColors(containerColor = DiagGreen),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                    ) { Text("Отключить") }
                    savedLabel.isNotBlank() -> {
                        Button(onClick = onConnectSaved, enabled = carEnabled, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("Подключить: $savedLabel") }
                        Button(onClick = onChooseNew, enabled = carEnabled, colors = ButtonDefaults.buttonColors(containerColor = DiagGray), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Выбрать другой адаптер") }
                    }
                    else -> Button(onClick = onChooseNew, enabled = carEnabled, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("Подключить ELM327") }
                }
            }
            val obdLines = obdValuesText.split("\n")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    obdLines.take(4).forEach { Text(it, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp) }
                }
                Column(Modifier.weight(1f)) {
                    obdLines.drop(4).take(4).forEach { Text(it, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp) }
                }
            }
            Button(
                onClick = onDashboard,
                enabled = carEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = DiagGray, contentColor = DiagWhite),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) { Text("ПРИБОРНАЯ ПАНЕЛЬ") }
            Button(
                onClick = onToggleRecord,
                enabled = carEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = if (recording) DiagGreen else MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
            ) { Text(if (recording) "ОСТАНОВИТЬ ЗАПИСЬ" else "НАЧАТЬ ЗАПИСЬ") }
            LinearProgressIndicator(
                progress = { recordElapsedSec.coerceIn(0, 300) / 300f },
                color = DiagRed,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            Text(
                if (recording) "Запись: ${formatDuration(recordElapsedSec)} / 5:00" else recordStatusText,
                color = DiagLightGray,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            )

            /*
            if (showObdLog) {
                HorizontalDivider(color = DiagGray)
                Text("Журнал OBD", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onShareLog) { Text("Отправить") }
                    TextButton(onClick = onClearLog) { Text("Очистить") }
                }
                val logScroll = rememberScrollState()
                LaunchedEffect(obdLog.size) { logScroll.animateScrollTo(logScroll.maxValue) }
                Text(
                    if (obdLog.isEmpty()) "Пока пусто." else obdLog.joinToString("\n"),
                    color = DiagLightGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .verticalScroll(logScroll)
                        .padding(top = 8.dp, bottom = 24.dp)
                )
            }
            */
        }
    }
}

@Composable
private fun FirstCarDialog(onAdd: () -> Unit, onRestore: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text("Добро пожаловать!") },
        text = {
            Column {
                Text("Чтобы начать, добавьте автомобиль.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Если у вас уже есть резервная копия AlfaDiag (после переустановки приложения или с другого телефона), восстановите её — вернутся автомобили, диагностики, сервисная книжка, чаты и PDF.",
                    fontSize = 13.sp,
                    color = DiagLightGray
                )
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Добавить автомобиль") } },
        dismissButton = { TextButton(onClick = onRestore) { Text("Восстановить из копии") } }
    )
}

@Composable
private fun CarsDialog(
    cars: List<Car>,
    selectedCar: Car?,
    onSelect: (Car) -> Unit,
    onEdit: (Car) -> Unit,
    onDelete: (Car) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Выберите автомобиль") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                cars.forEach { car ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(car) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = car.id == selectedCar?.id, onClick = { onSelect(car) })
                        Spacer(Modifier.width(8.dp))
                        Text(car.title(), modifier = Modifier.weight(1f))
                        IconButton(onClick = { onEdit(car) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Изменить автомобиль", tint = DiagLightGray)
                        }
                        IconButton(onClick = { onDelete(car) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Удалить автомобиль", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onAdd) { Text("+ Добавить") }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
    )
}

@Composable
private fun DeleteCarDialog(car: Car, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val maxTextHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        icon = {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(
                "Удалить автомобиль?",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(Modifier.heightIn(max = maxTextHeight).verticalScroll(rememberScrollState())) {
                Text("Вы собираетесь удалить «${car.title()}».", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "ВМЕСТЕ С НИМ БУДУТ БЕЗВОЗВРАТНО УДАЛЕНЫ:",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text("• все диагностики этого автомобиля;")
                Text("• все записи и файлы сессий (.adp);")
                Text("• все PDF-заключения;")
                Text("• сервисная книжка и её записи;")
                Text("• все прочие данные, связанные с автомобилем.")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Отменить это действие будет НЕВОЗМОЖНО.",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Если нужны заключения — сначала сохраните их PDF. Готовые PDF лежат в папке: Download/Alfa Diagnostic/conclusions",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("УДАЛИТЬ ВСЁ", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}

@Composable
private fun ObdDevicesDialog(devices: List<ObdDevice>, hint: String = "", onSelect: (ObdDevice) -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Выберите OBD-адаптер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (hint.isNotBlank()) Text(hint, color = DiagLightGray, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                if (devices.isEmpty()) Text("Список пуст.", color = DiagLightGray)
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
private fun ConnectChooserDialog(onBle: () -> Unit, onSpp: () -> Unit, onWifi: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Тип подключения") },
        text = {
            Column {
                Button(onClick = onBle, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("Bluetooth LE (BLE)") }
                Button(onClick = onSpp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("Bluetooth (SPP)") }
                Button(onClick = onWifi, modifier = Modifier.fillMaxWidth()) { Text("Wi-Fi") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}

@Composable
private fun WifiDialog(initial: String, onConnect: (String) -> Unit, onCancel: () -> Unit) {
    var host by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Wi-Fi адаптер") },
        text = {
            Column {
                Text("Введите адрес адаптера: host или host:порт (по умолчанию порт 35000).", fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Адрес") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (host.isNotBlank()) onConnect(host.trim()) }) { Text("Подключить") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}

@Composable
private fun AccountDialog(
    accountId: String?,
    credits: Int?,
    spentMicros: Long?,
    subscriptionUntil: String,
    transferCode: String?,
    busy: Boolean,
    message: String?,
    backupMessage: String?,
    lastBackupName: String?,
    lastBackupTime: Long,
    lastRestoreTime: Long,
    backupLocation: String,
    onCreateCode: () -> Unit,
    onRedeem: (String) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Аккаунт") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (accountId.isNullOrBlank()) {
                    Text("Создаю аккаунт…")
                } else {
                    Text("ID: ${accountId.take(8)}…", fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text("Диагностик в пакете: ${credits ?: 0}")
                    Text("Израсходовано на ИИ: ${formatMicros(spentMicros)}")
                    Text(subscriptionLabel(subscriptionUntil))
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onCreateCode,
                    enabled = !busy && !accountId.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DiagGray)
                ) { Text("Создать код переноса") }
                if (transferCode != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Код переноса: $transferCode", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("Введите его на новом телефоне ниже. Код действует 20 минут и сработает один раз.", fontSize = 13.sp, color = DiagLightGray)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = DiagGray)
                Spacer(Modifier.height(12.dp))
                Text("Перенос на этот телефон", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                    label = { Text("Код переноса") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    onClick = { onRedeem(input.trim()) },
                    enabled = input.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Привязать аккаунт") }
                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = DiagGray)
                Spacer(Modifier.height(12.dp))
                Text("Резервная копия", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Сохраните все данные приложения в один архив (.zip): автомобили, диагностики, сервисную книжку, чаты с ИИ, PDF-заключения и файлы сессий. Это нужно при переустановке приложения или переносе на новый телефон. Архив содержит личные данные, поэтому храните его в надёжном месте.",
                    fontSize = 13.sp,
                    color = DiagLightGray
                )
                Spacer(Modifier.height(10.dp))
                if (lastBackupTime > 0) {
                    Text("Последняя копия: ${formatDateTime(lastBackupTime)}", fontWeight = FontWeight.Bold)
                    lastBackupName?.let { Text("Файл: $it", fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
                    Text("Путь: $backupLocation", fontSize = 13.sp, color = DiagLightGray)
                } else {
                    Text("Резервных копий пока нет.", fontSize = 13.sp, color = DiagLightGray)
                }
                if (lastRestoreTime > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("Последнее восстановление: ${formatDateTime(lastRestoreTime)}", fontSize = 13.sp, color = DiagLightGray)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = DiagGray)
                ) { Text("Сохранить копию сейчас", fontSize = 14.sp) }
                Button(
                    onClick = onRestore,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DiagGray)
                ) { Text("Восстановить из копии", fontSize = 14.sp) }
                Text(
                    "Копии создаются автоматически при запуске приложения и далее каждые 10 минут (хранятся 3 последние).",
                    fontSize = 12.sp,
                    color = DiagLightGray,
                    modifier = Modifier.padding(top = 6.dp)
                )
                backupMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}

@Composable
private fun ConfirmRestoreDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Восстановить данные?") },
        text = { Text("Текущие данные приложения (автомобили, диагностики, сервисная книжка, чаты) будут заменены данными из файла резервной копии. Это действие нельзя отменить.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("ВЫБРАТЬ ФАЙЛ") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}

private fun subscriptionLabel(until: String): String {
    if (until.isBlank()) return "Подписка не активна"
    return "Подписка активна до: ${until.substringBefore('T')}"
}

private fun formatMicros(micros: Long?): String =
    String.format(Locale.US, "\$%.4f", (micros ?: 0L) / 1_000_000.0)

@OptIn(ExperimentalLayoutApi::class)
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
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when { record.aiResponseUri != null -> "ИИ: заключение получено"; record.aiSent -> "ИИ: ответ ожидается"; else -> "ИИ: не отправлено" },
                            modifier = Modifier.weight(1f)
                        )
                        FilledIconButton(
                            onClick = { onDelete(record) },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = DiagButtonGray)
                        ) { Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = DiagRed) }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!record.aiSent) HistoryActionButton("Отправить ИИ") { onSendAi(record) }
                        if (record.aiSent && record.diagnosticId != null) HistoryActionButton("Открыть диагностику") { onOpenDiagnostic(record) }
                        if (record.aiResponseUri != null) HistoryActionButton("PDF") { onOpenPdf(record) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = DiagButtonGray, contentColor = DiagRed)
            ) { Text("Закрыть") }
        }
    )
}

@Composable
private fun HistoryActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = DiagButtonGray, contentColor = DiagRed),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 14.sp) }
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
private fun UploadProgressDialog(state: UploadState, onRetry: () -> Unit, onClose: () -> Unit) {
    val err = state.error
    if (err == null) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(200)
                if (state.percent < 95) state.percent += 1
            }
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Передача данных в ИИ") },
            text = {
                Column {
                    Text("Диагностическая сессия отправляется искусственному интеллекту на анализ. Дождитесь окончания передачи — не закрывайте приложение.")
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${state.percent}%",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {}
        )
    } else {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Не удалось отправить") },
            text = { Text("Передача в ИИ не удалась.\n\n$err") },
            confirmButton = { TextButton(onClick = onRetry) { Text("ПОВТОРИТЬ") } },
            dismissButton = { TextButton(onClick = onClose) { Text("ЗАКРЫТЬ") } }
        )
    }
}

@Composable
private fun HistoricalComplaintDialog(state: HistoricalComplaintState, onSend: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Опишите проблему") },
        text = {
            Column {
                Text("Чтобы ИИ разобрал запись, опишите, что вас беспокоит.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = state.complaint, onValueChange = { state.complaint = it }, label = { Text("Что вас беспокоит?") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = onSend, enabled = state.complaint.isNotBlank()) { Text("ОТПРАВИТЬ ИИ") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
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
private fun ServiceBookScreen(car: Car, entries: List<ServiceEntry>, hints: List<String>, onAdd: () -> Unit, onEdit: (ServiceEntry) -> Unit, onDelete: (ServiceEntry) -> Unit, onBack: () -> Unit) {
    Surface(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Сервисная книжка", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Назад") }
            }
            Text(car.title(), color = DiagLightGray, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (hints.isNotEmpty()) {
                    Text("Подсказки (ориентировочно)", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
                    hints.forEach { Text("• $it", color = DiagLightGray, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DiagGray)
                }
                if (entries.isEmpty()) Text("Записей пока нет. Добавьте первую.", color = DiagLightGray)
                entries.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onEdit(e) }) {
                            val head = listOfNotNull(
                                e.kind.ifBlank { null },
                                e.mileage.ifBlank { null }?.let { "$it км" },
                                e.date.ifBlank { null }
                            ).joinToString(" · ")
                            if (head.isNotBlank()) Text(head, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            if (e.title.isNotBlank()) Text(e.title, fontSize = 16.sp)
                            if (e.note.isNotBlank()) Text(e.note, color = DiagLightGray, fontSize = 14.sp)
                        }
                        IconButton(onClick = { onDelete(e) }) { Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = DiagRed) }
                    }
                    HorizontalDivider(color = DiagGray)
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("+ Добавить запись") }
        }
    }
}

@Composable
private fun ServiceEntryDialog(entry: ServiceEntry, onSave: (ServiceEntry) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(entry.date) }
    var mileage by remember { mutableStateOf(entry.mileage) }
    var kind by remember { mutableStateOf(entry.kind.ifBlank { "ТО" }) }
    var title by remember { mutableStateOf(entry.title) }
    var note by remember { mutableStateOf(entry.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.title.isBlank() && entry.note.isBlank()) "Новая запись" else "Запись") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = date, onValueChange = { date = it }, label = { Text("Дата (напр. 30.09.2026)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = mileage, onValueChange = { mileage = it.filter { c -> c.isDigit() } }, label = { Text("Пробег, км") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ServiceStore.KINDS.forEach { k ->
                        RadioButton(selected = kind == k, onClick = { kind = k })
                        Text(k, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Что сделано (кратко)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Описание (необязательно)") }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(entry.copy(date = date.trim(), mileage = mileage.trim(), kind = kind, title = title.trim(), note = note.trim())) }, enabled = title.isNotBlank() || note.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private data class HelpStep(val emoji: String, val title: String, val text: String)

@Composable
private fun HelpScreen(onClose: () -> Unit) {
    val steps = listOf(
        HelpStep("🚗", "Добавьте автомобиль", "Нажмите «+ Добавить» и заполните данные машины. Можно определить по VIN."),
        HelpStep("🔌", "Подключите OBD (если есть)", "Кнопка «Подключить ELM327»: BLE, Bluetooth или Wi-Fi. Без адаптера тоже можно — по GPS и звуку."),
        HelpStep("📱", "Закрепите телефон", "Поставьте телефон в держатель в машине и заведите двигатель."),
        HelpStep("🎙️", "Начните запись", "Нажмите «НАЧАТЬ ЗАПИСЬ» и поезжайте. Запись идёт до 5 минут."),
        HelpStep("⏹️", "Остановите запись", "Когда проедете — нажмите «ОСТАНОВИТЬ ЗАПИСЬ»."),
        HelpStep("💬", "Опишите проблему", "Расскажите, что беспокоит, и нажмите «НАЧАТЬ ДИАГНОСТИКУ ИИ»."),
        HelpStep("📄", "Получите заключение", "При необходимости уточните у ИИ и сохраните PDF-заключение.")
    )
    var step by remember { mutableStateOf(0) }
    val s = steps[step]
    val transition = rememberInfiniteTransition()
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
    )
    Surface(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(s.emoji, fontSize = 96.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
            Spacer(Modifier.height(24.dp))
            Text(s.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(s.text, fontSize = 16.sp, color = DiagLightGray, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row {
                steps.indices.forEach { i ->
                    Box(Modifier.padding(4.dp).size(if (i == step) 10.dp else 8.dp).clip(CircleShape).background(if (i == step) DiagRed else DiagGray))
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth()) {
                if (step > 0) {
                    Button(onClick = { step-- }, colors = ButtonDefaults.buttonColors(containerColor = DiagGray), modifier = Modifier.weight(1f)) { Text("Назад") }
                    Spacer(Modifier.width(8.dp))
                }
                if (step < steps.lastIndex) Button(onClick = { step++ }, modifier = Modifier.weight(1f)) { Text("Далее") }
                else Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Понятно") }
            }
            TextButton(onClick = onClose) { Text("Пропустить") }
        }
    }
}

@Composable
private fun WelcomeScreen(onDismiss: () -> Unit, onHelp: () -> Unit) {
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
                    Button(
                        onClick = onHelp,
                        colors = ButtonDefaults.buttonColors(containerColor = DiagGray, contentColor = DiagWhite),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    ) { Text("Как пользоваться") }
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
