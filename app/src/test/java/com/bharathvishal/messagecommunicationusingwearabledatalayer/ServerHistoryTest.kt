package com.bharathvishal.messagecommunicationusingwearabledatalayer

import org.junit.Assert.*
import org.junit.Test

class ServerHistoryTest {
    @Test fun previousValuesAreDistinctAndBounded() {
        assertEquals(listOf("b", "c", "d"), previousFieldValues(listOf("b", "c", "d", "e"), "b", "a"))
        assertEquals(listOf("b", "d"), previousFieldValues(listOf("c", "b", "d"), "b", "c"))
        assertTrue(previousFieldValues(emptyList(), "a", "a").isEmpty())
    }

    @Test fun historicalCredentialsAreBoundToTheirDestination() {
        val a = ServerTarget("https://example.org", "orgA", "activity", "wearable")
        val b = a.copy(url = "https://example.com")
        val entries = listOf(PreviousServerToken("1", a, "test"), PreviousServerToken("2", b, "test"))
        assertEquals("test", chooseServerToken(entries, "1", a))
        assertNull(chooseServerToken(entries, "1", b))
        assertNull(chooseServerToken(entries, "1", a.copy(org = "orgB")))
        assertEquals(listOf("2"), previousTokens(entries, null, a, "test").map { it.id })
        assertEquals(3, previousTokens((1..5).map { PreviousServerToken(it.toString(), a, "test-$it") },
            null, a, "dummy").size)
    }
}
