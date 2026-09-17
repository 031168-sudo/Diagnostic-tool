package com.example.diagnostictool

import android.content.ContentValues
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticChatActivity : AppCompatActivity() {
    private lateinit var stage: TextView
    private lateinit var conversation: TextView
    private lateinit var question: TextView
    private lateinit var answer: EditText
    private lateinit var send: Button
    private lateinit var pdf: Button
    private var diagnosticId = ""
    private var sessionName = "diagnostic"
    private var lastConclusion = ""
    private var lastState = ""
    private val followUp = mutableListOf<Pair<String, String>>()
    private val busy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnosticId = intent.getStringExtra("diagnostic_id") ?: run { finish(); return }
        sessionName = intent.getStringExtra("session_name") ?: "diagnostic"
        buildUi(); poll()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        val title = TextView(this).apply { text = "Alfa Diagnostic — ИИ"; textSize = 24f; setGravity(Gravity.CENTER); setPadding(0, 0, 0, 16) }
        stage = TextView(this).apply { textSize = 18f; setPadding(0, 0, 0, 12) }
        conversation = TextView(this).apply { textSize = 16f; setPadding(0, 8, 0, 16) }
        val scroll = ScrollView(this).apply { addView(conversation); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        question = TextView(this).apply { textSize = 18f; setPadding(0, 8, 0, 8) }
        answer = EditText(this).apply { hint = "Ваш ответ"; minLines = 2; gravity = Gravity.TOP }
        send = Button(this).apply { text = "ОТПРАВИТЬ ОТВЕТ"; isEnabled = false; setOnClickListener { submitAnswer() } }
        pdf = Button(this).apply { text = "СОХРАНИТЬ ЗАКЛЮЧЕНИЕ PDF"; isEnabled = false; setOnClickListener { savePdf(lastConclusion) } }
        root.addView(title); root.addView(stage); root.addView(scroll); root.addView(question); root.addView(answer); root.addView(send); root.addView(pdf)
        setContentView(root)
    }

    private fun poll() {
        DiagnosticApi.status(diagnosticId) { result ->
            runOnUiThread {
                result.onSuccess { s ->
                    lastState = s.state
                    stage.text = when (s.state) {
                        "processing" -> "${s.stage}\n${s.message}"
                        "question" -> "Нужна дополнительная информация"
                        "completed" -> "Диагностика завершена"
                        "error" -> "Ошибка диагностики: ${s.message}"
                        else -> s.message
                    }
                    conversation.text = buildConversation(s)
                    when (s.state) {
                        "question" -> { question.text = s.question; answer.hint = "Ваш ответ"; answer.isEnabled = true; send.isEnabled = true }
                        "completed" -> { question.text = "Можете задать уточняющий вопрос по заключению"; answer.hint = "Ваш вопрос"; answer.isEnabled = true; send.isEnabled = true }
                        else -> { question.text = ""; answer.isEnabled = false; send.isEnabled = false }
                    }
                    if (s.state == "completed" && s.conclusion.isNotBlank()) { lastConclusion = s.conclusion; pdf.isEnabled = true }
                    if (s.state == "processing" || s.state == "question") window.decorView.postDelayed({ poll() }, 2000)
                }.onFailure { e -> stage.text = "Связь с сервером: ${e.message}"; window.decorView.postDelayed({ poll() }, 5000) }
            }
        }
    }

    private fun buildConversation(s: DiagnosticApi.Status): String {
        val b = StringBuilder()
        if (s.state == "processing") b.append("ИИ получает и сопоставляет данные записи.\n\n")
        if (s.question.isNotBlank()) b.append("ИИ: ${s.question}\n")
        if (s.conclusion.isNotBlank()) b.append("\nЗАКЛЮЧЕНИЕ\n${s.conclusion}\n")
        if (followUp.isNotEmpty()) {
            b.append("\nДИАЛОГ\n")
            followUp.forEach { (role, text) -> b.append(if (role == "user") "Вы: $text\n" else "ИИ: $text\n") }
        }
        return b.toString()
    }

    private fun submitAnswer() {
        val text = answer.text.toString().trim()
        if (text.isBlank() || !busy.compareAndSet(false, true)) return
        send.isEnabled = false
        if (lastState == "completed") {
            question.text = "Вопрос отправляется…"
            DiagnosticApi.chat(diagnosticId, text) { result ->
                runOnUiThread {
                    busy.set(false); send.isEnabled = true
                    result.onSuccess { reply ->
                        followUp += "user" to text; followUp += "assistant" to reply
                        answer.setText(""); question.text = "Можете задать уточняющий вопрос по заключению"
                        conversation.text = buildConversation(DiagnosticApi.Status(diagnosticId, lastState, "", "", "", emptyList(), lastConclusion, ""))
                    }.onFailure { e -> question.text = "Ошибка отправки: ${e.message}" }
                }
            }
        } else {
            question.text = "Ответ отправляется…"
            DiagnosticApi.answer(diagnosticId, text) { result ->
                runOnUiThread {
                    busy.set(false)
                    result.onSuccess { answer.setText(""); poll() }
                        .onFailure { e -> question.text = "Ошибка отправки: ${e.message}"; send.isEnabled = true }
                }
            }
        }
    }

    private fun savePdf(text: String) {
        if (text.isBlank()) return
        try {
            val doc = PdfDocument(); val pageWidth = 595; val pageHeight = 842
            var pageNumber = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = android.graphics.Typeface.DEFAULT }
            var y = 48f
            for (line in wrap(text, paint, pageWidth - 72f)) {
                if (y > pageHeight - 48) { doc.finishPage(page); pageNumber++; page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()); y = 48f }
                page.canvas.drawText(line, 36f, y, paint); y += 18f
            }
            doc.finishPage(page)
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "Заключение_$sessionName.pdf"); put(MediaStore.Downloads.MIME_TYPE, "application/pdf"); put(MediaStore.Downloads.RELATIVE_PATH, "Download/DiagnosticTool/conclusions"); put(MediaStore.Downloads.IS_PENDING, 1) }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать PDF")
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: error("Не удалось записать PDF")
            doc.close()
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            DiagnosticRecordStore(this).setAiResponse(sessionName, uri.toString())
            Toast.makeText(this, "PDF сохранён в Downloads/DiagnosticTool/conclusions", Toast.LENGTH_LONG).show()
            pdf.isEnabled = false
        } catch (e: Exception) { Toast.makeText(this, "Ошибка PDF: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        text.replace("\r", "").split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) { result += ""; return@forEach }
            var line = ""
            paragraph.split(" ").forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) line = candidate
                else { if (line.isNotEmpty()) result += line; line = word }
            }
            if (line.isNotEmpty()) result += line
        }
        return result
    }
}
