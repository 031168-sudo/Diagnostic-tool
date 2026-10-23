package com.example.diagnostictool

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diagnostictool.ui.theme.DiagBlack
import com.example.diagnostictool.ui.theme.DiagGray
import com.example.diagnostictool.ui.theme.DiagGreen
import com.example.diagnostictool.ui.theme.DiagLightGray
import com.example.diagnostictool.ui.theme.DiagRed
import com.example.diagnostictool.ui.theme.DiagWhite
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal val Amber = Color(0xFFE0A800)
private val ColdBlue = Color(0xFF4FA3D1)
private const val GAUGE_START = 135f
private const val GAUGE_SWEEP = 270f

@Composable
fun DashboardScreen(
    values: ObdValues,
    connected: Boolean,
    landscape: Boolean,
    tripKm: Double,
    tripSec: Int,
    onToggleOrientation: () -> Unit,
    onResetTrip: () -> Unit,
    onOpenPro: () -> Unit,
    onClose: () -> Unit
) {
    val model = DashModel(
        speed = values.speed,
        rpm = values.rpm,
        coolant = values.coolant,
        voltage = values.voltage,
        ambient = values.ambient,
        connected = connected
    )

    Surface(color = DiagBlack, modifier = Modifier.fillMaxSize()) {
        if (landscape) DashboardLandscape(model, tripKm, tripSec, onToggleOrientation, onResetTrip, onOpenPro, onClose)
        else DashboardPortrait(model, tripKm, tripSec, onToggleOrientation, onResetTrip, onOpenPro, onClose)
    }
}

private data class DashModel(
    val speed: Double?,
    val rpm: Double?,
    val coolant: Double?,
    val voltage: Double?,
    val ambient: Double?,
    val connected: Boolean
)

@Composable
private fun DashboardPortrait(
    m: DashModel, tripKm: Double, tripSec: Int,
    onToggle: () -> Unit, onReset: () -> Unit, onOpenPro: () -> Unit, onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        TopBar(m.connected, onOpenPro)
        Box(Modifier.weight(1.1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnalogGauge(
                value = m.speed, minValue = 0.0, maxValue = 220.0,
                label = "Скорость", unit = "км/ч", majorTicks = 11, labelEvery = 2,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                accent = MaterialTheme.colorScheme.primary,
                formatTick = { fmt0(it) }
            )
        }
        Box(Modifier.weight(0.85f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnalogGauge(
                value = m.rpm, minValue = 0.0, maxValue = 7000.0,
                label = "Обороты", unit = "об/мин", majorTicks = 7, redlineFrom = 6000.0,
                modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                accent = MaterialTheme.colorScheme.primary,
                formatTick = { fmt0(it / 1000.0) },
                formatValue = { fmt0(it) }
            )
        }
        Row(
            Modifier.weight(0.9f).fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GaugeCell(Modifier.weight(1.1f).fillMaxHeight()) {
                AnalogGauge(
                    value = m.coolant, minValue = 40.0, maxValue = 120.0,
                    label = "ОЖ", unit = "°C", majorTicks = 4,
                    modifier = Modifier.fillMaxSize(),
                    accent = coolantColor(m.coolant), formatTick = { fmt0(it) }
                )
            }
            BigMetric("Сеть", m.voltage?.let { fmt1(it) } ?: "—", "В", voltageColor(m.voltage), Modifier.weight(1f))
            BigMetric("За бортом", m.ambient?.let { fmt0(it) } ?: "—", "°C", ambientColor(m.ambient), Modifier.weight(1f))
        }
        Spacer(Modifier.weight(0.12f))
        TripRow(tripKm, tripSec, onReset)
        DashboardButtons(onToggle, onClose)
    }
}

@Composable
private fun DashboardLandscape(
    m: DashModel, tripKm: Double, tripSec: Int,
    onToggle: () -> Unit, onReset: () -> Unit, onOpenPro: () -> Unit, onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 14.dp, vertical = 8.dp)) {
        TopBar(m.connected, onOpenPro)
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(0.85f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                AnalogGauge(
                    value = m.rpm, minValue = 0.0, maxValue = 7000.0,
                    label = "Обороты", unit = "об/мин", majorTicks = 7, redlineFrom = 6000.0,
                    modifier = Modifier.fillMaxSize(),
                    accent = MaterialTheme.colorScheme.primary,
                    formatTick = { fmt0(it / 1000.0) },
                    formatValue = { fmt0(it) }
                )
            }
            Box(Modifier.weight(1.25f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                AnalogGauge(
                    value = m.speed, minValue = 0.0, maxValue = 220.0,
                    label = "Скорость", unit = "км/ч", majorTicks = 11, labelEvery = 2,
                    modifier = Modifier.fillMaxSize(),
                    accent = MaterialTheme.colorScheme.primary,
                    formatTick = { fmt0(it) }
                )
            }
            Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GaugeCell(Modifier.weight(1f).fillMaxWidth()) {
                    AnalogGauge(
                        value = m.coolant, minValue = 40.0, maxValue = 120.0,
                        label = "ОЖ", unit = "°C", majorTicks = 4,
                        modifier = Modifier.fillMaxSize(),
                        accent = coolantColor(m.coolant), formatTick = { fmt0(it) }
                    )
                }
                Row(Modifier.weight(0.7f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BigMetric("Сеть", m.voltage?.let { fmt1(it) } ?: "—", "В", voltageColor(m.voltage), Modifier.weight(1f))
                    BigMetric("За бортом", m.ambient?.let { fmt0(it) } ?: "—", "°C", ambientColor(m.ambient), Modifier.weight(1f))
                }
            }
        }
        TripRow(tripKm, tripSec, onReset)
        DashboardButtons(onToggle, onClose)
    }
}

