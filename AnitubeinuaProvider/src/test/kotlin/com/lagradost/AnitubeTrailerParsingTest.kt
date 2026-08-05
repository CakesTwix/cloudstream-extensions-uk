package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnitubeTrailerParsingTest {
    @Test
    fun `youtube посилання з rollover є трейлером`() {
        val document = Jsoup.parse(
            """
            <div class="rcol">
                <a class="rollover" href="https://www.youtube.com/watch?v=anitube123">Переглянути трейлер</a>
            </div>
            """.trimIndent()
        )

        assertEquals(
            "https://www.youtube.com/watch?v=anitube123",
            extractAnitubeTrailer(document),
        )
    }

    @Test
    fun `відсутній href не перетворюється на рядок null`() {
        val document = Jsoup.parse("<div class=\"rcol\"><a class=\"rollover\">Немає трейлера</a></div>")

        assertNull(extractAnitubeTrailer(document))
    }
}
