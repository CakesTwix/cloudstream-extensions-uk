package com.lagradost.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleUtilsTest {

    @Test
    fun `stripSeo removes standard online viewing seo suffixes`() {
        assertEquals("Футурама", TitleUtils.stripSeo("Футурама дивитися онлайн"))
        assertEquals("Гріфіни", TitleUtils.stripSeo("Гріфіни - дивитись онлайн"))
        assertEquals("Надія", TitleUtils.stripSeo("Надія дивитись онлайн"))
        assertEquals("Ван Гельсінґ", TitleUtils.stripSeo("Ван Гельсінґ: дивитись онлайн"))
        assertEquals("Вавилон - Берлін", TitleUtils.stripSeo("Вавилон - Берлін - дивитись онлайн"))
    }

    @Test
    fun `stripSeo removes online ukrainian seo tails`() {
        assertEquals(
            "Сімпсони - 25 сезон 11 серія",
            TitleUtils.stripSeo("Сімпсони - 25 сезон 11 серія дивитися онлайн українською"),
        )
        assertEquals(
            "Чарівний лицар Рейарт",
            TitleUtils.stripSeo("Чарівний лицар Рейарт дивитись онлайн українською"),
        )
        assertEquals(
            "Астрал: За межами Потойбіччя",
            TitleUtils.stripSeo("Астрал: За межами Потойбіччя дивитись онлайн українською в HD"),
        )
        assertEquals(
            "Гангстерленд / Земля гангстерів",
            TitleUtils.stripSeo("Гангстерленд / Земля гангстерів дивитись онлайн українською"),
        )
    }

    @Test
    fun `stripSeo removes category-associated ukrainian tails`() {
        assertEquals("Мультсеріали", TitleUtils.stripSeo("Мультсеріали українською"))
        assertEquals("Серіали", TitleUtils.stripSeo("Серіали українською"))
        assertEquals("Гріфіни", TitleUtils.stripSeo("Гріфіни - українською"))
        assertEquals("Фільм", TitleUtils.stripSeo("Фільм (українською мовою)"))
        assertEquals("Фільм", TitleUtils.stripSeo("Фільм (озвучка)"))
        assertEquals("Фільм", TitleUtils.stripSeo("Фільм (дубляж)"))
    }

    @Test
    fun `stripSeo preserves ukrainian word when part of genuine title`() {
        assertEquals("Казки українською", TitleUtils.stripSeo("Казки українською"))
        assertEquals("Розмовляємо українською", TitleUtils.stripSeo("Розмовляємо українською"))
        assertEquals("Сімпсони українською: Сезон 1", TitleUtils.stripSeo("Сімпсони українською: Сезон 1"))
        assertEquals(
            "Мультсеріали українською від студії",
            TitleUtils.stripSeo("Мультсеріали українською від студії дивитися онлайн"),
        )
    }

    @Test
    fun `stripSeo keeps already clean titles intact`() {
        assertEquals("Тед-мандрівник і Магічна лампа", TitleUtils.stripSeo("Тед-мандрівник і Магічна лампа"))
        assertEquals("Тварини", TitleUtils.stripSeo("Тварини"))
        assertEquals("Золотий лебідь", TitleUtils.stripSeo("Золотий лебідь"))
        assertEquals("", TitleUtils.stripSeo(null))
        assertEquals("", TitleUtils.stripSeo("   "))
    }

    @Test
    fun `stripYear removes trailing year and extracts it`() {
        val (title1, year1) = TitleUtils.stripYear("Рік та Морті (2019)")
        assertEquals("Рік та Морті", title1)
        assertEquals(2019, year1)

        val (title2, year2) = TitleUtils.stripYear("Ахіллес і черепаха (2008) ")
        assertEquals("Ахіллес і черепаха", title2)
        assertEquals(2008, year2)

        val (title3, year3) = TitleUtils.stripYear("Фільм (частина 2)")
        assertEquals("Фільм (частина 2)", title3)
        assertNull(year3)

        val (title4, year4) = TitleUtils.stripYear("Кохання (2019) у великому місті")
        assertEquals("Кохання (2019) у великому місті", title4)
        assertNull(year4)
    }

    @Test
    fun `splitOriginalTitle separates ukrainian and latin titles`() {
        val (main1, orig1) = TitleUtils.splitOriginalTitle("Геніальний план / Masterplan")
        assertEquals("Геніальний план", main1)
        assertEquals("Masterplan", orig1)

        val (main2, orig2) = TitleUtils.splitOriginalTitle("Первородні / Прадавні / The Originals")
        assertEquals("Первородні / Прадавні", main2)
        assertEquals("The Originals", orig2)

        val (main3, orig3) = TitleUtils.splitOriginalTitle("Первородні / Прадавні")
        assertEquals("Первородні / Прадавні", main3)
        assertNull(orig3)
    }

    @Test
    fun `cleanTitleWithOriginal performs full cleanup and separation`() {
        val res = TitleUtils.cleanTitleWithOriginal(
            raw = "Геніальний план / Masterplan (2026) дивитись онлайн українською",
            stripYear = true,
        )

        assertEquals("Геніальний план", res.title)
        assertEquals("Masterplan", res.originalTitle)
        assertEquals(2026, res.year)
    }
}
