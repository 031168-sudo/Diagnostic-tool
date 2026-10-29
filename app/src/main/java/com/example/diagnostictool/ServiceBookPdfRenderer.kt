package com.example.diagnostictool

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ServiceBookPdfRenderer(private val pageWidth: Int = 595, private val pageHeight: Int = 842) {
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
        textSize = 20f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Color.rgb(20, 20, 20)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.03f
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f
        typeface = Typeface.create("serif", Typeface.NORMAL)
        color = Color.rgb(70, 70, 70)
        textAlign = Paint.Align.CENTER
    }
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Color.rgb(15, 15, 15)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11.5f
        typeface = Typeface.create("serif", Typeface.NORMAL)
        color = Color.rgb(30, 30, 30)
    }
    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f
        typeface = Typeface.create("serif", Typeface.NORMAL)
        color = Color.rgb(30, 30, 30)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Color.rgb(20, 20, 20)
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f
        typeface = Typeface.create("serif", Typeface.ITALIC)
        color = Color.rgb(110, 110, 110)
    }
    private val gridPaint = Paint().apply {
        color = gridColor
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
    }
    private val headerBgPaint = Paint().apply {
        color = Color.rgb(242, 242, 242)
        style = Paint.Style.FILL
    }

    private val tableLine = lineHeight(tablePaint)
    private val noteLine = lineHeight(notePaint)

    fun render(
        car: Car,
        meta: ServiceMeta,
        dues: List<ServiceDue>,
        entries: List<ServiceEntry>,
        generatedAt: Long
    ): PdfDocument {
        newPage()

        centered("СЕРВИСНАЯ КНИЖКА", titlePaint)
        y += 6f
        val carTitle = car.title().ifBlank { "Автомобиль" }
        centered(carTitle, subtitlePaint)
        y += 4f
        val generated = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(generatedAt))
        centered("Сформировано $generated", subtitlePaint)
        y += 12f

        numbered("Автомобиль")
        table(carRows(car))

        numbered("Текущее состояние")
        table(
            listOf(
                "Текущий пробег" to "${meta.currentMileage} км",
                "Дата пробега" to meta.currentDate,
                "Интервал ТО" to "${meta.toInterval} км",
                "Интервал замены масла" to "${meta.oilInterval} км",
                "Порог уведомления" to "${meta.notifyThreshold} км"
            )
        )

        numbered("Сроки обслуживания")
        dueTable(dues)

        numbered("Записи")
        if (entries.isEmpty()) {
            paragraph("Записей пока нет.", bodyPaint, tableLine)
        } else {
            entriesTable(entries)
        }

        y += 10f
        paragraph("Сформировано приложением AlfaDiag. Пробег ведётся вручную в сервисной книжке.", notePaint, noteLine)

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
        canvas?.drawText(title, margin, y - headingPaint.fontMetrics.ascent, headingPaint)
        y += h + 7f
    }

    private fun centered(text: String, paint: Paint) {
        val h = lineHeight(paint)
        ensure(h)
        canvas?.drawText(text, pageWidth / 2f, y - paint.fontMetrics.ascent, paint)
        y += h
    }

    private fun paragraph(text: String, paint: Paint, lineH: Float) {
        val fm = paint.fontMetrics
        for (line in wrap(text.trim(), paint, contentWidth)) {
            ensure(lineH)
            canvas?.drawText(line, margin, y - fm.ascent, paint)
            y += lineH
        }
        y += 6f
    }

    private fun table(rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
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
        y += 8f
    }

    private fun dueTable(dues: List<ServiceDue>) {
        if (dues.isEmpty()) return
        val labelW = contentWidth * 0.28f
        val valueW = contentWidth - labelW
        val hPad = 8f
        val vPad = 6f
        val fm = tablePaint.fontMetrics
        val left = margin
        val mid = margin + labelW
        val right = margin + contentWidth
        for (due in dues) {
            val value = when {
                due.overdue -> "ПРОСРОЧЕНО на ${-due.remaining} км · следующее на ${due.nextMileage} км"
                due.dueSoon -> "скоро · осталось ${due.remaining} км · следующее на ${due.nextMileage} км"
                else -> "следующее на ${due.nextMileage} км · осталось ${due.remaining} км"
            }
            val color = when {
                due.overdue -> Color.rgb(183, 24, 0)
                due.dueSoon -> Color.rgb(180, 110, 0)
                else -> Color.rgb(30, 30, 30)
            }
            val valuePaint = Paint(tablePaint).apply { this.color = color }
            val labelLines = wrap(due.kind, tablePaint, labelW - hPad * 2)
            val valueLines = wrap(value, valuePaint, valueW - hPad * 2)
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
                canvas?.drawText(line, mid + hPad, baseline, valuePaint)
                baseline += tableLine
            }
            y = bottom
        }
        y += 8f
    }

    private fun entriesTable(entries: List<ServiceEntry>) {
        val dateW = 60f
        val mileageW = 62f
        val kindW = 96f
        val descW = contentWidth - dateW - mileageW - kindW
        val widths = floatArrayOf(dateW, mileageW, kindW, descW)
        val headers = listOf("Дата", "Пробег", "Категория", "Что сделано / описание")
        val hPad = 5f
        val vPad = 5f

        drawEntriesRow(headers, widths, hPad, vPad, headerPaint, headerBgPaint)
        for (e in entries) {
            val description = listOfNotNull(
                e.title.ifBlank { null },
                e.note.ifBlank { null }
            ).joinToString("\n")
            val values = listOf(
                e.date.ifBlank { "—" },
                e.mileage.ifBlank { "—" }.let { if (it == "—") it else "$it км" },
                e.kind.ifBlank { "—" },
                description.ifBlank { "—" }
            )
            drawEntriesRow(values, widths, hPad, vPad, tablePaint, null)
        }
        y += 8f
    }

    private fun drawEntriesRow(
        values: List<String>,
        widths: FloatArray,
        hPad: Float,
        vPad: Float,
        paint: Paint,
        background: Paint?
    ) {
        val lines = values.mapIndexed { i, value -> wrap(value, paint, widths[i] - hPad * 2) }
        val lineCount = maxOf(lines.maxOfOrNull { it.size } ?: 1, 1)
        val rowH = lineCount * tableLine + vPad * 2
        ensure(rowH)
        val top = y
        val bottom = top + rowH
        val left = margin
        val right = margin + contentWidth
        if (background != null) canvas?.drawRect(left, top, right, bottom, background)
        canvas?.drawRect(left, top, right, bottom, gridPaint)

        var x = left
        for ((i, colWidth) in widths.withIndex()) {
            val nextX = x + colWidth
            if (i > 0) canvas?.drawLine(x, top, x, bottom, gridPaint)
            var baseline = top + vPad - paint.fontMetrics.ascent
            for (line in lines[i]) {
                canvas?.drawText(line, x + hPad, baseline, paint)
                baseline += tableLine
            }
            x = nextX
        }
        y = bottom
    }

    private fun carRows(car: Car): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        fun add(label: String, value: String) {
            if (value.isNotBlank()) rows += label to value.trim()
        }
        add("Марка", car.make)
        add("Модель", car.model)
        add("Год", car.year)
        add("Двигатель", car.engine)
        add("Топливо", car.fuel)
        add("КПП", car.transmission)
        add("Привод", car.drive)
        add("VIN", car.vin)
        add("Пробег в профиле", car.mileage)
        return rows
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
}
