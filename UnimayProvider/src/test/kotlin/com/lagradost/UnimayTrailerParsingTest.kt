package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnimayTrailerParsingTest {
    @Test
    fun `ID трейлера Unimay перетворюється на YouTube URL`() {
        assertEquals(
            "https://www.youtube.com/watch?v=O_y51D9eirU",
            normalizeUnimayTrailer("O_y51D9eirU"),
        )
    }

    @Test
    fun `готовий URL трейлера з API не змінюється`() {
        assertEquals(
            "https://www.youtube.com/watch?v=JdtnnzecMIQ",
            normalizeUnimayTrailer("https://www.youtube.com/watch?v=JdtnnzecMIQ"),
        )
    }

    @Test
    fun `порожній або некоректний trailer пропускається`() {
        assertNull(normalizeUnimayTrailer(null))
        assertNull(normalizeUnimayTrailer(""))
        assertNull(normalizeUnimayTrailer("not-a-youtube-id"))
    }
}
