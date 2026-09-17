package com.example.diagnostictool

import android.content.ContentValues
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diagnostictool.ui.theme.DiagnosticTheme
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticChatActivity : ComponentActivity() {
    private var diagnosticId = ""
    private var sessionName = "diagnostic"
    private var lastConclusion = ""
    private var lastState = ""
    private val followUp = mutableListOf<Pair<String, String>>()
    private val busy = AtomicBoolean(false)

    private var stageText by mutableStateOf("")
    private var conversationText by mutableStateOf("")
    private var questionText by mutableStateOf("")
    private var answerHint by mutableStateOf("Ваш ответ")
    private var answerText by mutableStateOf("")
    private var answerEnabled by mutableStateOf(false)
    private var sendEnabled by mutableStateOf(false)
    private var pdfEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        diagnosticId = intent.getStringExtra("diagnostic_id") ?: run { finish(); return }
        sessionName = intent.getStringExtra("session_name") ?: "diagnostic"
        setContent {
            DiagnosticTheme {
                ChatScreen(
                    stageText = stageText, conversationText = conversationText, questionText = questionText,
                    answerHint = answerHint, answerText = answerText, answerEnabled = answerEnabled,
                    sendEnabled = sendEnabled, pdfEnabled = pdfEnabled,
                    onAnswerChange = { answerText = it }, onSend = { submitAnswer() }, onSavePdf = { savePdf(lastConclusion) }
                )
            }
        }
        poll()
    }

    private fun poll() {
        DiagnosticApi.status(diagnosticId) { result ->
            runOnUiThread {
                result.onSuccess { s ->
                    lastState = s.state
                    stageText = when (s.state) {
                        "processing" -> "${s.stage}\n${s.message}"
                        "question" -> "Нужна дополнительная информация"
                        "completed" -> "Диагностика завершена"
                        "error" -> "Ошибка диагностики: ${s.message}"
                        else -> s.message
                    }
                    conversationText = buildConversation(s)
                    when (s.state) {
                        "question" -> { questionText = s.question; answerHint = "Ваш ответ"; answerEnabled = true; sendEnabled = true }
                        "completed" -> { questionText = "Можете задать уточняющий вопрос по заключению"; answerHint = "Ваш вопрос"; answerEnabled = true; sendEnabled = true }
                        else -> { questionText = ""; answerEnabled = false; sendEnabled = false }
                    }
                    if (s.state == "completed" && s.conclusion.isNotBlank()) { lastConclusion = s.conclusion; pdfEnabled = true }
                    if (s.state == "processing" || s.state == "question") window.decorView.postDelayed({ poll() }, 2000)
                }.onFailure { e -> stageText = "Связь с сервером: ${e.message}"; window.decorView.postDelayed({ poll() }, 5000) }
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
        val text = answerText.trim()
        if (text.isBlank() || !busy.compareAndSet(false, true)) return
        sendEnabled = false
        if (lastState == "completed") {
            questionText = "Вопрос отправляется…"
            DiagnosticApi.chat(diagnosticId, text) { result ->
                runOnUiThread {
                    busy.set(false); sendEnabled = true
                    result.onSuccess { reply ->
                        followUp += "user" to text; followUp += "assistant" to reply
                        answerText = ""; questionText = "Можете задать уточняющий вопрос по заключению"
                        conversationText = buildConversation(DiagnosticApi.Status(diagnosticId, lastState, "", "", "", emptyList(), lastConclusion, ""))
                    }.onFailure { e -> questionText = "Ошибка отправки: ${e.message}" }
                }
            }
        } else {
            questionText = "Ответ отправляется…"
            DiagnosticApi.answer(diagnosticId, text) { result ->
                runOnUiThread {
                    busy.set(false)
                    result.onSuccess { answerText = ""; poll() }
                        .onFailure { e -> questionText = "Ошибка отправки: ${e.message}"; sendEnabled = true }
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
            pdfEnabled = false
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

@Composable
private fun ChatScreen(
    stageText: String, conversationText: String, questionText: String, answerHint: String,
    answerText: String, answerEnabled: Boolean, sendEnabled: Boolean, pdfEnabled: Boolean,
    onAnswerChange: (String) -> Unit, onSend: () -> Unit, onSavePdf: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
            Text("Alfa Diagnostic — ИИ", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            if (stageText.isNotBlank()) Text(stageText, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            Text(conversationText, fontSize = 16.sp, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp))
            if (questionText.isNotBlank()) Text(questionText, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(value = answerText, onValueChange = onAnswerChange, enabled = answerEnabled, label = { Text(answerHint) }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSend, enabled = sendEnabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("ОТПРАВИТЬ ОТВЕТ") }
            Button(onClick = onSavePdf, enabled = pdfEnabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("СОХРАНИТЬ ЗАКЛЮЧЕНИЕ PDF") }
        }
    }
}