@Composable
private fun TopBar(connected: Boolean, onOpenPro: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Приборная панель", color = DiagWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenPro, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("Диагностика →", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            if (connected) "OBD подключён" else "OBD не подключён",
            color = if (connected) DiagGreen else DiagRed,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DashboardButtons(onToggle: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onToggle,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = DiagGray),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) { Text("Сменить вид", fontSize = 13.sp) }
        Button(
            onClick = onClose,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = DiagGray),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) { Text("Закрыть", fontSize = 13.sp) }
    }
}

@Composable
private fun GaugeCell(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(color = DiagBlack, shape = RoundedCornerShape(12.dp), modifier = modifier) { content() }
}

@Composable
private fun BigMetric(label: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = DiagLightGray, maxLines = 1)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        Text(unit, fontSize = 11.sp, color = DiagLightGray, maxLines = 1)
    }
}

@Composable
private fun TripRow(tripKm: Double, tripSec: Int, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Пробег: ${fmt1(tripKm)} км", fontSize = 11.sp, color = DiagLightGray, modifier = Modifier.weight(1f))
        Text("Время: ${fmtTime(tripSec)}", fontSize = 11.sp, color = DiagLightGray, modifier = Modifier.weight(1f))
        Text(
            "Ср.: ${if (tripSec > 0) fmt0(tripKm / (tripSec / 3600.0)) else "—"} км/ч",
            fontSize = 11.sp, color = DiagLightGray, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
            Text("Сброс", fontSize = 11.sp)
        }
    }
}

