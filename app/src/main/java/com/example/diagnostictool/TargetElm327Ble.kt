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
import androidx.activity.ComponentActivity
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class TargetElm327Ble(
    private val activity: ComponentActivity,
    private val listener: Listener
) {
    interface Listener {
        fun onState(text: String)
        fun onData(values: ObdValues, monotonicNs: Long)
        fun onDevices(devices: List<DeviceInfo>)
        fun onErrors(codes: List<String>, raw: String)
    }

    data class DeviceInfo(val device: BluetoothDevice, val name: String, val address: String) {
        fun title(): String = if (name.isBlank()) "OBDII" else name
        fun label(): String = "${title()}\n$address"
    }

    companion object {
        private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val OBD_NAME_HINTS = listOf("obd", "elm", "vlink", "v-link", "vgate", "icar", "konnwei", "veepeak", "bafx", "kw902", "autool", "scanner", "obdii")
        private val OBD_SERVICE_UUIDS = setOf(
            "0000ffe0-0000-1000-8000-00805f9b34fb",
            "0000fff0-0000-1000-8000-00805f9b34fb",
            "0000ff00-0000-1000-8000-00805f9b34fb",
            "0000ffe5-0000-1000-8000-00805f9b34fb",
            "0000fff1-0000-1000-8000-00805f9b34fb",
            "000018f0-0000-1000-8000-00805f9b34fb"
        )
    }

    private fun looksLikeObd(name: String, uuids: List<UUID>?): Boolean {
        val n = name.lowercase(Locale.US)
        if (OBD_NAME_HINTS.any { n.contains(it) }) return true
        if (uuids != null && uuids.any { it.toString().lowercase(Locale.US) in OBD_SERVICE_UUIDS }) return true
        return false
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
    private var commandIndex = 0
    private var selectedDevice: BluetoothDevice? = null
    private val commands = listOf("010C", "010D", "0104", "0111", "010B", "0105", "010F", "0142")
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun scan(preferredAddress: String? = null) {
        close()
        if (!adapter.isEnabled) { listener.onState("Bluetooth выключен"); return }
        listener.onState("Поиск OBD-адаптеров рядом...")
        val scanner = adapter.bluetoothLeScanner
        val all = LinkedHashMap<String, DeviceInfo>()
        val obd = LinkedHashMap<String, DeviceInfo>()
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: try { device.name ?: "" } catch (_: Exception) { "" }
                val info = DeviceInfo(device, name, device.address)
                all[device.address] = info
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }
                if (looksLikeObd(name, uuids) || device.address.equals(preferredAddress, true)) obd[device.address] = info
            }
            override fun onScanFailed(errorCode: Int) { listener.onState("Ошибка BLE scan: $errorCode") }
        }
        scanner.startScan(callback)
        mainHandler.postDelayed({
            scanner.stopScan(callback)
            val primary = if (obd.isNotEmpty()) obd.values.toList() else all.values.toList()
            val list = primary.sortedWith(
                compareBy<DeviceInfo> { !it.address.equals(preferredAddress, true) }
                    .thenBy { !looksLikeObd(it.title(), null) }
                    .thenBy { it.title() }
            )
            if (list.isEmpty()) listener.onState("OBD-адаптеры не найдены") else listener.onDevices(list)
        }, 8000)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close(); selectedDevice = device
        listener.onState("Подключение к ${device.address}...")
        gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g; listener.onState("OBDII ${selectedDevice?.address ?: ""} подключён; поиск GATT..."); g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                running = false; listener.onState("OBDII отключён"); listener.onErrors(emptyList(), ""); g.close(); gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { listener.onState("Ошибка GATT: $status"); return }
            var bestWrite: BluetoothGattCharacteristic? = null; var bestNotify: BluetoothGattCharacteristic? = null; var bestScore = -1
            for (service in g.services) {
                val writes = service.characteristics.filter { (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 }
                val notifies = service.characteristics.filter { (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0 }
                for (w in writes) for (n in notifies) {
                    val score = (if (w.uuid == n.uuid) 3 else 0) + (if (w.uuid.toString().contains("ffe1", true)) 10 else 0) + (if (n.uuid.toString().contains("ffe1", true)) 10 else 0) + (if (w.uuid.toString().contains("fff1", true)) 8 else 0) + (if (n.uuid.toString().contains("fff1", true)) 8 else 0)
                    if (score > bestScore) { bestScore = score; bestWrite = w; bestNotify = n }
                }
            }
            if (bestWrite == null || bestNotify == null) { listener.onState("У OBDII не найдена BLE UART-служба"); return }
            writeCharacteristic = bestWrite; rxCharacteristic = bestNotify
            listener.onState("BLE UART: ${bestWrite.uuid} / ${bestNotify.uuid}")
            g.setCharacteristicNotification(bestNotify, true)
            val descriptor = bestNotify.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(descriptor) } else startElmSession()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) startElmSession() else listener.onState("Ошибка включения уведомлений BLE: $status") }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { consume(characteristic.value?.toString(Charsets.US_ASCII) ?: "") }
    }

    @SuppressLint("MissingPermission")
    private fun startElmSession() {
        thread(name = "elm327-session") {
            for (command in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6")) sendAndWait(command, 3000)
            readTroubleCodes()
            running = true; commandIndex = 0; values.speedPidPresent = false; values.speed = null
            listener.onState("ELM327 ${selectedDevice?.address ?: ""} готов; OBD опрашивается"); pollLoop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(command: String) {
        val c = writeCharacteristic ?: return
        c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = (command + "\r").toByteArray(Charsets.US_ASCII); gatt?.writeCharacteristic(c)
    }

    private fun sendAndWait(command: String, timeoutMs: Long): String {
        synchronized(responseLock) {
            responseText = StringBuilder(); promptReceived = false; rxBuffer.clear(); send(command)
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (!promptReceived && SystemClock.uptimeMillis() < deadline) try { responseLock.wait(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            return responseText.toString()
        }
    }

    private fun consume(text: String) { synchronized(responseLock) { rxBuffer.append(text); responseText.append(text); if (rxBuffer.contains('>')) { promptReceived = true; responseLock.notifyAll(); rxBuffer.clear() } } }

    private fun parseResponse(response: String, pid: Int): Boolean {
        val hex = response.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), ""); val marker = "41" + "%02X".format(Locale.US, pid); val start = hex.indexOf(marker); if (start < 0) return false
        val data = hex.substring(start + marker.length); fun b(i: Int): Int? = if (data.length >= i + 2) data.substring(i, i + 2).toIntOrNull(16) else null
        when (pid) {
            0x0B -> { val a=b(0) ?: return false; values.map=a.toDouble() }
            0x0C -> { val a=b(0) ?: return false; val c=b(2) ?: return false; values.rpm=(a*256+c)/4.0 }
            0x0D -> { val a=b(0) ?: return false; values.speed=a.toDouble(); values.speedPidPresent=true }
            0x04 -> { val a=b(0) ?: return false; values.load=a*100.0/255.0 }
            0x11 -> { val a=b(0) ?: return false; values.throttle=a*100.0/255.0 }
            0x05 -> { val a=b(0) ?: return false; values.coolant=a-40.0 }
            0x0F -> { val a=b(0) ?: return false; values.intake=a-40.0 }
            0x42 -> { val a=b(0) ?: return false; val c=b(2) ?: return false; values.voltage=(a*256+c)/1000.0 }
            else -> return false
        }; return true
    }

    @SuppressLint("MissingPermission")
    private fun readTroubleCodes() {
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        sendAndWait("0100", 3000)
        var raw = ""
        var codes: List<String> = emptyList()
        for (attempt in 0 until 2) {
            raw = sendAndWait("03", 6000)
            codes = parseDtc(raw, 0x03)
            if (codes.isNotEmpty() || raw.uppercase(Locale.US).contains("43")) break
        }
        val rawLine = "# raw 03: " + raw.replace("\r", " ").replace("\n", " | ").trim()
        listener.onErrors(codes, rawLine)
    }

    private fun parseDtc(response: String, mode: Int): List<String> {
        val marker = "%02X".format(Locale.US, mode + 0x40)
        val hex = response.lineSequence()
            .joinToString("") { it.replace(Regex("^[0-9A-Fa-f]:"), "") }
            .uppercase(Locale.US)
            .replace(Regex("[^0-9A-F]"), "")
        val out = LinkedHashSet<String>()
        var pos = 0
        while (pos < hex.length) {
            val idx = hex.indexOf(marker, pos)
            if (idx < 0 || idx + marker.length > hex.length) break
            val rest = hex.substring(idx + marker.length)
            var consumed = 0
            if ((rest.length / 2) % 2 == 1) {
                val count = rest.substring(0, 2).toIntOrNull(16)
                if (count != null && rest.length >= 2 + count * 4) {
                    var i = 2
                    var n = 0
                    while (n < count && i + 4 <= rest.length) {
                        val b1 = rest.substring(i, i + 2).toIntOrNull(16) ?: break
                        val b2 = rest.substring(i + 2, i + 4).toIntOrNull(16) ?: break
                        if (b1 != 0 || b2 != 0) out += decodeDtc(b1, b2)
                        i += 4; n++
                    }
                    consumed = i
                }
            }
            if (consumed == 0) {
                var i = 0
                while (i + 4 <= rest.length) {
                    val b1 = rest.substring(i, i + 2).toIntOrNull(16) ?: break
                    val b2 = rest.substring(i + 2, i + 4).toIntOrNull(16) ?: break
                    if (b1 == 0 && b2 == 0) break
                    out += decodeDtc(b1, b2)
                    i += 4
                }
                consumed = rest.length
            }
            if (consumed == 0) break
            pos = idx + marker.length + consumed
        }
        return out.toList()
    }

    private fun decodeDtc(b1: Int, b2: Int): String {
        val letter = when ((b1 shr 6) and 0x03) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
        val d1 = (b1 shr 4) and 0x03
        val d2 = b1 and 0x0F
        return "$letter$d1$d2%02X".format(Locale.US, b2)
    }

    private fun pollLoop() {
        while (running) {
            val command = commands[commandIndex++ % commands.size]; val pid = command.substring(2).toInt(16)
            if (pid == 0x0D) { values.speedPidPresent = false; values.speed = null }
            val response = sendAndWait(command, 2000)
            if (parseResponse(response, pid)) listener.onData(values.copy(), SystemClock.elapsedRealtimeNanos())
            else if (pid == 0x0D) listener.onData(values.copy(speed = null, speedPidPresent = false), SystemClock.elapsedRealtimeNanos())
            try { Thread.sleep(80) } catch (_: InterruptedException) { break }
        }
    }

    fun close() { running=false; mainHandler.removeCallbacksAndMessages(null); synchronized(responseLock) { promptReceived=true; responseLock.notifyAll(); rxBuffer.clear() }; try { gatt?.close() } catch (_: Exception) {}; gatt=null; writeCharacteristic=null; rxCharacteristic=null }
}
