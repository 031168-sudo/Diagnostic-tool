package com.example.diagnostictool

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diagnostictool.ui.theme.DiagGreen
import com.example.diagnostictool.ui.theme.DiagRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

private data class DashPalette(
    val bg: Color,
    val panel: Color,
    val ring: Color,
    val ringDim: Color,
    val needle: Color,
    val text: Color,
    val muted: Color,
    val green: Color,
    val red: Color,
)

private val DarkDash = DashPalette(
    bg = Color(0xFF060A17),
    panel = Color(0xFF0E1930),
    ring = Color(0xFF37C6F0),
    ringDim = Color(0xFF1A4C63),
    needle = Color(0xFFFF2D78),
    text = Color(0xFFE8F4FF),
    muted = Color(0xFF8FA6BC),
    green = DiagGreen,
    red = DiagRed,
)

private val LightDash = DashPalette(
    bg = Color(0xFFF2F5F9),
    panel = Color(0xFFFFFFFF),
    ring = Color(0xFF0E8FB8),
    ringDim = Color(0xFFC2DCE7),
    needle = Color(0xFFD81B60),
    text = Color(0xFF10202E),
    muted = Color(0xFF5B6B7A),
    green = Color(0xFF2E7D32),
    red = Color(0xFFB71800),
)

private val LocalDashPalette = staticCompositionLocalOf { DarkDash }

internal val Amber = Color(0xFFC47A00)
private val ColdBlue = Color(0xFF3E9BD1)
private const val GAUGE_START = 120f
private const val GAUGE_SWEEP = 300f

@Composable
fun DashboardScreen(
    values: ObdValues,
    connected: Boolean,
    darkTheme: Boolean,
    landscape: Boolean,
    tripKm: Double,
    tripSec: Int,
    onToggleOrientation: () -> Unit,
    onResetTrip: () -> Unit,
    onOpenPro: () -> Unit,
    onClose: () -> Unit
) {
    val demo = rememberDemoValues(enabled = DEMO_WITHOUT_OBD && !connected)
    val model = if (demo != null) demo else DashModel(
        speed = values.speed,
        rpm = values.rpm,
        coolant = values.coolant,
        voltage = values.voltage,
        ambient = values.ambient,
        connected = connected
    )
    val clock = rememberClock()
    val palette = if (darkTheme) DarkDash else LightDash

    CompositionLocalProvider(LocalDashPalette provides palette) {
        Surface(color = palette.bg, modifier = Modifier.fillMaxSize()) {
            if (landscape) {
                DashboardLandscape(model, tripKm, tripSec, clock, onToggleOrientation, onResetTrip, onOpenPro, onClose)
            } else {
                DashboardPortrait(model, tripKm, tripSec, clock, onToggleOrientation, onResetTrip, onOpenPro, onClose)
            }
        }
    }
}

private data class DashModel(
    val speed: Double?,
    val rpm: Double?,
    val coolant: Double?,
    val voltage: Double?,
    val ambient: Double?,
    val connected: Boolean,
    val demo: Boolean = false
)

/**
 * ТЕСТОВЫЙ РЕЖИМ для проверки дизайна: пока OBD не подключён, приборная панель
 * показывает имитацию поездки (разгоны, переключения передач, прогрев ОЖ,
 * колебания напряжения и температуры за бортом). При реальном подключении
 * OBD всегда показываются настоящие данные. Для релиза поставить false.
 */
private const val DEMO_WITHOUT_OBD = true

@Composable
private fun rememberDemoValues(enabled: Boolean): DashModel? {
    var t by remember { mutableStateOf(0.0) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val start = System.nanoTime()
        while (true) {
            delay(50)
            t = (System.nanoTime() - start) / 1e9
        }
    }
    if (!enabled) return null
    val twoPi = 2 * Math.PI
    val speed = (65 + 62 * sin(twoPi * t / 45) + 8 * sin(twoPi * t / 7)).coerceAtLeast(0.0)
    val perKmh = when {
        speed < 18 -> 110.0
        speed < 35 -> 62.0
        speed < 55 -> 44.0
        speed < 80 -> 33.0
        else -> 27.0
    }
    val rpm = maxOf(820.0 + 30 * sin(t * 3), speed * perKmh)
    val coolant = if (t < 60) 55 + 37 * t / 60 else 92 + 9 * sin(twoPi * (t - 60) / 90)
    val voltage = 13.6 + 0.8 * sin(twoPi * t / 70)
    val ambient = 7.5 + 22.5 * sin(twoPi * t / 120)
    return DashModel(speed, rpm, coolant, voltage, ambient, connected = true, demo = true)
}