@Composable
private fun AnalogGauge(
    value: Double?,
    minValue: Double,
    maxValue: Double,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    majorTicks: Int = 8,
    labelEvery: Int = 1,
    redlineFrom: Double? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    formatTick: (Double) -> String = { fmt0(it) },
    formatValue: (Double) -> String = { fmt0(it) }
) {
    val safe = value?.coerceIn(minValue, maxValue)
    val fraction = if (safe == null || maxValue == minValue) 0f else ((safe - minValue) / (maxValue - minValue)).toFloat()

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value)
        val valueSize = (side * 0.24f).coerceIn(18f, 54f).sp
        val unitSize = (side * 0.085f).coerceIn(8f, 15f).sp
        val labelSize = (side * 0.072f).coerceIn(8f, 13f).sp

        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.065f
            val arcSize = Size(size.width - stroke * 2, size.height - stroke * 2)
            val topLeft = Offset(stroke, stroke)
            val center = Offset(size.width / 2, size.height / 2)
            val radius = size.minDimension / 2f - stroke

            drawArc(
                color = DiagGray, startAngle = GAUGE_START, sweepAngle = GAUGE_SWEEP,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (redlineFrom != null && redlineFrom < maxValue) {
                val rf = ((redlineFrom - minValue) / (maxValue - minValue)).toFloat()
                drawArc(
                    color = DiagRed, startAngle = GAUGE_START + GAUGE_SWEEP * rf,
                    sweepAngle = GAUGE_SWEEP * (1f - rf), useCenter = false,
                    topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt)
                )
            }
            if (fraction > 0f) {
                drawArc(
                    color = accent, startAngle = GAUGE_START, sweepAngle = GAUGE_SWEEP * fraction,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            for (i in 0..majorTicks) {
                val angle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * i / majorTicks).toDouble())
                val outer = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
                val innerR = radius - stroke * 1.8f
                val inner = Offset(center.x + innerR * cos(angle).toFloat(), center.y + innerR * sin(angle).toFloat())
                drawLine(DiagLightGray, inner, outer, strokeWidth = stroke * 0.35f, cap = StrokeCap.Round)
            }

            val paint = Paint().apply {
                color = android.graphics.Color.parseColor("#B8B8B8")
                textSize = size.minDimension * 0.062f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            for (i in 0..majorTicks) {
                if (i % labelEvery != 0 && i != majorTicks) continue
                val angle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * i / majorTicks).toDouble())
                val labelR = radius - stroke * 3.2f
                val x = center.x + labelR * cos(angle).toFloat()
                val y = center.y + labelR * sin(angle).toFloat() + paint.textSize / 3f
                val tickValue = minValue + (maxValue - minValue) * i / majorTicks
                val text = formatTick(tickValue)
                if (text.isNotBlank()) drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
            }

            if (safe != null) {
                val angle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * fraction).toDouble())
                val innerR = radius * 0.58f
                val outerR = radius * 0.92f
                val inner = Offset(center.x + innerR * cos(angle).toFloat(), center.y + innerR * sin(angle).toFloat())
                val outer = Offset(center.x + outerR * cos(angle).toFloat(), center.y + outerR * sin(angle).toFloat())
                drawLine(accent, inner, outer, strokeWidth = stroke * 0.72f, cap = StrokeCap.Round)
                drawCircle(accent, radius = stroke * 0.55f, center = inner)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (safe == null) "—" else formatValue(safe),
                color = DiagWhite, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1
            )
            Text(unit, color = DiagLightGray, fontSize = unitSize, maxLines = 1)
        }
        Text(
            label,
            color = DiagLightGray,
            fontSize = labelSize,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp)
        )
    }
}

internal fun coolantColor(value: Double?): Color = when {
    value == null -> DiagGray
    value >= 110.0 -> DiagRed
    value >= 100.0 -> Amber
    value < 70.0 -> ColdBlue
    else -> DiagGreen
}

internal fun voltageColor(value: Double?): Color = when {
    value == null -> DiagLightGray
    value < 12.2 || value > 15.0 -> DiagRed
    value < 13.0 || value > 14.8 -> Amber
    else -> DiagGreen
}

private fun ambientColor(value: Double?): Color = when {
    value == null -> DiagLightGray
    value < 0.0 -> ColdBlue
    value < 25.0 -> DiagGreen
    value < 35.0 -> Amber
    else -> DiagRed
}

internal fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
internal fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmtTime(sec: Int): String = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
