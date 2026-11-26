package com.bharathvishal.messagecommunicationusingwearabledatalayer

internal fun validCommandAck(expectedNode: String, sourceNode: String, result: String): Boolean =
    expectedNode == sourceNode && result in setOf("OK", "REJECTED", "UNKNOWN")

internal fun validInfluxStatus(status: InfluxWriteStatus): Boolean {
    val states = setOf("NONE", "OK", "ERROR")
    return status.activity in states && status.wearable in states &&
        status.pending >= 0 && status.failures >= 0
}

internal enum class InfluxDisplayState { UNAVAILABLE, FAILED, PREVIOUS_FAILURES, OK, PENDING, NO_WRITES }

internal fun influxDisplayState(status: InfluxWriteStatus?): InfluxDisplayState = when {
    status == null -> InfluxDisplayState.UNAVAILABLE
    status.activity == "ERROR" || status.wearable == "ERROR" -> InfluxDisplayState.FAILED
    status.failures > 0 -> InfluxDisplayState.PREVIOUS_FAILURES
    status.activity == "OK" && status.wearable == "OK" -> InfluxDisplayState.OK
    status.pending > 0 || status.activity == "OK" || status.wearable == "OK" -> InfluxDisplayState.PENDING
    else -> InfluxDisplayState.NO_WRITES
}
