package com.example.diagnostictool

import android.content.ContentValues
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

class PublicSessionRecorder(private val activity: NewMainActivity, private val car: Car, private val status: (String) -> Unit) : SensorEventListener, LocationListener {
    private val running = AtomicBoolean(false)
    private var audio: AudioRecord? = null
    private var audioThread: Thread? = null
    private var pcmFile: File? = null
    private var obdWriter: BufferedWriter? = null
    private var sensorsWriter: BufferedWriter? = null
    private var gpsWriter: BufferedWriter? = null
    private var sessionStartNs = 0L
    private var audioStartNs = 0L
    var sessionName: String = ""
        private set
    private val sensorManager = activity.getSystemService(SensorManager::class.java)
    private val locationManager = activity.getSystemService(LocationManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var obdUri: android.net.Uri? = null
    private var sensorsUri: android.net.Uri? = null
    private var gpsUri: android.net.Uri? = null
    @Volatile private var lastGpsSpeedKmh: Double? = null
    @Volatile private var obdActive = false

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
            gpsUri = createPublicFile("gps.csv", "text/csv")
            gpsWriter = writerFor(gpsUri!!)
            gpsWriter!!.write("relative_ms,monotonic_ns,speed_kmh,latitude,longitude,accuracy_m\n")
            gpsWriter!!.flush()
        } catch (e: Exception) { status("Ошибка создания файлов: ${e.message}"); return }
        accelerometer?.let { sensorManager?.registerListener(this, it, 20_000) }
        gyroscope?.let { sensorManager?.registerListener(this, it, 20_000) }
        if (activity.hasLocationPermission()) {
            try { locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this) } catch (_: Exception) {}
            try { locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, this) } catch (_: Exception) {}
        }
        audio = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        try { audio!!.startRecording() } catch (e: Exception) { sensorManager?.unregisterListener(this); stopLocation(); status("Микрофон недоступен: ${e.message}"); return }
        audioStartNs = SystemClock.elapsedRealtimeNanos(); running.set(true); writeMeta(sampleRate)
        status("Запись: $sessionName\n${car.title()}\nOBD: ${if (obdActive) "подключён" else "нет — скорость GPS"}\nПапка: Загрузки/DiagnosticTool/sessions/$sessionName")
        audioThread = thread(name = "audio-recorder") {
            val buffer = ByteArray(minBuffer * 2)
            FileOutputStream(pcmFile!!).use { out -> while (running.get()) { val n = audio?.read(buffer, 0, buffer.size) ?: -1; if (n > 0) out.write(buffer, 0, n) } }
        }
    }

    fun setObdActive(active: Boolean) { obdActive = active }

    @Synchronized fun onObd(v: ObdValues, monotonicNs: Long) {
        if (!running.get()) return
        val relativeMs = (monotonicNs - sessionStartNs) / 1_000_000.0
        obdWriter?.write("%.3f,%d,%s,%s,%s,%s,%s,%s,%s,%s\n".format(Locale.US, relativeMs, monotonicNs, v.rpm?.toString() ?: "", v.speed?.toString() ?: "", v.load?.toString() ?: "", v.throttle?.toString() ?: "", v.map?.toString() ?: "", v.coolant?.toString() ?: "", v.intake?.toString() ?: "", v.voltage?.toString() ?: ""))
        obdWriter?.flush(); obdActive = true
    }

    override fun onLocationChanged(location: Location) {
        if (!running.get()) return
        val speed = if (location.hasSpeed()) (location.speed * 3.6).toDouble() else null
        if (speed != null) lastGpsSpeedKmh = speed
        val t = SystemClock.elapsedRealtimeNanos()
        val relativeMs = (t - sessionStartNs) / 1_000_000.0
        synchronized(this) { gpsWriter?.write("%.3f,%d,%s,%s,%s,%s\n".format(Locale.US, relativeMs, t, speed?.toString() ?: "", location.latitude, location.longitude, location.accuracy)); gpsWriter?.flush() }
    }
    override fun onLocationChanged(locations: MutableList<Location>) { locations.forEach { onLocationChanged(it) } }
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    override fun onFlushComplete(requestCode: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!running.get()) return
        val type = when (event.sensor.type) { Sensor.TYPE_ACCELEROMETER -> "accelerometer"; Sensor.TYPE_GYROSCOPE -> "gyroscope"; else -> return }
        val t = event.timestamp; val relativeMs = (t - sessionStartNs) / 1_000_000.0
        synchronized(this) { sensorsWriter?.write("%.3f,%d,%s,%.7f,%.7f,%.7f\n".format(Locale.US, relativeMs, t, type, event.values[0], event.values[1], event.values[2])); sensorsWriter?.flush() }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun stop() {
        if (!running.getAndSet(false)) return
        sensorManager?.unregisterListener(this); stopLocation()
        try { audioThread?.join(2000) } catch (_: Exception) {}
        try { audio?.stop() } catch (_: Exception) {}
        audio?.release(); audio = null
        synchronized(this) { obdWriter?.close(); obdWriter = null; sensorsWriter?.close(); sensorsWriter = null; gpsWriter?.close(); gpsWriter = null }
        try { obdUri?.let { publish(it) } } catch (_: Exception) {}
        try { sensorsUri?.let { publish(it) } } catch (_: Exception) {}
        try { gpsUri?.let { publish(it) } } catch (_: Exception) {}
        try { writeWavToPublic(pcmFile!!, 48_000, 1, 16) } catch (e: Exception) { status("Ошибка WAV: ${e.message}") }
        try { writeMetaFile() } catch (e: Exception) { status("Ошибка session.json: ${e.message}") }
        pcmFile?.delete(); status("Запись сохранена: Загрузки/DiagnosticTool/sessions/$sessionName")
    }

    private fun stopLocation() { try { locationManager?.removeUpdates(this) } catch (_: Exception) {} }
    private fun writeMeta(sampleRate: Int) { File(pcmFile!!.parentFile, "session.json").writeText(metaJson(sampleRate)) }
    private fun metaJson(sampleRate: Int): String = """{
  "session_start_monotonic_ns": $sessionStartNs,
  "audio_start_monotonic_ns": $audioStartNs,
  "sample_rate_hz": $sampleRate,
  "channels": 1,
  "encoding": "PCM16_LE",
  "timebase": "android_elapsedRealtimeNanos",
  "accelerometer": ${accelerometer != null},
  "gyroscope": ${gyroscope != null},
  "gps_available": ${activity.hasLocationPermission()},
  "speed_source": "${if (obdActive) "OBD_with_GPS_fallback" else "GPS"}",
  "car_id": "${escape(car.id)}",
  "car": ${car.toJson()},
  "storage": "Downloads/DiagnosticTool/sessions/$sessionName"
}
"""
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun writeMetaFile() { val meta = File(pcmFile!!.parentFile, "session.json"); val uri = createPublicFile("session.json", "application/json"); activity.contentResolver.openOutputStream(uri)?.use { it.write(meta.readBytes()) }; publish(uri) }
    private fun writeWavToPublic(raw: File, rate: Int, channels: Int, bits: Int) { val uri = createPublicFile("audio.wav", "audio/wav"); activity.contentResolver.openOutputStream(uri)?.use { out -> writeWavHeader(out, rate, channels, bits, raw.length()); raw.inputStream().use { it.copyTo(out, 64 * 1024) } }; publish(uri) }
    private fun writeWavHeader(out: OutputStream, rate: Int, channels: Int, bits: Int, dataSize: Long) { val h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN); h.put("RIFF".toByteArray()); h.putInt((36+dataSize).toInt()); h.put("WAVE".toByteArray()); h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort()); h.putInt(rate); h.putInt(rate*channels*bits/8); h.putShort((channels*bits/8).toShort()); h.putShort(bits.toShort()); h.put("data".toByteArray()); h.putInt(dataSize.toInt()); out.write(h.array()) }
    private fun writerFor(uri: android.net.Uri): BufferedWriter = (activity.contentResolver.openOutputStream(uri) ?: error("Не удалось открыть $uri")).bufferedWriter()
    private fun createPublicFile(name: String, mime: String): android.net.Uri { if(Build.VERSION.SDK_INT<29){val dir=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"DiagnosticTool/sessions/$sessionName");dir.mkdirs();return android.net.Uri.fromFile(File(dir,name))};val v=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,mime);put(MediaStore.Downloads.RELATIVE_PATH,"Download/DiagnosticTool/sessions/$sessionName");put(MediaStore.Downloads.IS_PENDING,1)};return activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v)?:error("Не удалось создать $name") }
    private fun publish(uri: android.net.Uri){if(Build.VERSION.SDK_INT>=29)activity.contentResolver.update(uri,ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)}
}