@Composable
private fun rememberClock(): String {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()).format(Date(now))
}

@Composable
private fun DashboardPortrait(
    m: DashModel, tripKm: Double, tripSec: Int, clock: String,
    onToggle: () -> Unit, onReset: () -> Unit, onOpenPro: () -> Unit, onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
        TopBar(m.connected, m.demo, onOpenPro)
        Box(Modifier.weight(1.15f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnalogGauge(
                value = m.speed, minValue = 0.0, maxValue = 220.0,
                unit = "км/ч", majorTicks = 11, minorPerMajor = 4,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                formatTick = { fmt0(it) }
            )
        }
        Box(Modifier.weight(0.85f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnalogGauge(
                value = m.rpm, minValue = 0.0, maxValue = 7000.0,
                unit = "об/мин", majorTicks = 7, minorPerMajor = 4,
                redlineFrom = 6000.0,
                modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                formatTick = { fmt0(it / 1000.0) },
                formatValue = { fmt0(it) }
            )
        }
        Row(
            Modifier.weight(0.62f).fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconCard(
                R.drawable.ic_coolant, m.coolant?.let { fmt0(it) } ?: "—", "°C",
                coolantColor(m.coolant), frac(m.coolant, 40.0, 120.0),
                Modifier.weight(1f).fillMaxHeight()
            )
            IconCard(
                R.drawable.ic_battery, m.voltage?.let { fmt1(it) } ?: "—", "В",
                voltageColor(m.voltage), frac(m.voltage, 11.0, 15.0),
                Modifier.weight(1f).fillMaxHeight()
            )
            IconCard(
                R.drawable.ic_ambient, m.ambient?.let { fmt0(it) } ?: "—", "°C",
                ambientColor(m.ambient), frac(m.ambient, -30.0, 40.0),
                Modifier.weight(1f).fillMaxHeight()
            )
        }
        TripRow(tripKm, tripSec, clock, onReset)
        BottomBar(onToggle, onClose)
    }
}

@Composable
private fun DashboardLandscape(
    m: DashModel, tripKm: Double, tripSec: Int, clock: String,
    onToggle: () -> Unit, onReset: () -> Unit, onOpenPro: () -> Unit, onClose: () -> Unit
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        TopBar(m.connected, m.demo, onOpenPro)
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1.15f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                AnalogGauge(
                    value = m.speed, minValue = 0.0, maxValue = 220.0,
                    unit = "км/ч", majorTicks = 11, minorPerMajor = 4,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    formatTick = { fmt0(it) }
                )
            }
            CenterPanel(m, Modifier.weight(0.78f).fillMaxHeight())
            Box(Modifier.weight(1.15f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                AnalogGauge(
                    value = m.rpm, minValue = 0.0, maxValue = 7000.0,
                    unit = "об/мин", majorTicks = 7, minorPerMajor = 4,
                    redlineFrom = 6000.0,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    formatTick = { fmt0(it / 1000.0) },
                    formatValue = { fmt0(it) }
                )
            }
        }
        TripRow(tripKm, tripSec, clock, onReset)
        BottomBar(onToggle, onClose)
    }
}

