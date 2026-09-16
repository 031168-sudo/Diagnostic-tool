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
    private val listener: Listener
) {
    interface Listener {
        fun onState(text: String)
        fun onData(values: ObdValues, monotonicNs: Long)
    }

    companion object {
        const val TARGET_MAC = "66:1E:11:0E:02:C6"
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
        listener.onState("Поиск BLE OBDII: $TARGET_MAC...")
        val scanner = adapter.bluetoothLeScanner
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                if (found || !device.address.equals(TARGET_MAC, ignoreCase = true)) return
                found = true
                scanner.stopScan(this)
                listener.onState("Найден OBDII $TARGET_MAC; подключение...")
                gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            override fun onScanFailed(errorCode: Int) { listener.onState("Ошибка BLE scan: $errorCode") }
        }
        scanner.startScan(callback)
        mainHandler.postDelayed({
            scanner.stopScan(callback)
            if (!found && gatt == null) listener.onState("OBDII $TARGET_MAC не найден")
        }, 10_000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                listener.onState("OBDII $TARGET_MAC подключён; поиск GATT...")
                g.discoverServices()
            } else {
                running = false
                listener.onState("OBDII отключён (status=$status)")
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
                if (writes.isEmpty() || notifies.isEmpty()) continue
                for (w in writes) for (n in notifies) {
                    val s = (if (w.uuid.toString().contains("ffe1", true)) 10 else 0) +
                            (if (n.uuid.toString().contains("ffe1", true)) 10 else 0) +
                            (if (w.uuid.toString().contains("fff1", true)) 8 else 0) +
                            (if (n.uuid.toString().contains("fff1", true)) 8 else 0) +
                            (if (w.uuid == n.uuid) 3 else 0)
                    if (s > bestScore) { bestScore = s; bestWrite = w; bestNotify = n }
                }
            }
            writeCharacteristic = bestWrite
            rxCharacteristic = bestNotify
            if (bestWrite == null || bestNotify == null) {
                listener.onState("У OBDII $TARGET_MAC не найдена BLE UART-служба")
                return
            }
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
            if (status == BluetoothGatt.GATT_SUCCESS) startElmSession()
            else listener.onState("Ошибка включения уведомлений BLE: $status")
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
        thread(name = "elm327-session") {
            sendAndWait("ATZ", 3000)
            sendAndWait("ATE0", 1200)
            sendAndWait("ATL0", 1200)
            sendAndWait("ATS0", 1200)
            sendAndWait("ATH0", 1200)
            sendAndWait("ATSP6", 4000)
            running = true
            commandIndex = 0
            listener.onState("OBDII $TARGET_MAC готов; OBD опрашивается")
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
            responseText = StringBuilder()
            promptReceived = false
            rxBuffer.clear()
            send(command)
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (!promptReceived) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) break
                try { responseLock.wait(remaining) } catch (_: InterruptedException) { break }
            }
            return responseText.toString()
        }
    }

    private fun consume(text: String) {
        synchronized(responseLock) {
            rxBuffer.append(text)
            responseText.append(text)
            if (rxBuffer.contains('>')) {
                promptReceived = true
                responseLock.notifyAll()
                rxBuffer.clear()
            }
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
            0x0B -> { val a=b(0) ?: return false; values.map=a.toDouble() }
            0x0C -> { val a=b(0); val c=b(2); if (a!=null&&c!=null) values.rpm=(a*256+c)/4.0 else return false }
            0x0D -> { val a=b(0) ?: return false; values.speed=a.toDouble() }
            0x04 -> { val a=b(0) ?: return false; values.load=a*100.0/255.0 }
            0x11 -> { val a=b(0) ?: return false; values.throttle=a*100.0/255.0 }
            0x05 -> { val a=b(0) ?: return false; values.coolant=a-40.0 }
            0x0F -> { val a=b(0) ?: return false; values.intake=a-40.0 }
            0x42 -> { val a=b(0); val c=b(2); if(a!=null&&c!=null) values.voltage=(a*256+c)/1000.0 else return false }
            else -> return false
        }
        return true
    }

    private fun pollLoop() {
        while (running) {
            val command = commands[commandIndex++ % commands.size]
            val response = sendAndWait(command, 2000)
            val pid = command.substring(2).toInt(16)
            if (parseResponse(response, pid)) listener.onData(values.copy(), SystemClock.elapsedRealtimeNanos())
            Thread.sleep(80)
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(responseLock) { promptReceived = true; responseLock.notifyAll(); rxBuffer.clear() }
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        rxCharacteristic = null
    }
}

// Force CI rebuild after the supported-PID update.
