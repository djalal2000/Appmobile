package com.bharathvishal.messagecommunicationusingwearabledatalayer

import java.net.URI

internal val SENSOR_KEYS = listOf("accelerometer", "gyroscope", "gravity", "linear", "rotation", "heart", "steps", "light", "pressure", "fatigue")

internal fun validServerUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)

internal fun validSensorSelection(keys: Set<String>): Boolean =
    keys.isNotEmpty() && keys.all { it in SENSOR_KEYS }
