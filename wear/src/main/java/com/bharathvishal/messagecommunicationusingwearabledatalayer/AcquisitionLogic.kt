package com.bharathvishal.messagecommunicationusingwearabledatalayer

/** Pure protocol rules shared by the runtime and its JVM tests. */
internal fun validatedCommand(payload: String, running: Boolean): Map<String, String>? {
        val pairs = payload.split(',').map { it.split(':', limit = 2).map(String::trim) }
        if (pairs.any { it.size != 2 || it[0].isEmpty() || it[1].isEmpty() }) return null
        val fields = pairs.associate { it[0] to it[1] }
        if (fields.size != pairs.size || fields.keys.any { it !in setOf("ID", "Profession", "Activity", "FatigueLevel", "Action") }) return null
        if (fields.values.any { ':' in it }) return null
        val action = fields["Action"]
        val fatigue = fields["FatigueLevel"]
        if (fatigue != null && fatigue.toIntOrNull()?.let { it in 0..5 } != true) return null
        when (action) {
            "Start" -> if (running || listOf("ID", "Profession", "Activity", "FatigueLevel").any { it !in fields }) return null
            "Pause", "Resume" -> if (!running) return null
            "Stop", "Refresh" -> Unit
            null -> if (fields.keys.any { it !in setOf("Activity", "FatigueLevel") } ||
                ("Activity" in fields && !running)) return null
            else -> return null
        }
    return fields
}

/** Only the caller's recording lock may access this buffer. */
internal class SampleBatch<T>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val items = ArrayList<T>(capacity)
    val size: Int get() = items.size
    fun add(sample: T) { items.add(sample) }
    fun isFull(): Boolean = items.size >= capacity
    fun clear() = items.clear()
    fun drain(): List<T> = items.toList().also { items.clear() }
}

internal class CommandResults(private val capacity: Int = 128) {
    private val results = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > capacity
    }
    fun result(node: String, id: String, execute: () -> String): String {
        val key = "$node:$id"
        return results[key] ?: execute().also { results[key] = it }
    }
}

/** Null means no further retry; delay is in milliseconds. */
internal fun retryDelayMs(code: Int, retryHeader: String?, attempt: Int): Long? {
    if (attempt != 0 || (code != 408 && code != 429 && code !in 500..599)) return null
    val seconds = retryHeader?.toLongOrNull()
    if (retryHeader != null && (seconds == null || seconds !in 0L..30L)) return null
    return maxOf(1000L, (seconds ?: 0L) * 1000L)
}
