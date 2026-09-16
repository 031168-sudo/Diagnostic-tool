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
import android.os.SystemClock
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
            if (status != BluetoothGatt.GATT_SUCCESS) { listener.onState("Ошибка GATT: $status"); return }
            var bestWrite: BluetoothGattCharacteristic? = null
            var bestNotify: BluetoothGattCharacteristic? = null
            var bestScore = -1
            for (service in g.services) {
                val writes = service.characteristics.filter {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                }
                val notifies = service.characteristics.filter {
                    (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                }
                for (w in writes) for (n in notifies) {
                    val score = (if (w.uuid == n.uuid) 3 else 0) +
                        (if (w.uuid.toString().contains("ffe1", true)) 10 else 0) +
                        (if (n.uuid.toString().contains("ffe1", true)) 10 else 0) +
                        (if (w.uuid.toString().contains("fff1", true)) 8 else 0) +
                        (if (n.uuid.toString().contains("fff1", true)) 8 else 0)
                    if (score > bestScore) { bestScore = score; bestWrite = w; bestNotify = n }
                }
            }
            if (bestWrite == null || bestNotify == null) { listener.onState("У OBDII не найдена BLE UART-служба"); return }
            writeCharacteristic = bestWrite
            rxCharacteristic = bestNotify
            listener.onState("BLE UART: ${bestWrite.uuid} / ${bestNotify.uuid}")
            g.setCharacteristicNotification(bestNotify, true)
            val descriptor = bestNotify.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            } else startElmSession()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) startElmSession() else listener.onState("Ошибка включения уведомлений BLE: $status")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            consume(characteristic.value?.toString(Charsets.US_ASCII) ?: "")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startElmSession() {
        thread(name = "elm327-session") {
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6").forEach { command -> sendAndWait(command, 3000) }
            running = true
            commandIndex = 0
            listener.onState("OBDII $targetMac готов; OBD опрашивается")
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

    private fun sendAndWait(command: String, timeoutMs: Long): String {
        synchronized(responseLock) {
            responseText = StringBuilder(); promptReceived = false; rxBuffer.clear(); send(command)
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (!promptReceived && SystemClock.uptimeMillis() < deadline) {
                try { responseLock.wait(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
            return responseText.toString()
        }
    }

    private fun consume(text: String) {
        synchronized(responseLock) {
            rxBuffer.append(text); responseText.append(text)
            if (rxBuffer.contains(">")) { promptReceived = true; responseLock.notifyAll(); rxBuffer.clear() }
        }
    }

    private fun parseResponse(response: String, pid: Int): Boolean {
        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        val marker = "41" + "%02X".format(Locale.US, pid)
        val start = hex.indexOf(marker)
        if (start < 0) return false
        val data = hex.substring(start + marker.length)
        fun b(i: Int): Int? = if (data.length >= i + 2) data.substring(i, i + 2).toIntOrNull(16) else null
        when (pid) {
            0x0B -> { val a = b(0) ?: return false; values.map = a.toDouble() }
            0x0C -> { val a=b(0) ?: return false; val c=b(2) ?: return false; values.rpm=(a*256+c)/4.0 }
            0x0D -> { val a=b(0) ?: return false; values.speed=a.toDouble() }
            0x04 -> { val a=b(0) ?: return false; values.load=a*100.0/255.0 }
            0x11 -> { val a=b(0) ?: return false; values.throttle=a*100.0/255.0 }
            0x05 -> { val a=b(0) ?: return false; values.coolant=a-40.0 }
            0x0F -> { val a=b(0) ?: return false; values.intake=a-40.0 }
            0x42 -> { val a=b(0) ?: return false; val c=b(2) ?: return false; values.voltage=(a*256+c)/1000.0 }
            else -> return false
        }
        return true
    }

    private fun pollLoop() {
        while (running) {
            val command = commands[commandIndex++ % commands.size]
            val response = sendAndWait(command, 2000)
            if (parseResponse(response, command.substring(2).toInt(16))) listener.onData(values.copy(), SystemClock.elapsedRealtimeNanos())
            try { Thread.sleep(80) } catch (_: InterruptedException) { break }
        }
    }

    fun close() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(responseLock) { promptReceived = true; responseLock.notifyAll(); rxBuffer.clear() }
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; writeCharacteristic = null; rxCharacteristic = null
    }
}
