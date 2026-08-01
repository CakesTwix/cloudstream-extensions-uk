package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class UASerialsPlayerTest {
    @Test
    fun `blank player URLs are skipped`() {
        assertEquals(
            "https://player.example/2",
            firstAvailablePlayerUrl(listOf("", "https://player.example/2")),
        )
        assertEquals(null, firstAvailablePlayerUrl(emptyList()))
    }
}
