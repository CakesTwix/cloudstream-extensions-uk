package com.lagradost

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class UFDubTitleParsingTest {
    @Test
    fun `cleanUFDubTitle strips short-t-or original title div`() {
        val doc = Jsoup.parse("""<h1 class="top-title"> Три обличчя Єви  <br> <div class="short-t-or"> Three Faces of Eve </div> </h1>""")
        val el = doc.selectFirst("h1.top-title")
        assertEquals("Три обличчя Єви", cleanUFDubTitle(el))
    }

    @Test
    fun `cleanUFDubTitle works with anime series titles`() {
        val doc = Jsoup.parse("""<h1 class="top-title">Фермерка Аракава Сезон 3 <br><div class="short-t-or">Hyakushou Kizoku S3</div></h1>""")
        val el = doc.selectFirst("h1.top-title")
        assertEquals("Фермерка Аракава Сезон 3", cleanUFDubTitle(el))
    }

    @Test
    fun `cleanUFDubTitle preserves plain titles without short-t-or`() {
        val doc = Jsoup.parse("""<h1 class="top-title">Просто назва фільму</h1>""")
        val el = doc.selectFirst("h1.top-title")
        assertEquals("Просто назва фільму", cleanUFDubTitle(el))
    }

    @Test
    fun `cleanUFDubTitle returns empty string on null element`() {
        assertEquals("", cleanUFDubTitle(null))
    }
}
