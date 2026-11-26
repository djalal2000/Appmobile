package com.bharathvishal.messagecommunicationusingwearabledatalayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun parseTimestampToMillis(timeStr: String?): Long {
    if (timeStr == null) return System.currentTimeMillis()
    return try {
        val formatter = SimpleDateFormat("yyyy-MM-dd:HH:mm:ss.SSS", Locale.getDefault())
        val date = formatter.parse(timeStr)
        date?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}

data class Data(
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd:HH:mm:ss.SSS", Locale.getDefault()).format(
        Date()
    ),
    val person_id: String? = null,
    val acceX: Float? = null,
    val acceY: Float? = null,
    val acceZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val gravityX: Float? = null,
    val gravityY: Float? = null,
    val gravityZ: Float? = null,

    val linAccX: Float? = null,
    val linAccY: Float? = null,
    val linAccZ: Float? = null,
    val rotX: Float? = null,
    val rotY: Float? = null,
    val rotZ: Float? = null,
    val rotW: Float? = null,

    val bpm: String? = null,
    val label: String? = null,
    val fatigueLevel: String? = null,
    val steps: Int = 0,
    val light: Float? = null,
    val pressure: Float? = null
) {

    fun toLineProtocol(): String {
        val timestampMs = parseTimestampToMillis(timestamp)
        val safePersonId = (person_id ?: "unknown").replace(" ", "\\ ")
        val safeLabel = (label ?: "none").replace("\"", "").replace(" ", "\\ ")

        val fields = mutableListOf<String>()

        // 🔹 Accélération
        acceX?.let { fields.add("acceX=$it") }
        acceY?.let { fields.add("acceY=$it") }
        acceZ?.let { fields.add("acceZ=$it") }
        gyroX?.let { fields.add("gyroX=$it") }
        gyroY?.let { fields.add("gyroY=$it") }
        gyroZ?.let { fields.add("gyroZ=$it") }

        // 🔹 gravité
        gravityX?.let { fields.add("gravityX=$it") }
        gravityY?.let { fields.add("gravityY=$it") }
        gravityZ?.let { fields.add("gravityZ=$it") }

        // 🔹 Linear Acceleration
        linAccX?.let { fields.add("linAccX=$it") }
        linAccY?.let { fields.add("linAccY=$it") }
        linAccZ?.let { fields.add("linAccZ=$it") }

        // 🔹 Rotation Vector
        rotX?.let { fields.add("rotX=$it") }
        rotY?.let { fields.add("rotY=$it") }
        rotZ?.let { fields.add("rotZ=$it") }
        rotW?.let { fields.add("rotW=$it") }

        if (fields.isEmpty()) return ""

        return "inertial_data,person_id=$safePersonId,label=$safeLabel ${fields.joinToString(",")} $timestampMs"
    }

    fun toLineProtocolActivity(): String {
        val timestampMs = parseTimestampToMillis(timestamp)
        val safePersonId = (person_id ?: "unknown").replace(" ", "\\ ")
        val safeLabel = (label ?: "none").replace("\"", "").replace(" ", "\\ ")

        val fields = mutableListOf<String>()
        if (fields.isEmpty()) fields.add("dummy=1")

        return "activity,person_id=$safePersonId,label=$safeLabel ${fields.joinToString(",")} $timestampMs"
    }

    fun toLineProtocolWearable(): String {
        val timestampMs = parseTimestampToMillis(timestamp)
        val safePersonId = (person_id ?: "unknown").replace(" ", "\\ ")

        val lines = mutableListOf<String>()

        bpm?.toFloatOrNull()?.let {
            lines.add("heart_rate,person_id=$safePersonId bpm=$it $timestampMs")
        }

        fatigueLevel?.toIntOrNull()?.takeIf { it in 0..5 }?.let {
            lines.add("fatigue_level,person_id=$safePersonId fatigue_level=$it $timestampMs")
        }

        if (steps >= 0) {
            lines.add("steps,person_id=$safePersonId steps=$steps $timestampMs")
        }

        light?.let { lines.add("light,person_id=$safePersonId lux=$it $timestampMs") }
        pressure?.let { lines.add("pressure,person_id=$safePersonId hPa=$it $timestampMs") }

        return lines.joinToString("\n")
    }

}
