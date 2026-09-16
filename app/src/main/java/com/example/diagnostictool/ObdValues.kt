package com.example.diagnostictool

import java.util.Locale

data class ObdValues(
    var rpm: Double? = null,
    var speed: Double? = null,
    var load: Double? = null,
    var throttle: Double? = null,
    var map: Double? = null,
    var coolant: Double? = null,
    var intake: Double? = null,
    var voltage: Double? = null
) {
    fun toDisplay(): String = buildString {
        append("RPM: ").append(rpm?.let { "%.0f".format(Locale.US, it) } ?: "—")
        append("\nSpeed: ").append(speed?.let { "%.0f km/h".format(Locale.US, it) } ?: "—")
        append("\nLoad: ").append(load?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nThrottle: ").append(throttle?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nMAP: ").append(map?.let { "%.0f kPa".format(Locale.US, it) } ?: "—")
        append("\nCoolant: ").append(coolant?.let { "%.0f °C".format(Locale.US, it) } ?: "—")
        append("\nIntake: ").append(intake?.let { "%.0f °C".format(Locale.US, it) } ?: "—")
        append("\nVoltage: ").append(voltage?.let { "%.2f V".format(Locale.US, it) } ?: "—")
    }
}
