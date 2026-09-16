package com.example.diagnostictool

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var obdValues: TextView
    private lateinit var recordButton: Button
    private lateinit var recordStatus: TextView
    private lateinit var obd: Elm327Ble
    private var recorder: SessionRecorder? = null
    private val requestCode = 10

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        obdValues = findViewById(R.id.obdValues)
        recordButton = findViewById(R.id.record)
        recordStatus = findViewById(R.id.recordStatus)

        obd = Elm327Ble(this, object : Elm327Ble.Listener {
            override fun onState(text: String) = runOnUiThread { status.text = text }

            override fun onData(values: ObdValues, monotonicNs: Long) {
                runOnUiThread { obdValues.text = values.toDisplay() }
                recorder?.onObd(values, monotonicNs)
            }
        })

        findViewById<Button>(R.id.connect).setOnClickListener { requestAndConnect() }
        recordButton.setOnClickListener { toggleRecording() }
    }

    private fun requestAndConnect() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissions += Manifest.permission.RECORD_AUDIO

        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions.toTypedArray(), requestCode)
        } else {
            obd.connect()
        }
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == requestCode && results.all { it == PackageManager.PERMISSION_GRANTED }) obd.connect()
    }

    private fun toggleRecording() {
        if (recorder == null) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestAndConnect()
                return
            }
            val r = SessionRecorder(this) { text -> runOnUiThread { recordStatus.text = text } }
            recorder = r
            r.start()
            recordButton.text = "ОСТАНОВИТЬ ЗАПИСЬ"
        } else {
            recorder?.stop()
            recorder = null
            recordButton.text = "НАЧАТЬ ЗАПИСЬ"
        }
    }

    override fun onDestroy() {
        recorder?.stop()
        obd.close()
        super.onDestroy()
    }
}

data class ObdValues(
    var rpm: Double? = null,
    var speed: Double? = null,
    var load: Double? = null,
    var throttle: Double? = null,
    var maf: Double? = null,
    var map: Double? = null,
    var coolant: Double? = null,
    var intake: Double? = null,
    var voltage: Double? = null
) {
    fun toDisplay(): String = buildString {
        append("RPM: ").append(rpm?.let { "%.0f".format(Locale.US, it) } ?: "—")
        append("\nSpeed: ").append(speed?.let { "%.0f km/h".format(Locale.US, it) } ?: "—")
        append("\nLoad: ").append(load?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nThrottle: ").append(throttle?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nMAP: ").append(map?.let { "%.0f kPa".format(Locale.US, it) } ?: "—")
        append("\nCoolant: ").append(coolant?.let { "%.0f °C".format(Locale.US, it) } ?: "—")
        append("\nIntake: ").append(intake?.let { "%.0f °C".format(Locale.US, it) } ?: "—")
        append("\nVoltage: ").append(voltage?.let { "%.2f V".format(Locale.US, it) } ?: "—")
    }
}

class Elm327Ble(
    private val activity: AppCompatActivity,
    private val listener: Listener
) {
    interface Listener {
        fun onState(text: String)
        fun onData(values: ObdValues, monotonicNs: Long)
    }

    private val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val rxBuffer = StringBuilder()
    private val values = ObdValues()
    private var running = false
    private var commandIndex = 0
    private val commands = listOf("010C", "010D", "0104", "0111", "0110", "0105", "0142")

    @SuppressLint("MissingPermission")
    fun connect() {
        if (!adapter.isEnabled) {
            listener.onState("Bluetooth выключен")
            return
        }
        listener.onState("Поиск ELM327 BLE...")
        val scanner = adapter.bluetoothLeScanner
        var found = false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found) return
                val name = result.device.name ?: return
                if (name.contains("ELM", true) || name.contains("OBD", true) || name.contains("V-LINK", true)) {
                    found = true
                    scanner.stopScan(this)
                    listener.onState("Подключение к $name...")
                    gatt = result.device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onState("Ошибка BLE scan: $errorCode")
            }
        }
        scanner.startScan(callback)
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(callback)
            if (!found && gatt == null) listener.onState("ELM327 не найден")
        }, 10_000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                listener.onState("ELM327 подключён; поиск характеристик...")
                gatt = g
                g.discoverServices()
            } else {
                running = false
                listener.onState("ELM327 отключён")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var write: BluetoothGattCharacteristic? = null
            var rx: BluetoothGattCharacteristic? = null
            g.services.flatMap { it.characteristics }.forEach { c ->
                val p = c.properties
                if (write == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) write = c
                if (rx == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) rx = c
            }
            writeCharacteristic = write
            rxCharacteristic = rx
            if (write == null || rx == null) {
                listener.onState("BLE UART ELM327 не найден")
                return
            }

            g.setCharacteristicNotification(rx, true)
            val descriptor = rx.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            } else {
                startElmSession()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            startElmSession()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            consume(value.toString(Charsets.US_ASCII))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            consume(characteristic.value.toString(Charsets.US_ASCII))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startElmSession() {
        thread(name = "elm327-init") {
            send("ATZ"); Thread.sleep(1200)
            send("ATE0"); Thread.sleep(300)
            send("ATL0"); Thread.sleep(300)
            send("ATS0"); Thread.sleep(300)
            send("ATH0"); Thread.sleep(300)
            running = true
            listener.onState("ELM327 готов; OBD опрашивается")
            pollLoop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(command: String) {
        val c = writeCharacteristic ?: return
        c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = (command + "\r").toByteArray(Charsets.US_ASCII)
        gatt?.writeCharacteristic(c)
    }

    private fun consume(text: String) {
        rxBuffer.append(text)
        if (rxBuffer.contains('>')) rxBuffer.clear()
    }

    private fun pollLoop() {
        while (running) {
            val command = commands[commandIndex++ % commands.size]
            send(command)
            Thread.sleep(250)
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        running = false
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        rxCharacteristic = null
    }

    companion object {
        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
