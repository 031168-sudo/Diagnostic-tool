package com.example.diagnostictool

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.diagnostictool.ui.theme.DiagnosticTheme
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticChatActivity : ComponentActivity() {
    private var diagnosticId = ""
    private var sessionName = "diagnostic"
    private var lastConclusion = ""
    private var lastDocument = ""
    private var lastState = ""
    private val followUp = mutableListOf<Pair<String, String>>()
    private val busy = AtomicBoolean(false)

    private var recognizer: SpeechRecognizer? = null
    private var voiceBase = ""

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onMicClicked()
        else Toast.makeText(this, "Нет доступа к микрофону", Toast.LENGTH_SHORT).show()
    }

    private var stageText by mutableStateOf("")
    private var conversationText by mutableStateOf("")
    private var questionText by mutableStateOf("")
    private var answerHint by mutableStateOf("Ваш ответ")
    private var answerText by mutableStateOf("")
    private var answerEnabled by mutableStateOf(false)
    private var sendEnabled by mutableStateOf(false)
    private var pdfEnabled by mutableStateOf(false)
    private var listening by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        diagnosticId = intent.getStringExtra("diagnostic_id") ?: run { finish(); return }
        sessionName = intent.getStringExtra("session_name") ?: "diagnostic"
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        }
        setContent {
            DiagnosticTheme {
                ChatScreen(
                    stageText = stageText, conversationText = conversationText, questionText = questionText,
                    answerHint = answerHint, answerText = answerText, answerEnabled = answerEnabled,
                    sendEnabled = sendEnabled, pdfEnabled = pdfEnabled, listening = listening,
                    onAnswerChange = { answerText = it }, onSend = { submitAnswer() }, onMic = { onMicClicked() },
                    onSavePdf = { savePdf(lastDocument, lastConclusion) }, onBack = { finish() }
                )
            }
        }
        poll()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
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
                    if (s.state == "completed" && (s.conclusion.isNotBlank() || s.document.isNotBlank())) {
                        lastConclusion = s.conclusion; lastDocument = s.document; pdfEnabled = true
                    }
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

    private fun onMicClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val r = recognizer
        if (r == null) {
            Toast.makeText(this, "Распознавание речи недоступно", Toast.LENGTH_SHORT).show()
            return
        }
        if (listening) {
            r.stopListening()
            listening = false
            return
        }
        voiceBase = answerText
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
        listening = true
    }

    private fun applyVoice(spoken: String) {
        if (spoken.isBlank()) return
        answerText = if (voiceBase.isBlank()) spoken else voiceBase.trimEnd() + " " + spoken
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { listening = false }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            applyVoice(text.orEmpty())
            voiceBase = answerText
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            applyVoice(text.orEmpty())
        }

        override fun onError(error: Int) {
            listening = false
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал, повторите"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нужна сеть для распознавания"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
                SpeechRecognizer.ERROR_AUDIO -> "Ошибка микрофона"
                else -> "Ошибка распознавания ($error)"
            }
            Toast.makeText(this@DiagnosticChatActivity, message, Toast.LENGTH_SHORT).show()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun savePdf(documentJson: String, fallbackText: String) {
        if (documentJson.isBlank() && fallbackText.isBlank()) return
        try {
            val renderer = ConclusionPdfRenderer()
            val doc = if (documentJson.isNotBlank()) {
                val root = JSONObject(documentJson)
                if (root.optString("title").isNotBlank() || root.has("carRows") || root.optString("complaint").isNotBlank()) renderer.render(root)
                else renderer.renderPlainText(fallbackText)
            } else renderer.renderPlainText(fallbackText)
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, "Заключение_$sessionName.pdf"); put(MediaStore.Downloads.MIME_TYPE, "application/pdf"); put(MediaStore.Downloads.RELATIVE_PATH, "Download/Alfa Diagnostic/conclusions"); put(MediaStore.Downloads.IS_PENDING, 1) }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать PDF")
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: error("Не удалось записать PDF")
            doc.close()
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            DiagnosticRecordStore(this).setAiResponse(sessionName, uri.toString())
            Toast.makeText(this, "PDF сохранён в Download/Alfa Diagnostic/conclusions", Toast.LENGTH_LONG).show()
            pdfEnabled = false
        } catch (e: Exception) { Toast.makeText(this, "Ошибка PDF: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}

@Composable
private fun ChatScreen(
    stageText: String, conversationText: String, questionText: String, answerHint: String,
    answerText: String, answerEnabled: Boolean, sendEnabled: Boolean, pdfEnabled: Boolean, listening: Boolean,
    onAnswerChange: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit, onSavePdf: () -> Unit, onBack: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
            val scrollState = rememberScrollState()
            LaunchedEffect(conversationText, stageText, questionText) {
                snapshotFlow { scrollState.maxValue }.collectLatest { scrollState.animateScrollTo(it) }
            }
            Text("Alfa Diagnostic — ИИ", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            if (stageText.isNotBlank()) Text(stageText, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))
            Text(conversationText, fontSize = 16.sp, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(bottom = 16.dp))
            if (questionText.isNotBlank()) Text(questionText, fontSize = 18.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(value = answerText, onValueChange = onAnswerChange, enabled = answerEnabled, label = { Text(answerHint) }, minLines = 2, modifier = Modifier.weight(1f))
                Button(onClick = onMic, enabled = answerEnabled, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(
                        painter = painterResource(if (listening) R.drawable.ic_stop else R.drawable.ic_mic),
                        contentDescription = if (listening) "Остановить запись" else "Голосовой ввод"
                    )
                }
            }
            Button(onClick = onSend, enabled = sendEnabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("ОТПРАВИТЬ ОТВЕТ") }
            Button(onClick = onSavePdf, enabled = pdfEnabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("СОХРАНИТЬ ЗАКЛЮЧЕНИЕ PDF") }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("НАЗАД") }
        }
    }
}
