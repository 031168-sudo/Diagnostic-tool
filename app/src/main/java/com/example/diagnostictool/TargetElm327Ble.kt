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

/** BLE ELM327 connection locked to the adapter shown in the user's diagnostic app. */
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
    private val values = ObdValues()
    @Volatile private var running = false
    @Volatile private var found = false
    private var commandIndex = 0
    private val commands = listOf("010C", "010D", "0104", "0111", "0110", "0105", "0142")
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connect() {
        close()
        if (!adapter.isEnabled) {
            listener.onState("Bluetooth выключен")
            return
        }

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

            override fun onScanFailed(errorCode: Int) {
                listener.onState("Ошибка BLE scan: $errorCode")
            }
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onState("Ошибка GATT: $status")
                return
            }
            var write: BluetoothGattCharacteristic? = null
            var notify: BluetoothGattCharacteristic? = null
            for (service in g.services) {
                for (c in service.characteristics) {
                    val p = c.properties
                    if (write == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) write = c
                    if (notify == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notify = c
                }
            }
            writeCharacteristic = write
            rxCharacteristic = notify
            if (write == null || notify == null) {
                listener.onState("У OBDII $TARGET_MAC не найдена BLE UART-служба")
                return
            }

            g.setCharacteristicNotification(notify, true)
            val descriptor = notify.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(descriptor)
            } else {
                startElmSession()
            }
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
        thread(name = "elm327-init") {
            send("ATZ"); Thread.sleep(1200)
            send("ATE0"); Thread.sleep(300)
            send("ATL0"); Thread.sleep(300)
            send("ATS0"); Thread.sleep(300)
            send("ATH0"); Thread.sleep(300)
            send("ATSP0"); Thread.sleep(500)
            running = true
            listener.onState("OBDII $TARGET_MAC готов; OBD опрашивается")
            pollLoop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun send(command: String) {
        val c = writeCharacteristic ?: return
        c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = (command + "\r").toByteArray(Charsets.US_ASCII)
        gatt?.writeCharacteristic(c)
    }

    private fun consume(text: String) {
        synchronized(rxBuffer) {
            rxBuffer.append(text)
            var end = -1
            while (true) {
                val s = rxBuffer.toString()
                val r = s.indexOf('\r')
                val p = s.indexOf('>')
                end = when {
                    r >= 0 && p >= 0 -> minOf(r, p)
                    r >= 0 -> r
                    p >= 0 -> p
                    else -> -1
                }
                if (end < 0) return
                val line = s.substring(0, end)
                rxBuffer.delete(0, end + 1)
                parse(line)
            }
        }
    }

    private fun parse(response: String) {
        val x = response.replace(" ", "").replace("\r", "").replace(">", "").uppercase(Locale.US)
        var p = x.indexOf("41")
        while (p >= 0 && p + 4 <= x.length) {
            val payload = x.substring(p + 2)
            val bytes = try {
                (0 until payload.length / 2).map { Integer.parseInt(payload.substring(it * 2, it * 2 + 2), 16) }
            } catch (_: Exception) {
                emptyList()
            }
            if (bytes.isNotEmpty()) {
                when (bytes[0]) {
                    0x0C -> if (bytes.size >= 3) values.rpm = (bytes[1] * 256 + bytes[2]) / 4.0
                    0x0D -> if (bytes.size >= 2) values.speed = bytes[1].toDouble()
                    0x04 -> if (bytes.size >= 2) values.load = bytes[1] * 100.0 / 255.0
                    0x11 -> if (bytes.size >= 2) values.throttle = bytes[1] * 100.0 / 255.0
                    0x10 -> if (bytes.size >= 3) values.maf = (bytes[1] * 256 + bytes[2]) / 100.0
                    0x05 -> if (bytes.size >= 2) values.coolant = bytes[1] - 40.0
                    0x42 -> if (bytes.size >= 3) values.voltage = (bytes[1] * 256 + bytes[2]) / 1000.0
                }
                listener.onData(values.copy(), SystemClock.elapsedRealtimeNanos())
                return
            }
            p = x.indexOf("41", p + 2)
        }
    }

    private fun pollLoop() {
        while (running) {
            send(commands[commandIndex++ % commands.size])
            Thread.sleep(450)
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        rxCharacteristic = null
    }
}
