package com.example.diagnostictool

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Поле даты: можно ввести вручную или выбрать в календаре.
 * Формат значения — дд.мм.гггг.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "Дата",
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    var pickerOpen by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier,
        trailingIcon = {
            IconButton(onClick = { pickerOpen = true }, enabled = enabled) {
                Icon(Icons.Filled.DateRange, contentDescription = "Выбрать дату из календаря")
            }
        }
    )

    if (pickerOpen) {
        val state = rememberDatePickerState(initialSelectedDateMillis = parseDateToUtcMillis(value))
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onValueChange(formatUtcMillis(it)) }
                    pickerOpen = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Отмена") }
            }
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
}

private fun parseDateToUtcMillis(text: String): Long? = try {
    LocalDate.parse(text.trim(), DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
} catch (_: Exception) {
    null
}

private fun formatUtcMillis(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
