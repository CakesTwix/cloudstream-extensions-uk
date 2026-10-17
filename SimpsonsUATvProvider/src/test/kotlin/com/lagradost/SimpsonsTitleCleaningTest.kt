package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class SimpsonsTitleCleaningTest {
    @Test
    fun `cleanSimpsonsTitle removes дивитися онлайн and trailing українською`() {
        assertEquals(
            "Сімпсони - 25 сезон 11 серія",
            cleanSimpsonsTitle("Сімпсони - 25 сезон 11 серія дивитися онлайн українською"),
        )
        assertEquals(
            "Футурама",
            cleanSimpsonsTitle("Футурама дивитися онлайн"),
        )
        assertEquals(
            "Гріфіни",
            cleanSimpsonsTitle("Гріфіни - дивитись онлайн"),
        )
        assertEquals(
            "Мультсеріали",
            cleanSimpsonsTitle("Мультсеріали українською"),
        )
    }

    @Test
    fun `cleanSimpsonsTitle preserves українською when inside title`() {
        assertEquals(
            "Сімпсони українською: Сезон 1",
            cleanSimpsonsTitle("Сімпсони українською: Сезон 1"),
        )
        assertEquals(
            "Мультсеріали українською від студії",
            cleanSimpsonsTitle("Мультсеріали українською від студії дивитися онлайн"),
        )
    }
}
