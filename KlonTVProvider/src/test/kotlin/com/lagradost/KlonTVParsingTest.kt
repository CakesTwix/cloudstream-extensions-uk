package com.lagradost

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
}
