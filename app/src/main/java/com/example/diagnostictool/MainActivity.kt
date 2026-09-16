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
    var coolant: Double? = null,
    var voltage: Double? = null
) {
    fun toDisplay(): String = buildString {
        append("RPM: ").append(rpm?.let { "%.0f".format(Locale.US, it) } ?: "—")
        append("\nSpeed: ").append(speed?.let { "%.0f km/h".format(Locale.US, it) } ?: "—")
        append("\nLoad: ").append(load?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nThrottle: ").append(throttle?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nMAF: ").append(maf?.let { "%.1f g/s".format(Locale.US, it) } ?: "—")
        append("\nCoolant: ").append(coolant?.let { "%.0f °C".format(Locale.US, it) } ?: "—")
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
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = (command + "\r").toByteArray(Charsets.US_ASCII)
        gatt?.writeCharacteristic(c)
    }

    private fun consume(text: String) {
        synchronized(rxBuffer) {
            rxBuffer.append(text)
            val all = rxBuffer.toString()
            val parts = all.split('\r', '>')
            rxBuffer.clear()
            if (parts.isNotEmpty() && !all.endsWith('\r') && !all.endsWith('>')) rxBuffer.append(parts.last())
            val completeCount = if (all.endsWith('\r') || all.endsWith('>')) parts.size else parts.size - 1
            for (i in 0 until completeCount.coerceAtLeast(0)) parse(parts[i])
        }
    }

    private fun parse(response: String) {
        val x = response.replace(" ", "").uppercase(Locale.US)
        val p = x.indexOf("41")
        if (p < 0 || x.length < p + 4) return
        val bytes = try {
            (p + 2 until x.length step 2).map { Integer.parseInt(x.substring(it, it + 2), 16) }
        } catch (_: Exception) { return }
        if (bytes.isEmpty()) return
        when (bytes[0]) {
            0x0C -> if (bytes.size >= 3) values.rpm = (bytes[1] * 256 + bytes[2]) / 4.0
            0x0D -> if (bytes.size >= 2) values.speed = bytes[1].toDouble()
            0x04 -> if (bytes.size >= 2) values.load = bytes[1] * 100.0 / 255.0
            0x11 -> if (bytes.size >= 2) values.throttle = bytes[1] * 100.0 / 255.0
            0x10 -> if (bytes.size >= 3) values.maf = (bytes[1] * 256 + bytes[2]) / 100.0
            0x05 -> if (bytes.size >= 2) values.coolant = bytes[1] - 40.0
            0x42 -> if (bytes.size >= 3) values.voltage = (bytes[1] * 256 + bytes[2]) / 1000.0
            else -> return
        }
        listener.onData(values.copy(), SystemClock.elapsedRealtimeNanos())
    }

    private fun pollLoop() {
        while (running) {
            send(commands[commandIndex++ % commands.size])
            Thread.sleep(220)
        }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        running = false
        gatt?.close()
        gatt = null
    }

    companion object {
        private val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

class SessionRecorder(
    private val activity: AppCompatActivity,
    private val status: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var audio: AudioRecord? = null
    private var wav: RandomAccessFile? = null
    private var csv: BufferedWriter? = null
    private var directory: File? = null
    private var audioThread: Thread? = null
    private var sessionStartNs = 0L
    private var audioStartNs = 0L
    private var audioBytes = 0L

    fun start() {
        val sampleRate = 48_000
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { status("Микрофон недоступен"); return }

        val root = activity.getExternalFilesDir(null) ?: activity.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        directory = File(root, "sessions/$stamp").apply { mkdirs() }
        wav = RandomAccessFile(File(directory, "audio.wav"), "rw")
        writeWavHeader(wav!!, sampleRate, 1, 16, 0)
        csv = File(directory, "obd.csv").bufferedWriter()
        csv!!.write("relative_ms,monotonic_ns,rpm,speed_kmh,load_pct,throttle_pct,maf_gps,coolant_c,voltage_v\n")

        sessionStartNs = SystemClock.elapsedRealtimeNanos()
        audio = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        audio!!.startRecording()
        audioStartNs = SystemClock.elapsedRealtimeNanos()
        running.set(true)
        writeSessionMeta(sampleRate)
        status("Запись: ${directory!!.name}")

        audioThread = thread(name = "audio-recorder") {
            val buffer = ByteArray(minBuffer * 2)
            while (running.get()) {
                val n = audio?.read(buffer, 0, buffer.size) ?: -1
                if (n > 0) {
                    synchronized(this) {
                        wav?.write(buffer, 0, n)
                        audioBytes += n
                    }
                }
            }
        }
    }

    @Synchronized
    fun onObd(v: ObdValues, monotonicNs: Long) {
        if (!running.get()) return
        val relativeMs = (monotonicNs - sessionStartNs) / 1_000_000.0
        csv?.apply {
            write("%.3f,%d,%s,%s,%s,%s,%s,%s,%s\n".format(
                Locale.US, relativeMs, monotonicNs,
                v.rpm?.toString() ?: "", v.speed?.toString() ?: "", v.load?.toString() ?: "",
                v.throttle?.toString() ?: "", v.maf?.toString() ?: "", v.coolant?.toString() ?: "", v.voltage?.toString() ?: ""
            ))
            flush()
        }
    }

    fun stop() {
        running.set(false)
        try { audioThread?.join(1500) } catch (_: Exception) {}
        try { audio?.stop() } catch (_: Exception) {}
        audio?.release(); audio = null
        synchronized(this) { csv?.close(); csv = null; wav?.let { finalizeWav(it) }; wav?.close(); wav = null }
        status("Запись сохранена: ${directory?.absolutePath ?: "—"}")
    }

    private fun writeSessionMeta(sampleRate: Int) {
        File(directory, "session.json").writeText(
            """{
  \"session_start_monotonic_ns\": $sessionStartNs,
  \"audio_start_monotonic_ns\": $audioStartNs,
  \"sample_rate_hz\": $sampleRate,
  \"channels\": 1,
  \"encoding\": \"PCM16_LE\",
  \"timebase\": \"android_elapsedRealtimeNanos\"
}
"""
        )
    }

    private fun writeWavHeader(f: RandomAccessFile, rate: Int, channels: Int, bits: Int, dataSize: Long) {
        f.setLength(0)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataSize).toInt()); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
        h.putInt(rate); h.putInt(rate * channels * bits / 8); h.putShort((channels * bits / 8).toShort()); h.putShort(bits.toShort())
        h.put("data".toByteArray()); h.putInt(dataSize.toInt()); f.write(h.array())
    }

    private fun finalizeWav(f: RandomAccessFile) {
        val dataSize = (f.length() - 44).coerceAtLeast(0)
        f.seek(4); f.writeIntLE((36 + dataSize).toInt())
        f.seek(40); f.writeIntLE(dataSize.toInt())
        f.seek(f.length())
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(byteArrayOf(
            (value and 255).toByte(), ((value ushr 8) and 255).toByte(),
            ((value ushr 16) and 255).toByte(), ((value ushr 24) and 255).toByte()
        ))
    }
}
