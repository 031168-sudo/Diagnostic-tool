package com.example.diagnostictool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.diagnostictool.ui.theme.DiagGreen
import com.example.diagnostictool.ui.theme.DiagRed
import java.util.Locale
import kotlin.math.abs

@Composable
fun ProDashboardScreen(
    values: ObdValues,
    connected: Boolean,
    darkTheme: Boolean,
    errorCodes: List<String>,
    reportBusy: Boolean,
    landscape: Boolean,
    onBack: () -> Unit,
    onReport: () -> Unit,
    onClose: () -> Unit
) {
    val tiles = buildList {
        add(ProfTile("STFT B1", d1(values.stft1), "%", trimColor(values.stft1)))
        add(ProfTile("LTFT B1", d1(values.ltft1), "%", trimColor(values.ltft1)))
        add(ProfTile("STFT B2", d1(values.stft2), "%", trimColor(values.stft2)))
        add(ProfTile("LTFT B2", d1(values.ltft2), "%", trimColor(values.ltft2)))
        add(ProfTile("Режим смеси", values.fuelSystem ?: "—"))
        add(ProfTile("MAF", d1(values.maf), "г/с"))
        add(ProfTile("MAP", d0(values.map), "кПа"))
        add(ProfTile("Наддув", boost(values.map, values.baro), "кПа"))
        add(ProfTile("Дроссель", d0(values.throttle), "%"))
        add(ProfTile("Нагрузка", d0(values.load), "%"))
        add(ProfTile("Угол зажиг.", d1(values.timing), "°"))
        add(ProfTile("Крутящий момент", d0(values.torque), "%"))
        add(ProfTile("Лямбда B1S1", d2(values.o2B1S1), "В"))
        add(ProfTile("Лямбда B1S2", d2(values.o2B1S2), "В"))
        add(ProfTile("Катализатор", d0(values.catTemp), "°C"))
        add(ProfTile("Темп. масла", d0(values.oilTemp), "°C"))
        add(ProfTile("Топл. рампа", d0(values.railPressure), "кПа"))
        add(ProfTile("Впуск", d0(values.intake), "°C"))
        add(ProfTile("За бортом", d0(values.ambient), "°C"))
        add(ProfTile("Атмосфера", d0(values.baro), "кПа"))
        add(ProfTile("EGR", d0(values.egr), "%"))
        add(ProfTile("EGR ошибка", d1(values.egrError), "%"))
        add(ProfTile("Напряжение", d1(values.voltage), "В", voltageColor(values.voltage)))
        add(ProfTile("ОЖ", d0(values.coolant), "°C", coolantColor(values.coolant)))
    }

    val columns = if (landscape) 3 else 2

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ДИАГНОСТИКА", color = dashAccent(darkTheme), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    if (connected) "OBD подключен" else "OBD не подключен",
                    color = if (connected) DiagGreen else DiagRed,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(6.dp))
            MilBlock(values.milOn)
            Spacer(Modifier.height(6.dp))
            ErrorsBlock(values.dtcCount, errorCodes)

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                tiles.chunked(columns).forEach { rowTiles ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowTiles.forEach { tile -> ProfTileView(tile, Modifier.weight(1f)) }
                        repeat(columns - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onBackground),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) { Text("← Приборка", fontSize = 12.sp, maxLines = 1) }
                Button(
                    onClick = onReport,
                    enabled = !reportBusy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    if (reportBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("ОТЧЁТ", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onBackground),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) { Text("Закрыть", fontSize = 12.sp, maxLines = 1) }
            }
        }
    }
}

private data class ProfTile(
    val label: String,
    val value: String,
    val unit: String = "",
    val accent: Color? = null
)

@Composable
private fun ProfTileView(tile: ProfTile, modifier: Modifier = Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(tile.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(tile.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tile.accent ?: MaterialTheme.colorScheme.onBackground, maxLines = 1)
            if (tile.unit.isNotBlank()) {
                Text(" ${tile.unit}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
    }
}

@Composable
private fun MilBlock(milOn: Boolean?) {
    val text = when (milOn) {
        true -> "ВКЛ"
        false -> "ВЫКЛ"
        null -> "нет данных"
    }
    val color = when (milOn) {
        true -> DiagRed
        false -> DiagGreen
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Лампа Check Engine:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun ErrorsBlock(dtcCount: Int?, codes: List<String>) {
    val count = if (codes.isNotEmpty()) codes.size else (dtcCount ?: 0)
    val hasErrors = count > 0
    Column(
        Modifier.fillMaxWidth()
            .background(
                if (hasErrors) DiagRed.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            if (hasErrors) "Ошибки: $count" else "Ошибок нет",
            color = if (hasErrors) DiagRed else DiagGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        if (codes.isEmpty() && (dtcCount ?: 0) > 0) {
            Text(
                "ЭБУ сообщает о кодах, но список не получен",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        codes.forEach { code ->
            Text(
                "$code — ${DtcCatalog.describe(code)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun trimColor(v: Double?): Color = when {
    v == null -> MaterialTheme.colorScheme.onSurfaceVariant
    abs(v) < 10.0 -> DiagGreen
    abs(v) < 20.0 -> Amber
    else -> DiagRed
}

private fun boost(map: Double?, baro: Double?): String =
    if (map != null && baro != null) fmt0(map - baro) else "—"

private fun d0(v: Double?): String = v?.let { fmt0(it) } ?: "—"
private fun d1(v: Double?): String = v?.let { fmt1(it) } ?: "—"
private fun d2(v: Double?): String = v?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
