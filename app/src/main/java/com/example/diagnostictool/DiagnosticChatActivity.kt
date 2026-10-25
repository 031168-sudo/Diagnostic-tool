package com.example.diagnostictool

import android.content.ContentValues
import android.content.res.Configuration
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.diagnostictool.ui.theme.DiagnosticTheme
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticChatActivity : ComponentActivity() {
    private var diagnosticId = ""
    private var sessionName = "diagnostic"
    private var lastConclusion = ""
    private var lastDocument = ""
    private var lastState = ""
    private val busy = AtomicBoolean(false)
    private val recordStore by lazy { DiagnosticRecordStore(this) }

    private var stageText by mutableStateOf("")
    private var chatLines by mutableStateOf<List<ChatLine>>(emptyList())
    private var questionText by mutableStateOf("")
    private var answerHint by mutableStateOf("Ваш ответ")
    private var answerText by mutableStateOf("")
    private var answerEnabled by mutableStateOf(false)
    private var sendEnabled by mutableStateOf(false)
    private var pdfEnabled by mutableStateOf(false)
    private var retryEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val darkTheme = resolveDarkTheme()
        setSystemBarIcons(lightIcons = darkTheme)
        diagnosticId = intent.getStringExtra("diagnostic_id") ?: run { finish(); return }
        sessionName = intent.getStringExtra("session_name") ?: "diagnostic"
        setContent {
            DiagnosticTheme(darkTheme = darkTheme) {
                ChatScreen(
                    stageText = stageText, chatLines = chatLines, questionText = questionText,
                    answerHint = answerHint, answerText = answerText, answerEnabled = answerEnabled,
                    sendEnabled = sendEnabled, pdfEnabled = pdfEnabled, retryEnabled = retryEnabled,
                    onAnswerChange = { answerText = it }, onSend = { submitAnswer() },
                    onSavePdf = { savePdf() }, onRetry = { retryAnalysis() }, onBack = { finish() }
                )
            }
        }
        poll()
    }

    private fun resolveDarkTheme(): Boolean {
        val mode = getSharedPreferences("settings", MODE_PRIVATE).getString("theme_mode", "system")
        return when (mode) {
            "light" -> false
            "dark" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun setSystemBarIcons(lightIcons: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !lightIcons
            isAppearanceLightNavigationBars = !lightIcons
        }
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
                    retryEnabled = s.state == "error"
                    if (s.transcript.isNotEmpty()) {
                        val arr = JSONArray()
                        s.transcript.forEach { (role, text) -> arr.put(JSONObject().put("role", role).put("text", text)) }
                        recordStore.setTranscript(sessionName, arr.toString())
                    }
                    chatLines = buildLines(s)
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

    private fun buildLines(s: DiagnosticApi.Status): List<ChatLine> {
        val list = mutableListOf<ChatLine>()
        if (s.state == "processing") list += ChatLine("ai", "ИИ получает и сопоставляет данные записи…")
        if (s.transcript.isNotEmpty()) {
            s.transcript.forEach { (role, text) ->
                if (text.isNotBlank()) list += ChatLine(if (role == "user") "user" else "ai", text)
            }
        } else {
            val cached = recordStore.transcript(sessionName)
            if (!cached.isNullOrBlank()) {
                val arr = JSONArray(cached)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val text = o.optString("text")
                    if (text.isNotBlank()) list += ChatLine(if (o.optString("role") == "user") "user" else "ai", text)
                }
            } else {
                if (s.question.isNotBlank()) list += ChatLine("ai", s.question)
                if (s.conclusion.isNotBlank()) list += ChatLine("ai", "Заключение:\n${s.conclusion}")
            }
        }
        return list
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
                    result.onSuccess { _ ->
                        answerText = ""; questionText = "Можете задать уточняющий вопрос по заключению"
                        poll()
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

    private fun retryAnalysis() {
        retryEnabled = false
        stageText = "Перезапускаю анализ…"
        DiagnosticApi.retry(diagnosticId) { result ->
            runOnUiThread {
                if (result.isSuccess) poll() else stageText = "Ошибка: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private fun savePdf() {
        Toast.makeText(this, "Формирую заключение с учётом диалога…", Toast.LENGTH_SHORT).show()
        DiagnosticApi.finalize(diagnosticId) { result ->
            runOnUiThread {
                val docJson = result.getOrElse { lastDocument }
                writePdf(docJson.ifBlank { lastDocument }, lastConclusion)
            }
        }
    }

    private fun writePdf(documentJson: String, fallbackText: String) {
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
    stageText: String, chatLines: List<ChatLine>, questionText: String, answerHint: String,
    answerText: String, answerEnabled: Boolean, sendEnabled: Boolean, pdfEnabled: Boolean, retryEnabled: Boolean,
    onAnswerChange: (String) -> Unit, onSend: () -> Unit, onSavePdf: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            val scrollState = rememberScrollState()
            LaunchedEffect(chatLines, stageText, questionText) {
                snapshotFlow { scrollState.maxValue }.collectLatest { scrollState.animateScrollTo(it) }
            }
            Text(
                "Alfa Diagnostic — ИИ",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (stageText.isNotBlank()) {
                    Text(stageText, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
                }
                chatLines.forEach { line ->
                    val isUser = line.role == "user"
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(if (isUser) "Вы" else "ИИ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(line.text, fontSize = 16.sp, color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (questionText.isNotBlank()) {
                Text(
                    questionText,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = answerText,
                            onValueChange = onAnswerChange,
                            enabled = answerEnabled,
                            textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 5,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (answerText.isEmpty()) {
                                        Text(answerHint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = onSend,
                            enabled = sendEnabled,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(painterResource(R.drawable.ic_send), contentDescription = "Отправить")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (retryEnabled) {
                            ComposerButton("Повторить", onRetry, Modifier.weight(1f))
                        }
                        ComposerButton("Сохранить PDF", onSavePdf, Modifier.weight(1f), enabled = pdfEnabled)
                        ComposerButton("Назад", onBack, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1)
    }
}

private data class ChatLine(val role: String, val text: String)