@Composable
private fun TopBar(connected: Boolean, demo: Boolean, onOpenPro: () -> Unit) {
    val p = LocalDashPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpenPro, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
            Text("Диагностика →", color = p.ring, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Text(
            when { demo -> "ДЕМО"; connected -> "OBD"; else -> "НЕТ OBD" },
            color = when { demo -> Amber; connected -> p.green; else -> p.red },
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CenterPanel(m: DashModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IconCard(
            R.drawable.ic_coolant, m.coolant?.let { fmt0(it) } ?: "—", "°C",
            coolantColor(m.coolant), frac(m.coolant, 40.0, 120.0),
            Modifier.weight(1f).fillMaxWidth(), compact = true
        )
        IconCard(
            R.drawable.ic_battery, m.voltage?.let { fmt1(it) } ?: "—", "В",
            voltageColor(m.voltage), frac(m.voltage, 11.0, 15.0),
            Modifier.weight(1f).fillMaxWidth(), compact = true
        )
        IconCard(
            R.drawable.ic_ambient, m.ambient?.let { fmt0(it) } ?: "—", "°C",
            ambientColor(m.ambient), frac(m.ambient, -30.0, 40.0),
            Modifier.weight(1f).fillMaxWidth(), compact = true
        )
    }
}

private fun frac(value: Double?, min: Double, max: Double): Float? =
    value?.let { ((it - min) / (max - min)).coerceIn(0.0, 1.0).toFloat() }

/**
 * Карточка параметра в стиле контрольной лампы: иконка «горит» цветом статуса
 * (норма / внимание / авария), крупная цифра — цветом текста, внизу шкала уровня.
 */
@Composable
private fun IconCard(
    iconRes: Int,
    value: String,
    unit: String,
    accent: Color,
    level: Float?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val p = LocalDashPalette.current
    val shape = RoundedCornerShape(16.dp)
    val content: @Composable () -> Unit = {
        Icon(
            painterResource(iconRes), contentDescription = null, tint = accent,
            modifier = Modifier.size(if (compact) 28.dp else 38.dp)
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, color = p.text, fontSize = if (compact) 22.sp else 27.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, textAlign = TextAlign.End,
                style = TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier.width(if (compact) 50.dp else 60.dp)
            )
            Text(
                " $unit", color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.padding(bottom = if (compact) 3.dp else 4.dp)
            )
        }
    }
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.panel, p.bg)))
            .border(1.dp, p.ringDim, shape)
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        } else {
            content()
        }
        LevelBar(level, accent, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LevelBar(level: Float?, color: Color, modifier: Modifier = Modifier) {
    val p = LocalDashPalette.current
    val segments = 10
    val filled = ((level ?: 0f) * segments).roundToInt()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(segments) { i ->
            Box(
                Modifier.weight(1f).height(5.dp)
                    .background(if (i < filled) color else p.ringDim, RoundedCornerShape(1.5.dp))
            )
        }
    }
}

@Composable
private fun TripRow(tripKm: Double, tripSec: Int, clock: String, onReset: () -> Unit) {
    val p = LocalDashPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clock, color = p.muted, fontSize = 10.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            "${fmt1(tripKm)} км  ·  ${fmtTime(tripSec)}  ·  ${if (tripSec > 0) fmt0(tripKm / (tripSec / 3600.0)) else "—"} км/ч",
            color = p.muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
            Text("Сброс", color = p.ring, fontSize = 10.sp)
        }
    }
}

