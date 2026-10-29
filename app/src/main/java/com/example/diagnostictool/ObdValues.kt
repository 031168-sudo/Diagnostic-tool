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
    var voltage: Double? = null,
    var maf: Double? = null,
    var ambient: Double? = null,
    var stft1: Double? = null,
    var ltft1: Double? = null,
    var stft2: Double? = null,
    var ltft2: Double? = null,
    var fuelSystem: String? = null,
    var timing: Double? = null,
    var o2B1S1: Double? = null,
    var o2B1S2: Double? = null,
    var baro: Double? = null,
    var railPressure: Double? = null,
    var oilTemp: Double? = null,
    var catTemp: Double? = null,
    var milOn: Boolean? = null,
    var dtcCount: Int? = null,
    var torque: Double? = null,
    var egr: Double? = null,
    var egrError: Double? = null,
    var speedPidPresent: Boolean = false
) {
    fun toDisplay(): String = buildString {
        append("RPM: ").append(rpm?.let { "%.0f".format(Locale.US, it) } ?: "—")
        append("\nSpeed: ").append(speed?.let { "%.0f км/ч".format(Locale.US, it) } ?: "—")
        append("\nLoad: ").append(load?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nThrottle: ").append(throttle?.let { "%.1f %%".format(Locale.US, it) } ?: "—")
        append("\nMAP: ").append(map?.let { "%.0f кПа".format(Locale.US, it) } ?: "—")
        append("\nCoolant: ").append(coolant?.let { "%.0f °C".format(Locale.US, it) } ?: "—")
        append("\nIntake: ").append(intake?.let { "%.0f °C".format(Locale.US, it) } ?: "—")
        append("\nVoltage: ").append(voltage?.let { "%.2f В".format(Locale.US, it) } ?: "—")
    }
}
