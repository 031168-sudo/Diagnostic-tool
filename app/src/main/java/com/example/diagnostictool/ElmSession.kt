package com.example.diagnostictool

import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import java.util.Locale
import kotlin.concurrent.thread

data class ObdDevice(
    val name: String,
    val address: String,
    val kind: Kind,
    val btDevice: BluetoothDevice? = null
) {
    enum class Kind { BLE, SPP }
    fun title(): String = if (name.isBlank()) "OBDII" else name
    fun label(): String = "${title()}\n$address"
}

interface ObdListener {
    fun onState(text: String)
    fun onData(values: ObdValues, monotonicNs: Long)
    fun onDevices(devices: List<ObdDevice>)
    fun onErrors(codes: List<String>, raw: String)
    fun onLog(line: String)
    fun onScanning(found: Int)
    fun onConnected(connected: Boolean)
}

class ElmSession(
    private val listener: ObdListener,
    private val send: (String) -> Unit
) {
    private val rxBuffer = StringBuilder()
    private val responseLock = Object()
    private var responseText = StringBuilder()
    private var promptReceived = false
    private val values = ObdValues()
    @Volatile private var running = false
    private var commandIndex = 0
    private var pendingDrain = false
    private val commands = listOf("010C", "010D", "0104", "0111", "010B", "0105", "010F", "0142")

    private fun log(line: String) = listener.onLog(line)

    fun start() {
        thread(name = "elm327-session") {
            try {
                listener.onLog("Инициализация ELM327…")
                for (command in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6")) sendAndWait(command, 3000)
                if (!probe()) {
                    log("Нет связи на ATSP6 — включаю авто-подбор протокола")
                    establishProtocol()
                }
                readTroubleCodes()
                running = true; commandIndex = 0; values.speedPidPresent = false; values.speed = null
                listener.onState("ELM327 готов; OBD опрашивается")
                log("Опрос PID: ${commands.joinToString(" ")}")
                pollLoop()
            } catch (t: Throwable) {
                log("Ошибка OBD-сессии: ${t.javaClass.simpleName}: ${t.message}")
                listener.onState("Ошибка OBD: ${t.message}")
            }
        }
    }

    fun stop() {
        running = false
        synchronized(responseLock) { promptReceived = true; responseLock.notifyAll(); rxBuffer.clear() }
    }

    fun feed(text: String) { consume(text) }

    private fun probe(): Boolean {
        val resp = sendAndWait("0100", 5000)
        return hexData(resp).contains("4100")
    }

    private fun establishProtocol() {
        log("Авто-выбор протокола (ATSP0)…")
        sendAndWait("ATSP0", 5000)
        if (probe()) {
            log("Протокол выбран автоматически (ATDPN=${clean(sendAndWait("ATDPN", 3000))})")
            return
        }
        for (p in listOf("6", "7", "5", "3", "1", "2")) {
            sendAndWait("ATSP$p", 3000)
            if (probe()) {
                log("Связь установлена на протоколе SP$p (ATDPN=${clean(sendAndWait("ATDPN", 2000))})")
                return
            }
        }
        log("ЭБУ не отвечает ни на одном протоколе — проверьте зажигание и адаптер")
    }

    private fun clean(s: String): String = s.replace("\r", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim().removeSuffix(">").trim()

    private fun sendAndWait(command: String, timeoutMs: Long): String {
        if (pendingDrain) {
            synchronized(responseLock) {
                try { responseLock.wait(200) } catch (_: InterruptedException) {}
                rxBuffer.clear(); responseText = StringBuilder(); promptReceived = false
            }
            pendingDrain = false
        }
        val start = SystemClock.uptimeMillis()
        val response = synchronized(responseLock) {
            responseText = StringBuilder(); promptReceived = false; rxBuffer.clear()
            try { send(command + "\r") } catch (_: Exception) {}
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (!promptReceived && SystemClock.uptimeMillis() < deadline) try { responseLock.wait(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            responseText.toString()
        }
        if (!promptReceived) pendingDrain = true
        val ms = SystemClock.uptimeMillis() - start
        val out = clean(response)
        listener.onLog("> $command  →  ${if (out.isEmpty()) "(нет ответа)" else out}  [$ms мс]")
        return response
    }

    private fun consume(text: String) { synchronized(responseLock) { rxBuffer.append(text); responseText.append(text); if (rxBuffer.contains('>')) { promptReceived = true; responseLock.notifyAll(); rxBuffer.clear() } } }

    private fun hexData(resp: String): String {
        var s = resp.uppercase(Locale.US)
        for (w in listOf("SEARCHING...", "SEARCHING", "BUS INIT:", "BUS INIT", "UNABLE TO CONNECT", "CAN ERROR", "NO DATA", "STOPPED", "WAITING...", "WAITING", ">")) {
            s = s.replace(w, " ")
        }
        return s.replace(Regex("[^0-9A-F]"), "")
    }

    private fun parseResponse(response: String, pid: Int): Boolean {
        val hex = hexData(response)
        val marker = "%02X".format(Locale.US, pid)
        var start = hex.indexOf("41" + marker)
        if (start < 0) start = hex.indexOf("40" + marker)
        if (start < 0) return false
        val data = hex.substring(start + 4)
        fun b(i: Int): Int? = if (data.length >= i + 2) data.substring(i, i + 2).toIntOrNull(16) else null
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

    private fun readTroubleCodes() {
        try { Thread.sleep(400) } catch (_: InterruptedException) {}
        var raw = ""
        var codes: List<String> = emptyList()
        for (attempt in 0 until 2) {
            raw = sendAndWait("03", 6000)
            codes = parseDtc(raw, 0x03)
            if (codes.isNotEmpty() || raw.uppercase(Locale.US).contains("43")) break
        }
        log("Считано кодов DTC: ${if (codes.isEmpty()) "нет" else codes.joinToString(" ")}")
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
                    if (i > 0 && rest.regionMatches(i, marker, 0, 2, ignoreCase = true)) break
                    val b1 = rest.substring(i, i + 2).toIntOrNull(16) ?: break
                    val b2 = rest.substring(i + 2, i + 4).toIntOrNull(16) ?: break
                    if (b1 == 0 && b2 == 0) break
                    out += decodeDtc(b1, b2)
                    i += 4
                }
                consumed = if (i == 0) rest.length else i
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
}
