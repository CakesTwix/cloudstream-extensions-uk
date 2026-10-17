package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class KinoTronTitleParsingTest {
    @Test
    fun `cleanKinoTronTitle removes дивитись онлайн suffix`() {
        assertEquals("Тварини", cleanKinoTronTitle("Тварини дивитись онлайн"))
        assertEquals("Золотий лебідь", cleanKinoTronTitle("Золотий лебідь дивитись онлайн"))
        assertEquals("Первородні / Прадавні / Першонародженні", cleanKinoTronTitle("Первородні / Прадавні / Першонародженні дивитись онлайн"))
    }

    @Test
    fun `cleanKinoTronTitle handles дивитися онлайн and українською`() {
        assertEquals("Сімпсони", cleanKinoTronTitle("Сімпсони дивитися онлайн українською"))
        assertEquals("Астрал: За межами Потойбіччя", cleanKinoTronTitle("Астрал: За межами Потойбіччя дивитись онлайн українською в HD"))
    }

    @Test
    fun `cleanKinoTronTitle strips trailing dashes and whitespace`() {
        assertEquals("Вавилон - Берлін", cleanKinoTronTitle("Вавилон - Берлін - дивитись онлайн"))
        assertEquals("Ван Гельсінґ", cleanKinoTronTitle("Ван Гельсінґ: дивитись онлайн"))
    }

    @Test
    fun `cleanKinoTronTitle keeps clean titles unchanged`() {
        assertEquals("Тварини", cleanKinoTronTitle("Тварини"))
        assertEquals("Вавилон - Берлін", cleanKinoTronTitle("Вавилон - Берлін"))
    }
}
