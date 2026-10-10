package com.example.diagnostictool

import androidx.activity.ComponentActivity
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class WifiObd(private val activity: ComponentActivity, private val listener: ObdListener) {
    private var socket: Socket? = null
    private var session: ElmSession? = null
    private var reader: Thread? = null
    @Volatile private var connected = false

    private fun log(line: String) = listener.onLog(line)

    fun connect(host: String, port: Int = 35000) {
        close()
        listener.onState("Подключение (Wi-Fi) к $host:$port...")
        log("Wi-Fi подключение к $host:$port")
        thread(name = "wifi-connect") {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 8000)
                socket = s
                connected = true
                listener.onConnected(true)
                listener.onState("OBDII $host:$port подключён (Wi-Fi)")
                reader = thread(name = "wifi-reader") { readLoop(s) }
                session = ElmSession(listener) { text ->
                    try { s.getOutputStream().write(text.toByteArray(Charsets.US_ASCII)); s.getOutputStream().flush() } catch (_: Exception) {}
                }
                session?.start()
            } catch (e: Exception) {
                log("Ошибка Wi-Fi: ${e.message}")
                listener.onState("Ошибка подключения Wi-Fi: ${e.message}")
                listener.onConnected(false)
                close()
            }
        }
    }

    private fun readLoop(s: Socket) {
        val buf = ByteArray(2048)
        try {
            val input = s.getInputStream()
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
