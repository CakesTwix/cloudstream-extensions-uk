package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class UAFlixParsingTest {
    @Test
    fun `episode number comes from episode title`() {
        assertEquals(
            2 to 7,
            parseEpisodeNumbers("2 Сезон", "7 Серія"),
        )
    }
}
