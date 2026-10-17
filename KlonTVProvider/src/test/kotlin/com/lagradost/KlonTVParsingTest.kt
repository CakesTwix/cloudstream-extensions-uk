package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KlonTVParsingTest {
    @Test
    fun `invalid JSON-LD is rejected before deserialization`() {
        assertFalse(hasJsonObjectShape("not-json"))
        assertFalse(hasJsonObjectShape(null))
        assertTrue(hasJsonObjectShape("{}"))
    }

    @Test
    fun `trailing year is stripped from the title`() {
        assertEquals("Рік та Морті", stripTitleYear("Рік та Морті (2019)"))
        assertEquals("Ахіллес і черепаха", stripTitleYear("Ахіллес і черепаха (2008)"))
    }

    @Test
    fun `stripping the year leaves no trailing whitespace`() {
        assertEquals("Рік та Морті", stripTitleYear("Рік та Морті (2019) "))
        assertEquals("Рік та Морті", stripTitleYear("Рік та Морті  (2019)"))
        assertEquals("Рік та Морті", stripTitleYear("  Рік та Морті (2019)  "))
    }

    @Test
    fun `only a four-digit year is stripped from the end`() {
        assertEquals("Фільм (спеціальна версія)", stripTitleYear("Фільм (спеціальна версія)"))
        assertEquals("Фільм (частина 2)", stripTitleYear("Фільм (частина 2)"))
    }

    @Test
    fun `year that is not at the end is preserved`() {
        assertEquals("Кохання (2019) у великому місті", stripTitleYear("Кохання (2019) у великому місті"))
    }
}
