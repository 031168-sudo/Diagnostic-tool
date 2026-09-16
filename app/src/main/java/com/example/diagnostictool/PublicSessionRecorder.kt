package com.example.diagnostictool

import android.content.ContentValues
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PublicSessionRecorder(
    private val activity: NewMainActivity,
    private val car: Car,
    private val status: (String) -> Unit
) : SensorEventListener {
    private val running = AtomicBoolean(false)
    private var audio: AudioRecord? = null
    private var audioThread: Thread? = null
    private var pcmFile: File? = null
    private var obdWriter: BufferedWriter? = null
    private var sensorsWriter: BufferedWriter? = null
    private var sessionStartNs = 0L
    private var audioStartNs = 0L
    var sessionName: String = ""
        private set
    private val sensorManager = activity.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var obdUri: android.net.Uri? = null
    private var sensorsUri: android.net.Uri? = null

    fun start() {
        val sampleRate = 48_000
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) { status("Микрофон недоступен"); return }

        sessionName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionStartNs = SystemClock.elapsedRealtimeNanos()
        val privateDir = File(activity.cacheDir, "sessions/$sessionName").apply { mkdirs() }
        pcmFile = File(privateDir, "audio.pcm")

        try {
            obdUri = createPublicFile("obd.csv", "text/csv")
            obdWriter = writerFor(obdUri!!)
            obdWriter!!.write("relative_ms,monotonic_ns,rpm,speed_kmh,load_pct,throttle_pct,map_kpa,coolant_c,intake_c,voltage_v\n")
            obdWriter!!.flush()
            sensorsUri = createPublicFile("sensors.csv", "text/csv")
            sensorsWriter = writerFor(sensorsUri!!)
            sensorsWriter!!.write("relative_ms,monotonic_ns,type,x,y,z\n")
            sensorsWriter!!.flush()
        } catch (e: Exception) { status("Ошибка создания файлов: ${e.message}"); return }

        accelerometer?.let { sensorManager?.registerListener(this, it, 20_000) }
        gyroscope?.let { sensorManager?.registerListener(this, it, 20_000) }
        audio = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        try { audio!!.startRecording() } catch (e: Exception) {
            sensorManager?.unregisterListener(this); status("Микрофон недоступен: ${e.message}"); return
        }
        audioStartNs = SystemClock.elapsedRealtimeNanos()
        running.set(true)
        writeMeta(sampleRate)
        status("Запись: $sessionName\n${car.title()}\nПапка: Загрузки/DiagnosticTool/sessions/$sessionName")
        audioThread = thread(name = "audio-recorder") {
            val buffer = ByteArray(minBuffer * 2)
            FileOutputStream(pcmFile!!).use { out ->
                while (running.get()) {
                    val n = audio?.read(buffer, 0, buffer.size) ?: -1
                    if (n > 0) out.write(buffer, 0, n)
                }
            }
        }
    }

    @Synchronized fun onObd(v: ObdValues, monotonicNs: Long) {
        if (!running.get()) return
        val relativeMs = (monotonicNs - sessionStartNs) / 1_000_000.0
        obdWriter?.apply {
            write("%.3f,%d,%s,%s,%s,%s,%s,%s,%s,%s\n".format(Locale.US, relativeMs, monotonicNs,
                v.rpm?.toString() ?: "", v.speed?.toString() ?: "", v.load?.toString() ?: "",
                v.throttle?.toString() ?: "", v.map?.toString() ?: "", v.coolant?.toString() ?: "",
                v.intake?.toString() ?: "", v.voltage?.toString() ?: ""))
            flush()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running.get()) return
        val type = when (event.sensor.type) { Sensor.TYPE_ACCELEROMETER -> "accelerometer"; Sensor.TYPE_GYROSCOPE -> "gyroscope"; else -> return }
        val t = event.timestamp
        val relativeMs = (t - sessionStartNs) / 1_000_000.0
        synchronized(this) { sensorsWriter?.write("%.3f,%d,%s,%.7f,%.7f,%.7f\n".format(Locale.US, relativeMs, t, type, event.values[0], event.values[1], event.values[2])); sensorsWriter?.flush() }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun stop() {
        if (!running.getAndSet(false)) return
        sensorManager?.unregisterListener(this)
        try { audioThread?.join(2000) } catch (_: Exception) {}
        try { audio?.stop() } catch (_: Exception) {}
        audio?.release(); audio = null
        synchronized(this) { obdWriter?.close(); obdWriter = null; sensorsWriter?.close(); sensorsWriter = null }
        try { obdUri?.let { publish(it) } } catch (e: Exception) { status("Ошибка публикации obd.csv: ${e.message}") }
        try { sensorsUri?.let { publish(it) } } catch (e: Exception) { status("Ошибка публикации sensors.csv: ${e.message}") }
        try { writeWavToPublic(pcmFile!!, 48_000, 1, 16) } catch (e: Exception) { status("Ошибка WAV: ${e.message}") }
        try { writeMetaFile() } catch (e: Exception) { status("Ошибка session.json: ${e.message}") }
        pcmFile?.delete()
        status("Запись сохранена: Загрузки/DiagnosticTool/sessions/$sessionName")
    }

    private fun writeMeta(sampleRate: Int) {
        File(pcmFile!!.parentFile, "session.json").writeText(metaJson(sampleRate))
    }

    private fun metaJson(sampleRate: Int): String = """{
  "session_start_monotonic_ns": $sessionStartNs,
  "audio_start_monotonic_ns": $audioStartNs,
  "sample_rate_hz": $sampleRate,
  "channels": 1,
  "encoding": "PCM16_LE",
  "timebase": "android_elapsedRealtimeNanos",
  "accelerometer": ${accelerometer != null},
  "gyroscope": ${gyroscope != null},
  "car_id": "${escape(car.id)}",
  "car": ${car.toJson()},
  "storage": "Downloads/DiagnosticTool/sessions/$sessionName"
}
"""

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun writeMetaFile() {
        val meta = File(pcmFile!!.parentFile, "session.json")
        val uri = createPublicFile("session.json", "application/json")
        activity.contentResolver.openOutputStream(uri)?.use { it.write(meta.readBytes()) }
        publish(uri)
    }
    private fun writeWavToPublic(raw: File, rate: Int, channels: Int, bits: Int) {
        val uri = createPublicFile("audio.wav", "audio/wav")
        activity.contentResolver.openOutputStream(uri)?.use { out -> writeWavHeader(out, rate, channels, bits, raw.length()); raw.inputStream().use { it.copyTo(out, 64 * 1024) } }
        publish(uri)
    }
    private fun writeWavHeader(out: OutputStream, rate: Int, channels: Int, bits: Int, dataSize: Long) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt((36 + dataSize).toInt()); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate); h.putInt(rate * channels * bits / 8); h.putShort((channels * bits / 8).toShort()); h.putShort(bits.toShort()); h.put("data".toByteArray()); h.putInt(dataSize.toInt()); out.write(h.array())
    }
    private fun writerFor(uri: android.net.Uri): BufferedWriter = (activity.contentResolver.openOutputStream(uri) ?: error("Не удалось открыть $uri")).bufferedWriter()
    private fun createPublicFile(name: String, mime: String): android.net.Uri {
        if (Build.VERSION.SDK_INT < 29) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiagnosticTool/sessions/$sessionName"); dir.mkdirs(); return android.net.Uri.fromFile(File(dir, name))
        }
        val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, "Download/DiagnosticTool/sessions/$sessionName"); put(MediaStore.Downloads.IS_PENDING, 1) }
        return activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать $name")
    }
    private fun publish(uri: android.net.Uri) { if (Build.VERSION.SDK_INT >= 29) activity.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
}
