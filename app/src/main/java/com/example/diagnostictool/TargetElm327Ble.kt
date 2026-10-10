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
import androidx.activity.ComponentActivity
import java.util.Locale
import java.util.UUID

class TargetElm327Ble(
    private val activity: ComponentActivity,
    private val listener: ObdListener
) {
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
    private var session: ElmSession? = null
    private var selectedDevice: BluetoothDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun log(line: String) = listener.onLog(line)

    @SuppressLint("MissingPermission")
    fun scan(preferredAddress: String? = null) {
        close()
        if (!adapter.isEnabled) { listener.onState("Bluetooth выключен"); return }
        listener.onState("Поиск OBD-адаптеров рядом...")
        log("Сканирование BLE начато (8 с)")
        listener.onScanning(0)
        val scanner = adapter.bluetoothLeScanner
        val all = LinkedHashMap<String, ObdDevice>()
        val obd = LinkedHashMap<String, ObdDevice>()
        val seen = HashSet<String>()
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: try { device.name ?: "" } catch (_: Exception) { "" }
                val info = ObdDevice(name, device.address, ObdDevice.Kind.BLE, device)
                all[device.address] = info
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }
                val isObd = looksLikeObd(name, uuids) || device.address.equals(preferredAddress, true)
                if (isObd) obd[device.address] = info
                if (seen.add(device.address)) {
                    val uuidStr = uuids?.joinToString(" ") { it.toString() } ?: "-"
                    log("BLE: \"${name.ifBlank { "(без имени)" }}\" ${device.address} rssi=${result.rssi} obd=$isObd uuids=[$uuidStr]")
                }
                listener.onScanning(all.size)
            }
            override fun onScanFailed(errorCode: Int) { listener.onState("Ошибка BLE scan: $errorCode"); log("Ошибка BLE scan: $errorCode") }
        }
        try {
            for (device in adapter.bondedDevices ?: emptySet()) {
                val name = try { device.name ?: "" } catch (_: Exception) { "" }
                val info = ObdDevice(name, device.address, ObdDevice.Kind.BLE, device)
                if (!all.containsKey(device.address)) all[device.address] = info
                if (looksLikeObd(name, null) || device.address.equals(preferredAddress, true)) obd[device.address] = info
            }
        } catch (_: Exception) {}
        scanner.startScan(callback)
        mainHandler.postDelayed({
            scanner.stopScan(callback)
            val primary = if (obd.isNotEmpty()) obd.values.toList() else all.values.toList()
            val list = primary.sortedWith(
                compareBy<ObdDevice> { !it.address.equals(preferredAddress, true) }
                    .thenBy { !looksLikeObd(it.title(), null) }
                    .thenBy { it.title() }
            )
            log("Найдено устройств: всего ${all.size}, похожих на OBD ${obd.size}")
            if (list.isEmpty()) listener.onState("OBD-адаптеры не найдены") else listener.onDevices(list)
        }, 8000)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        session?.stop(); session = null
        listener.onState("OBDII отключён")
        listener.onConnected(false)
        listener.onErrors(emptyList(), "")
        try { gatt?.disconnect() } catch (_: Exception) {}
        close()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close(); selectedDevice = device
        listener.onState("Подключение к ${device.address}...")
        log("Подключение к ${device.address} (${device.name ?: "без имени"})")
        gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun connectAddress(address: String) {
        try { connect(adapter.getRemoteDevice(address)) } catch (e: Exception) {
            listener.onState("Не удалось подключиться: ${e.message}")
            listener.onConnected(false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g; listener.onConnected(true); listener.onState("OBDII ${selectedDevice?.address ?: ""} подключён; поиск GATT..."); log("GATT подключён (status=$status)"); g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                session?.stop(); session = null
                listener.onConnected(false); listener.onState("OBDII отключён"); listener.onErrors(emptyList(), ""); log("GATT отключён (status=$status)"); g.close(); gatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { listener.onState("Ошибка GATT: $status"); log("Ошибка обнаружения служб: $status"); return }
            var bestWrite: BluetoothGattCharacteristic? = null; var bestNotify: BluetoothGattCharacteristic? = null; var bestScore = -1
            for (service in g.services) {
                val writes = service.characteristics.filter { (it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0 }
                val notifies = service.characteristics.filter { (it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0 }
                for (w in writes) for (n in notifies) {
                    val score = (if (w.uuid == n.uuid) 3 else 0) + (if (w.uuid.toString().contains("ffe1", true)) 10 else 0) + (if (n.uuid.toString().contains("ffe1", true)) 10 else 0) + (if (w.uuid.toString().contains("fff1", true)) 8 else 0) + (if (n.uuid.toString().contains("fff1", true)) 8 else 0)
                    if (score > bestScore) { bestScore = score; bestWrite = w; bestNotify = n }
                }
            }
            if (bestWrite == null || bestNotify == null) { listener.onState("У OBDII не найдена BLE UART-служба"); log("BLE UART не найдена"); return }
            writeCharacteristic = bestWrite; rxCharacteristic = bestNotify
            listener.onState("BLE UART: ${bestWrite.uuid} / ${bestNotify.uuid}")
            log("BLE UART службы: write=${bestWrite.uuid} notify=${bestNotify.uuid}")
            g.setCharacteristicNotification(bestNotify, true)
            val descriptor = bestNotify.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(descriptor) } else startSession()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) startSession() else { listener.onState("Ошибка включения уведомлений BLE: $status"); log("Ошибка уведомлений: $status") } }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { session?.feed(characteristic.value?.toString(Charsets.US_ASCII) ?: "") }
    }

    private fun startSession() {
        session?.stop()
        session = ElmSession(listener) { text ->
            val c = writeCharacteristic
            if (c != null) {
                c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = text.toByteArray(Charsets.US_ASCII)
                gatt?.writeCharacteristic(c)
            }
        }
        session?.start()
    }

    fun close() {
        session?.stop(); session = null
        mainHandler.removeCallbacksAndMessages(null)
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; writeCharacteristic = null; rxCharacteristic = null
    }
}
