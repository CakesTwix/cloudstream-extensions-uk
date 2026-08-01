package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Test

class KlonTVParsingTest {
    @Test
    fun `invalid JSON-LD does not crash provider parsing`() {
        assertEquals(null, parseGeneralInfo("not-json"))
        assertEquals(null, parseGeneralInfo(null))
    }
}
