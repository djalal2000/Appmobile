package com.bharathvishal.messagecommunicationusingwearabledatalayer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun escapeTag(value: String): String = value.replace("\\", "\\\\")
    .replace(",", "\\,").replace("=", "\\=").replace(" ", "\\ ")
    .replace("\n", " ").replace("\r", " ")

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
    val steps: Int? = null,
    val light: Float? = null,
    val pressure: Float? = null,
    val epochMillis: Long? = null,
    val sessionId: String? = null,
    val sequence: Long? = null,
    val sessionFields: String? = null,
    val watchId: String? = null,
    val magX: Float? = null,
    val magY: Float? = null,
    val magZ: Float? = null
) {

    internal fun toSessionProtocol(): String {
        val fields = sessionFields ?: return ""
        val tags = "person_id=" + escapeTag(person_id ?: "unknown") + ",session_id=" + escapeTag(sessionId ?: "unknown") + (watchId?.let { ",watch_id=" + escapeTag(it) } ?: "")
        return "session_metadata,$tags $fields ${epochMillis ?: parseTimestampToMillis(timestamp)}"
    }

    fun toLineProtocol(): String {
        val timestampMs = epochMillis ?: parseTimestampToMillis(timestamp)
        val safePersonId = escapeTag(person_id ?: "unknown") + (sessionId?.let { ",session_id=" + escapeTag(it) } ?: "") + (watchId?.let { ",watch_id=" + escapeTag(it) } ?: "")
        val safeLabel = escapeTag(label ?: "none")

        val fields = mutableListOf<String>()

        // Acceleration
        acceX?.takeIf { it.isFinite() }?.let { fields.add("acceX=$it") }
        acceY?.takeIf { it.isFinite() }?.let { fields.add("acceY=$it") }
        acceZ?.takeIf { it.isFinite() }?.let { fields.add("acceZ=$it") }
        gyroX?.takeIf { it.isFinite() }?.let { fields.add("gyroX=$it") }
        gyroY?.takeIf { it.isFinite() }?.let { fields.add("gyroY=$it") }
        gyroZ?.takeIf { it.isFinite() }?.let { fields.add("gyroZ=$it") }

        // Gravity
        gravityX?.takeIf { it.isFinite() }?.let { fields.add("gravityX=$it") }
        gravityY?.takeIf { it.isFinite() }?.let { fields.add("gravityY=$it") }
        gravityZ?.takeIf { it.isFinite() }?.let { fields.add("gravityZ=$it") }

        // Linear acceleration
        linAccX?.takeIf { it.isFinite() }?.let { fields.add("linAccX=$it") }
        linAccY?.takeIf { it.isFinite() }?.let { fields.add("linAccY=$it") }
        linAccZ?.takeIf { it.isFinite() }?.let { fields.add("linAccZ=$it") }

        // Rotation vector
        rotX?.takeIf { it.isFinite() }?.let { fields.add("rotX=$it") }
        rotY?.takeIf { it.isFinite() }?.let { fields.add("rotY=$it") }
        rotZ?.takeIf { it.isFinite() }?.let { fields.add("rotZ=$it") }
        rotW?.takeIf { it.isFinite() }?.let { fields.add("rotW=$it") }

        // Magnetic field in microteslas.
        magX?.takeIf { it.isFinite() }?.let { fields.add("magX=$it") }
        magY?.takeIf { it.isFinite() }?.let { fields.add("magY=$it") }
        magZ?.takeIf { it.isFinite() }?.let { fields.add("magZ=$it") }

        if (fields.isEmpty()) return ""
        sequence?.let { fields.add("sequence=${it}i") }

        return "inertial_data,person_id=$safePersonId,label=$safeLabel ${fields.joinToString(",")} $timestampMs"
    }

    fun toLineProtocolActivity(): String {
        val timestampMs = epochMillis ?: parseTimestampToMillis(timestamp)
        val safePersonId = escapeTag(person_id ?: "unknown") + (sessionId?.let { ",session_id=" + escapeTag(it) } ?: "") + (watchId?.let { ",watch_id=" + escapeTag(it) } ?: "")
        val safeLabel = escapeTag(label ?: "none")

        val fields = mutableListOf<String>()
        if (fields.isEmpty()) fields.add("dummy=1")
        sequence?.let { fields.add("sequence=${it}i") }

        return "activity,person_id=$safePersonId,label=$safeLabel ${fields.joinToString(",")} $timestampMs"
    }

    fun toLineProtocolWearable(): String {
        val timestampMs = epochMillis ?: parseTimestampToMillis(timestamp)
        val safePersonId = escapeTag(person_id ?: "unknown") + (sessionId?.let { ",session_id=" + escapeTag(it) } ?: "") + (watchId?.let { ",watch_id=" + escapeTag(it) } ?: "")

        val lines = mutableListOf<String>()

        bpm?.toFloatOrNull()?.takeIf { it.isFinite() }?.let {
            lines.add("heart_rate,person_id=$safePersonId bpm=$it $timestampMs")
        }

        fatigueLevel?.toIntOrNull()?.takeIf { it in 0..5 }?.let {
            lines.add("fatigue_level,person_id=$safePersonId fatigue_level=$it $timestampMs")
        }

        // A missing step-counter reading is not a zero-step measurement.
        if (steps != null && steps >= 0) {
            lines.add("steps,person_id=$safePersonId steps=$steps $timestampMs")
        }

        light?.takeIf { it.isFinite() }?.let { lines.add("light,person_id=$safePersonId lux=$it $timestampMs") }
        pressure?.takeIf { it.isFinite() }?.let { lines.add("pressure,person_id=$safePersonId hPa=$it $timestampMs") }

        return lines.joinToString("\n")
    }

}
