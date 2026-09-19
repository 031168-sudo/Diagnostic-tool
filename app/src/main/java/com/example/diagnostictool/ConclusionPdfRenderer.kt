package com.example.diagnostictool

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject

class ConclusionPdfRenderer(private val pageWidth: Int = 595, private val pageHeight: Int = 842) {
    private val margin = 48f
    private val contentWidth = pageWidth - margin * 2
    private val gridColor = Color.rgb(210, 210, 210)

    private val document = PdfDocument()
    private var pageNo = 0
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = margin
    private var sectionNo = 0

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Color.rgb(20, 20, 20)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.03f
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Color.rgb(90, 90, 90)
        textAlign = Paint.Align.CENTER
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.5f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Color.rgb(15, 15, 15)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Color.rgb(30, 30, 30)
    }
    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Color.rgb(30, 30, 30)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Color.rgb(40, 40, 40)
    }
    private val gridPaint = Paint().apply {
        color = gridColor
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
    }

    private val bodyLine = lineHeight(bodyPaint)
    private val tableLine = lineHeight(tablePaint)

    fun render(root: JSONObject): PdfDocument {
        newPage()
        val title = root.optString("title").ifBlank { "ДИАГНОСТИЧЕСКОЕ ЗАКЛЮЧЕНИЕ" }
        val subtitle = root.optString("subtitle")
        centered(title, titlePaint)
        y += 6f
        if (subtitle.isNotBlank()) {
            centered(subtitle, subtitlePaint)
            y += 4f
        }
        val dateTime = root.optString("dateTime")
        val duration = root.optString("duration")
        val meta = listOfNotNull(
            dateTime.takeIf { it.isNotBlank() },
            duration.takeIf { it.isNotBlank() }?.let { "длительность $it" }
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            centered(meta, subtitlePaint)
            y += 4f
        }
        y += 10f

        val complaint = root.optString("complaint")
        if (complaint.isNotBlank()) {
            numbered("Жалоба клиента")
            paragraph(complaint, bodyPaint, bodyLine)
        }

        val carRows = rows(root.optJSONArray("carRows"))
        if (carRows.isNotEmpty()) {
            numbered("Автомобиль")
            table(carRows)
        }

        val dataIntro = root.optString("dataIntro")
        val fileRows = rows(root.optJSONArray("fileRows"))
        val dataRanges = root.optString("dataRanges")
        if (dataIntro.isNotBlank() || fileRows.isNotEmpty() || dataRanges.isNotBlank()) {
            numbered("Полученные данные")
            if (dataIntro.isNotBlank()) paragraph(dataIntro, bodyPaint, bodyLine)
            if (fileRows.isNotEmpty()) table(fileRows)
            if (dataRanges.isNotBlank()) paragraph(dataRanges, bodyPaint, bodyLine)
        }

        val analysis = root.optString("analysis")
        if (analysis.isNotBlank()) {
            numbered("Что анализировалось")
            paragraph(analysis, bodyPaint, bodyLine)
        }

        val results = strings(root.optJSONArray("results"))
        if (results.isNotEmpty()) {
            numbered("Результаты анализа")
            bullets(results)
        }

        val errors = errorItems(root.optJSONArray("errors"))
        val errorsNote = root.optString("errorsNote")
        if (errors.isNotEmpty() || errorsNote.isNotBlank()) {
            numbered("Ошибки")
            if (errorsNote.isNotBlank()) paragraph(errorsNote, bodyPaint, bodyLine)
            errors.forEach { e ->
                val title = when {
                    e.code.isNotBlank() && e.meaning.isNotBlank() -> "${e.code} — ${e.meaning}"
                    e.code.isNotBlank() -> e.code
                    else -> e.meaning
                }
                subheading(title)
                if (e.cause.isNotBlank()) paragraph("Возможная причина: ${e.cause}", bodyPaint, bodyLine)
                if (e.remedy.isNotBlank()) paragraph("Как устранить: ${e.remedy}", bodyPaint, bodyLine)
                y += 2f
            }
        }

        val conclusion = root.optString("conclusion")
        val priority = root.optString("priority")
        if (conclusion.isNotBlank() || priority.isNotBlank()) {
            numbered("Диагностическое заключение")
            if (conclusion.isNotBlank()) paragraph(conclusion, bodyPaint, bodyLine)
            if (priority.isNotBlank()) {
                val text = if (priority.trimStart().startsWith("Приоритет", true)) priority else "Приоритет проверки: $priority"
                paragraph(text, bodyPaint, bodyLine)
            }
        }

        val recommended = root.optString("recommended")
        if (recommended.isNotBlank()) {
            numbered("Рекомендуемая проверка автомобиля")
            paragraph(recommended, bodyPaint, bodyLine)
        }

        val limitation = root.optString("limitation")
        if (limitation.isNotBlank()) {
            section("Ограничение заключения")
            paragraph(limitation, bodyPaint, bodyLine)
        }

        finishPage()
        return document
    }

    fun renderPlainText(text: String): PdfDocument {
        newPage()
        paragraph(text, bodyPaint, bodyLine)
        finishPage()
        return document
    }

    private fun numbered(title: String) {
        sectionNo++
        section("$sectionNo. $title")
    }

    private fun section(title: String) {
        val h = lineHeight(headingPaint)
        ensure(h + 16f)
        y += 14f
        val baseline = y - headingPaint.fontMetrics.ascent
        canvas?.drawText(title, margin, baseline, headingPaint)
        y += h + 7f
    }

    private fun subheading(text: String) {
        val h = lineHeight(subPaint)
        ensure(h + 8f)
        y += 6f
        for (line in wrap(text, subPaint, contentWidth)) {
            ensure(h)
            canvas?.drawText(line, margin, y - subPaint.fontMetrics.ascent, subPaint)
            y += h
        }
        y += 2f
    }

    private fun centered(text: String, paint: Paint) {
        val h = lineHeight(paint)
        ensure(h)
        val baseline = y - paint.fontMetrics.ascent
        canvas?.drawText(text, pageWidth / 2f, baseline, paint)
        y += h
    }

    private fun paragraph(text: String, paint: Paint, lineH: Float) {
        val fm = paint.fontMetrics
        for (line in wrap(text.trim(), paint, contentWidth)) {
            ensure(lineH)
            canvas?.drawText(line, margin, y - fm.ascent, paint)
            y += lineH
        }
        y += 8f
    }

    private fun bullets(items: List<String>) {
        val indent = 16f
        val fm = bodyPaint.fontMetrics
        for (item in items) {
            val lines = wrap(item.trim(), bodyPaint, contentWidth - indent)
            for ((i, line) in lines.withIndex()) {
                ensure(bodyLine)
                if (i == 0) canvas?.drawText("•", margin + 2f, y - fm.ascent, bodyPaint)
                canvas?.drawText(line, margin + indent, y - fm.ascent, bodyPaint)
                y += bodyLine
            }
            y += 5f
        }
        y += 3f
    }

    private fun table(rows: List<Pair<String, String>>) {
        val labelW = contentWidth * 0.34f
        val valueW = contentWidth - labelW
        val hPad = 8f
        val vPad = 6f
        val fm = tablePaint.fontMetrics
        val left = margin
        val mid = margin + labelW
        val right = margin + contentWidth
        for ((label, value) in rows) {
            val labelLines = wrap(label, tablePaint, labelW - hPad * 2)
            val valueLines = wrap(value, tablePaint, valueW - hPad * 2)
            val lineCount = maxOf(labelLines.size, valueLines.size, 1)
            val rowH = lineCount * tableLine + vPad * 2
            ensure(rowH)
            val top = y
            val bottom = top + rowH
            canvas?.drawRect(left, top, right, bottom, gridPaint)
            canvas?.drawLine(mid, top, mid, bottom, gridPaint)
            var baseline = top + vPad - fm.ascent
            for (line in labelLines) {
                canvas?.drawText(line, left + hPad, baseline, tablePaint)
                baseline += tableLine
            }
            baseline = top + vPad - fm.ascent
            for (line in valueLines) {
                canvas?.drawText(line, mid + hPad, baseline, tablePaint)
                baseline += tableLine
            }
            y = bottom
        }
        y += 10f
    }

    private fun ensure(needed: Float) {
        if (y + needed > pageHeight - margin) {
            finishPage()
            newPage()
        }
    }

    private fun newPage() {
        pageNo++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNo).create())
        canvas = page!!.canvas
        y = margin
    }

    private fun finishPage() {
        page?.let { document.finishPage(it) }
        page = null
        canvas = null
    }

    private fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * 1.32f
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = ArrayList<String>()
        text.replace("\r", "").split("\n").forEach { paragraph ->
            if (paragraph.isBlank()) { result.add(""); return@forEach }
            var line = ""
            paragraph.trim().split(" ").forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (paint.measureText(candidate) <= maxWidth) line = candidate
                else {
                    if (line.isNotEmpty()) result.add(line)
                    line = word
                }
            }
            if (line.isNotEmpty()) result.add(line)
        }
        return result
    }

    private fun rows(a: JSONArray?): List<Pair<String, String>> {
        if (a == null) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val label = o.optString("label")
            val value = o.optString("value")
            if (label.isNotBlank() || value.isNotBlank()) out.add(label to value)
        }
        return out
    }

    private data class ErrItem(val code: String, val meaning: String, val cause: String, val remedy: String)

    private fun errorItems(a: JSONArray?): List<ErrItem> {
        if (a == null) return emptyList()
        val out = ArrayList<ErrItem>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val code = o.optString("code")
            val meaning = o.optString("meaning")
            if (code.isBlank() && meaning.isBlank()) continue
            out.add(ErrItem(code, meaning, o.optString("cause"), o.optString("remedy")))
        }
        return out
    }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val s = a.optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}