@Composable
private fun BottomBar(onToggle: () -> Unit, onClose: () -> Unit) {
    val p = LocalDashPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onToggle,
            modifier = Modifier.weight(1f).height(38.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.panel, contentColor = p.ring),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) { Text("Сменить вид", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Button(
            onClick = onClose,
            modifier = Modifier.weight(1f).height(38.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.panel, contentColor = p.text),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) { Text("Закрыть", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun AnalogGauge(
    value: Double?,
    minValue: Double,
    maxValue: Double,
    unit: String,
    modifier: Modifier = Modifier,
    majorTicks: Int = 10,
    minorPerMajor: Int = 4,
    redlineFrom: Double? = null,
    formatTick: (Double) -> String = { fmt0(it) },
    formatValue: (Double) -> String = { fmt0(it) }
) {
    val safe = value?.coerceIn(minValue, maxValue)
    val fraction = if (safe == null || maxValue == minValue) 0f else ((safe - minValue) / (maxValue - minValue)).toFloat()
    val p = LocalDashPalette.current

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value)
        val valueSize = (side * 0.20f).coerceIn(18f, 54f).sp
        val unitSize = (side * 0.070f).coerceIn(8f, 14f).sp

        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val R = size.minDimension / 2f

            drawCircle(p.ring.copy(alpha = 0.14f), radius = R * 0.97f, center = center, style = Stroke(R * 0.10f))
            drawCircle(p.ring, radius = R * 0.97f, center = center, style = Stroke(R * 0.026f))

            if (redlineFrom != null && redlineFrom < maxValue) {
                val rf = ((redlineFrom - minValue) / (maxValue - minValue)).toFloat()
                drawArc(
                    color = p.needle,
                    startAngle = GAUGE_START + GAUGE_SWEEP * rf,
                    sweepAngle = GAUGE_SWEEP * (1f - rf),
                    useCenter = false,
                    topLeft = Offset(center.x - R * 0.97f, center.y - R * 0.97f),
                    size = Size(R * 1.94f, R * 1.94f),
                    style = Stroke(width = R * 0.028f, cap = StrokeCap.Round)
                )
            }

            val totalMinor = majorTicks * minorPerMajor
            val tickOuter = R * 0.915f
            for (j in 0..totalMinor) {
                val isMajor = j % minorPerMajor == 0
                val angle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * j / totalMinor).toDouble())
                val len = if (isMajor) R * 0.085f else R * 0.042f
                val outer = Offset(center.x + tickOuter * cos(angle).toFloat(), center.y + tickOuter * sin(angle).toFloat())
                val innerR = tickOuter - len
                val inner = Offset(center.x + innerR * cos(angle).toFloat(), center.y + innerR * sin(angle).toFloat())
                drawLine(
                    color = if (isMajor) p.text.copy(alpha = 0.9f) else p.ringDim,
                    start = inner, end = outer,
                    strokeWidth = if (isMajor) R * 0.014f else R * 0.007f,
                    cap = StrokeCap.Round
                )
            }

            val paint = Paint().apply {
                color = p.text.toArgb()
                textSize = R * 0.135f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            for (i in 0..majorTicks) {
                val angle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * i / majorTicks).toDouble())
                val labelR = R * 0.715f
                val x = center.x + labelR * cos(angle).toFloat()
                val y = center.y + labelR * sin(angle).toFloat() + paint.textSize / 3f
                val tickValue = minValue + (maxValue - minValue) * i / majorTicks
                val text = formatTick(tickValue)
                if (text.isNotBlank()) drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
            }

            if (safe != null) {
                val angle = Math.toRadians((GAUGE_START + GAUGE_SWEEP * fraction).toDouble())
                val innerR = R * 0.80f
                val outerR = R * 0.965f
                val p1 = Offset(center.x + innerR * cos(angle).toFloat(), center.y + innerR * sin(angle).toFloat())
                val p2 = Offset(center.x + outerR * cos(angle).toFloat(), center.y + outerR * sin(angle).toFloat())
                drawLine(p.needle.copy(alpha = 0.30f), p1, p2, strokeWidth = R * 0.075f, cap = StrokeCap.Round)
                drawLine(p.needle, p1, p2, strokeWidth = R * 0.028f, cap = StrokeCap.Round)
            }

            drawCircle(p.ring.copy(alpha = 0.45f), radius = R * 0.60f, center = center, style = Stroke(R * 0.012f))
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (safe == null) "—" else formatValue(safe),
                color = p.text, fontSize = valueSize, fontWeight = FontWeight.Bold, maxLines = 1
            )
            Text(unit, color = p.muted, fontSize = unitSize, maxLines = 1)
        }
    }
}

@Composable
internal fun coolantColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
    value >= 110.0 -> DiagRed
    value >= 100.0 -> Amber
    value < 70.0 -> ColdBlue
    else -> DiagGreen
}

@Composable
internal fun voltageColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
    value < 12.2 || value > 15.0 -> DiagRed
    value < 13.0 || value > 14.8 -> Amber
    else -> DiagGreen
}

@Composable
private fun ambientColor(value: Double?): Color = when {
    value == null -> MaterialTheme.colorScheme.onSurfaceVariant
    value < 0.0 -> ColdBlue
    value < 25.0 -> DiagGreen
    value < 35.0 -> Amber
    else -> DiagRed
}

internal fun fmt0(v: Double): String = String.format(Locale.US, "%.0f", v)
internal fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
private fun fmtTime(sec: Int): String = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
