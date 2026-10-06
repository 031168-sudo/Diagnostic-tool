package com.example.diagnostictool

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import androidx.activity.ComponentActivity
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class SppObd(private val activity: ComponentActivity, private val listener: ObdListener) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        private val OBD_NAME_HINTS = listOf("obd", "elm", "vlink", "v-link", "vgate", "icar", "konnwei", "veepeak", "bafx", "kw902", "autool", "scanner", "obdii")
    }

    private val adapter = activity.getSystemService(BluetoothManager::class.java).adapter
    private var socket: BluetoothSocket? = null
    private var session: ElmSession? = null
    private var reader: Thread? = null
    @Volatile private var connected = false

    private fun log(line: String) = listener.onLog(line)
    private fun looksLikeObd(name: String) = OBD_NAME_HINTS.any { name.lowercase(Locale.US).contains(it) }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<ObdDevice> {
        val list = (adapter?.bondedDevices ?: emptySet()).map { d ->
            val name = try { d.name ?: "" } catch (_: Exception) { "" }
            ObdDevice(name, d.address, ObdDevice.Kind.SPP, d)
        }
        return list.sortedWith(compareBy<ObdDevice> { !looksLikeObd(it.title()) }.thenBy { it.title() })
    }

    @SuppressLint("MissingPermission")
    fun connectAddress(address: String) {
        try { connect(adapter.getRemoteDevice(address)) } catch (e: Exception) {
            listener.onState("Не удалось подключиться: ${e.message}")
            listener.onConnected(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        listener.onState("Подключение (Bluetooth SPP) к ${device.address}...")
        log("SPP подключение к ${device.address} (${device.name ?: "без имени"})")
        thread(name = "spp-connect") {
            try {
                try { adapter?.cancelDiscovery() } catch (_: Exception) {}
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                connected = true
                listener.onConnected(true)
                listener.onState("OBDII ${device.address} подключён (SPP)")
                reader = thread(name = "spp-reader") { readLoop(s) }
                session = ElmSession(listener) { text ->
                    try { s.outputStream.write(text.toByteArray(Charsets.US_ASCII)); s.outputStream.flush() } catch (_: Exception) {}
                }
                session?.start()
            } catch (e: Exception) {
                log("Ошибка SPP: ${e.message}")
                listener.onState("Ошибка подключения SPP: ${e.message}")
                listener.onConnected(false)
                close()
            }
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        val buf = ByteArray(2048)
        try {
            val input = s.inputStream
            while (connected) {
                val n = input.read(buf)
                if (n < 0) break
                session?.feed(String(buf, 0, n, Charsets.US_ASCII))
            }
        } catch (_: Exception) {}
        if (connected) { connected = false; listener.onConnected(false); listener.onState("OBDII отключён") }
    }

    fun disconnect() {
        listener.onState("OBDII отключён")
        listener.onConnected(false)
        listener.onErrors(emptyList(), "")
        close()
    }

    fun close() {
        connected = false
        session?.stop(); session = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
