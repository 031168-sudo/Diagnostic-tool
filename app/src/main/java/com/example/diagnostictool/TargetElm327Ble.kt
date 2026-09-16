package com.example.diagnostictool

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class TargetElm327Ble(
    private val activity: AppCompatActivity,
    private val listener: Listener,
    private val targetMac: String
) {
    interface Listener {
        fun onState(text: String)
        fun onData(values: ObdValues, monotonicNs: Long)
    }

    companion object {
        const val DEFAULT_TARGET_MAC = "66:1E:11:0E:02:C6"
        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private val rxBuffer = StringBuilder()
    private val responseLock = Object()
    private var responseText = StringBuilder()
    private var promptReceived = false
    private val values = ObdValues()
    @Volatile private var running = false
    @Volatile private var found = false
    private var commandIndex = 0
    // Vehicle-supported PIDs only: RPM, speed, load, throttle, MAP, coolant, intake temp, voltage.
    // 0110 (MAF) is not supported by this vehicle.
    private val commands = listOf("010C", "010D", "0104", "0111", "010B", "0105", "010F", "0142")
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connect() {
        close()
        if (!adapter.isEnabled) { listener.onState("Bluetooth выключен"); return }
        found = false
        listener.onState("Поиск BLE OBDII: $targetMac...")
        val scanner = adapter.bluetoothLeScanner
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                if (found || !device.address.equals(targetMac, ignoreCase = true)) return
                found = true
                scanner.stopScan(this)
                listener.onState("Найден OBDII $targetMac; подключение...")
                gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            override fun onScanFailed(errorCode: Int) { listener.onState("Ошибка BLE scan: $errorCode") }
        }
        scanner.startScan(callback)
        mainHandler.postDelayed({
            scanner.stopScan(callback)
            if (!found && gatt == null) listener.onState("OBDII $targetMac не найден")
        }, 10_000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                listener.onState("OBDII $targetMac подключён; поиск GATT...")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                running = false
                listener.onState("OBDII отключён")
                g.close()
                gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val uart = g.services.flatMap { it.characteristics }.firstOrNull { c ->
                (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 &&
                    (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
            }
            if (uart == null) { listener.onState("BLE UART не найден"); return }
            writeCharacteristic = uart
            rxCharacteristic = uart
            g.setCharacteristicNotification(uart, true)
            val descriptor = uart.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            }
            listener.onState("ELM327: инициализация...")
            thread { initialize() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val text = characteristic.value?.toString(Charsets.US_ASCII) ?: return
            synchronized(responseLock) {
                rxBuffer.append(text)
                responseText.append(text)
                if (rxBuffer.contains(">")) {
                    promptReceived = true
                    responseLock.notifyAll()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initialize() {
        val init = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6")
        for (command in init) {
            if (!sendAndWait(command)) { listener.onState("ELM327: нет ответа на $command"); return }
        }
        running = true
        listener.onState("ELM327 готов; опрос PID...")
        pollLoop()
    }

    @SuppressLint("MissingPermission")
    private fun pollLoop() {
        while (running) {
            val command = commands[commandIndex % commands.size]
            commandIndex++
            val response = sendAndWait(command)
            val value = parsePid(response, command)
            if (value != null) {
                when (command) {
                    "010C" -> values.rpm = value * 4.0
                    "010D" -> values.speed = value
                    "0104" -> values.load = value
                    "0111" -> values.throttle = value
                    "010B" -> values.map = value
                    "0105" -> values.coolant = value - 40.0
                    "010F" -> values.intake = value - 40.0
                    "0142" -> values.voltage = value / 1000.0
                }
                listener.onData(values.copy(), android.os.SystemClock.elapsedRealtimeNanos())
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendAndWait(command: String): String {
        val characteristic = writeCharacteristic ?: return ""
        synchronized(responseLock) {
            responseText = StringBuilder()
            rxBuffer.clear()
            promptReceived = false
            characteristic.value = (command + "\r").toByteArray(Charsets.US_ASCII)
            gatt?.writeCharacteristic(characteristic)
            val deadline = System.currentTimeMillis() + 3000
            while (!promptReceived && System.currentTimeMillis() < deadline) {
                try { responseLock.wait(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            return responseText.toString()
        }
    }

    private fun parsePid(response: String, command: String): Double? {
        val pid = command.substring(2)
        val compact = response.uppercase(Locale.US).replace(" ", "").replace("\r", "").replace("\n", "")
        val marker = "41$pid"
        val i = compact.indexOf(marker)
        if (i < 0 || i + marker.length + 2 > compact.length) return null
        return try { compact.substring(i + marker.length, i + marker.length + 2).toInt(16).toDouble() } catch (_: Exception) { null }
    }

    fun close() {
        running = false
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeCharacteristic = null
        rxCharacteristic = null
    }
}
