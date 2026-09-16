package com.example.diagnostictool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class NewMainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var obdValues: TextView
    private lateinit var recordButton: Button
    private lateinit var recordStatus: TextView
    private lateinit var obd: TargetElm327Ble
    private var recorder: PublicSessionRecorder? = null
    private val requestCode = 10

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        obdValues = findViewById(R.id.obdValues)
        recordButton = findViewById(R.id.record)
        recordStatus = findViewById(R.id.recordStatus)

        obd = TargetElm327Ble(this, object : TargetElm327Ble.Listener {
            override fun onState(text: String) = runOnUiThread { status.text = text }
            override fun onData(values: ObdValues, monotonicNs: Long) {
                runOnUiThread { obdValues.text = values.toDisplay() }
                recorder?.onObd(values, monotonicNs)
            }
        })

        findViewById<Button>(R.id.connect).setOnClickListener { requestAndConnect() }
        recordButton.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.openFolder).setOnClickListener { openDownloads() }
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
        } else obd.connect()
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
            val r = PublicSessionRecorder(this) { text -> runOnUiThread { recordStatus.text = text } }
            recorder = r
            r.start()
            recordButton.text = "ОСТАНОВИТЬ ЗАПИСЬ"
        } else {
            recorder?.stop()
            recorder = null
            recordButton.text = "НАЧАТЬ ЗАПИСЬ"
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent("android.intent.action.VIEW_DOWNLOADS"))
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }
    }

    override fun onDestroy() {
        recorder?.stop()
        obd.close()
        super.onDestroy()
    }
}
