package com.example.diagnostictool

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject

class ConclusionPdfRenderer(private val pageWidth: Int = 595, private val pageHeight: Int = 842) {
    private val margin = 40f
    private val contentWidth = pageWidth - margin * 2
    private val lineHeight = 15f
    private val rowPad = 5f

    private val document = PdfDocument()
    private var pageNo = 0
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = margin
    private var sectionNo = 0

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f
        typeface = Typeface.DEFAULT
        color = Color.DKGRAY
        textAlign = Paint.Align.CENTER
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.BLACK
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f
        typeface = Typeface.DEFAULT
        color = Color.BLACK
    }
    private val linePaint = Paint().apply {
        color = Color.rgb(200, 200, 200)
        strokeWidth = 0.7f
        style = Paint.Style.STROKE
    }

    fun render(root: JSONObject): PdfDocument {
        val title = root.optString("title").ifBlank { "ДИАГНОСТИЧЕСКОЕ ЗАКЛЮЧЕНИЕ" }
        val subtitle = root.optString("subtitle")
        newPage()
        drawCentered(title, titlePaint, 6f)
        if (subtitle.isNotBlank()) drawCentered(subtitle, subtitlePaint, 10f)
        y += 8f

        val complaint = root.optString("complaint")
        if (complaint.isNotBlank()) {
            numbered("Жалоба клиента")
            paragraph(complaint)
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
            if (dataIntro.isNotBlank()) paragraph(dataIntro)
            if (fileRows.isNotEmpty()) table(fileRows)
            if (dataRanges.isNotBlank()) paragraph(dataRanges)
        }

        val analysis = root.optString("analysis")
        if (analysis.isNotBlank()) {
            numbered("Что анализировалось")
            paragraph(analysis)
        }

        val results = strings(root.optJSONArray("results"))
        if (results.isNotEmpty()) {
            numbered("Результаты анализа")
            bullets(results)
        }

        val conclusion = root.optString("conclusion")
        val priority = root.optString("priority")
        if (conclusion.isNotBlank() || priority.isNotBlank()) {
            numbered("Диагностическое заключение")
            if (conclusion.isNotBlank()) paragraph(conclusion)
            if (priority.isNotBlank()) {
                val text = if (priority.trimStart().startsWith("Приоритет", true)) priority else "Приоритет проверки: $priority"
                paragraph(text)
            }
        }

        val recommended = root.optString("recommended")
        if (recommended.isNotBlank()) {
            numbered("Рекомендуемая проверка автомобиля")
            paragraph(recommended)
        }

        val limitation = root.optString("limitation")
        if (limitation.isNotBlank()) {
            section("Ограничение заключения")
            paragraph(limitation)
        }

        finishPage()
        return document
    }

    fun renderPlainText(text: String): PdfDocument {
        newPage()
        for (line in wrap(text.replace("\r", ""), bodyPaint, contentWidth)) {
            ensure(lineHeight)
            draw(line, margin, bodyPaint)
            y += lineHeight
        }
        finishPage()
        return document
    }

    private fun numbered(title: String) {
        sectionNo++
        section("$sectionNo. $title")
    }

    private fun section(title: String) {
        ensure(24f)
        y += 10f
        draw(title, margin, headingPaint)
        y += 20f
    }

    private fun paragraph(text: String) {
        for (line in wrap(text.trim(), bodyPaint, contentWidth)) {
            ensure(lineHeight)
            draw(line, margin, bodyPaint)
            y += lineHeight
        }
        y += 6f
    }

    private fun bullets(items: List<String>) {
        val indent = 14f
        for (item in items) {
            val lines = wrap(item.trim(), bodyPaint, contentWidth - indent)
            for ((i, line) in lines.withIndex()) {
                ensure(lineHeight)
                if (i == 0) draw("•", margin, bodyPaint)
                draw(line, margin + indent, bodyPaint)
                y += lineHeight
            }
            y += 4f
        }
        y += 4f
    }

    private fun table(rows: List<Pair<String, String>>) {
        val labelW = contentWidth * 0.36f
        val valueW = contentWidth - labelW
        for ((label, value) in rows) {
            val labelLines = wrap(label, bodyPaint, labelW - rowPad * 2)
            val valueLines = wrap(value, bodyPaint, valueW - rowPad * 2)
            val rowH = maxOf(labelLines.size, valueLines.size) * lineHeight + rowPad * 2
            ensure(rowH + 1f)
            val top = y
            val bottom = y + rowH
            val left = margin
            val mid = margin + labelW
            val right = margin + contentWidth
            canvas?.drawRect(left, top, right, bottom, linePaint)
            canvas?.drawLine(mid, top, mid, bottom, linePaint)
            var ty = top + rowPad + lineHeight - 3f
            for (line in labelLines) { draw(line, left + rowPad, bodyPaint); ty += lineHeight }
            ty = top + rowPad + lineHeight - 3f
            for (line in valueLines) { draw(line, mid + rowPad, bodyPaint); ty += lineHeight }
            y = bottom
        }
        y += 8f
    }

    private fun drawCentered(text: String, paint: Paint, gap: Float) {
        ensure(20f + gap)
        y += gap
        canvas?.drawText(text, pageWidth / 2f, y, paint)
        y += 18f
    }

    private fun draw(text: String, x: Float, paint: Paint) {
        canvas?.drawText(text, x, y, paint)
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
