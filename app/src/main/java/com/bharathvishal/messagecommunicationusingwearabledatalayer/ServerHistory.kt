package com.bharathvishal.messagecommunicationusingwearabledatalayer

internal const val SERVER_HISTORY_LIMIT = 3
internal val SERVER_HISTORY_FIELDS = listOf("url", "org", "activity", "wearable")

internal data class ServerTarget(val url: String, val org: String, val activity: String, val wearable: String) {
    fun fields(): List<String> = listOf(url, org, activity, wearable)
}

internal class PreviousServerToken(val id: String, val target: ServerTarget, val value: String)
internal data class PreviousTokenChoice(val id: String, val target: ServerTarget)
internal data class ServerHistorySuggestions(
    val fields: Map<String, List<String>> = emptyMap(),
    val tokens: List<PreviousTokenChoice> = emptyList()
)

internal fun previousFieldValues(history: List<String>, old: String?, current: String): List<String> =
    (listOfNotNull(old) + history).filter { it.isNotBlank() && it != current }.distinct().take(SERVER_HISTORY_LIMIT)

internal fun previousTokens(history: List<PreviousServerToken>, old: PreviousServerToken?,
                            target: ServerTarget, current: String): List<PreviousServerToken> =
    (listOfNotNull(old) + history)
        .filterNot { it.target == target && it.value == current }
        .distinctBy { it.target to it.value }.take(SERVER_HISTORY_LIMIT)

internal fun chooseServerToken(history: List<PreviousServerToken>, id: String, target: ServerTarget): String? =
    history.firstOrNull { it.id == id && it.target == target }?.value
