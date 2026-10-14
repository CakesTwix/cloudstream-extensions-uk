package com.lagradost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UASerialsPlayerTest {
    @Test
    fun `blank player URLs are skipped`() {
        assertEquals(
            "https://player.example/2",
            firstAvailablePlayerUrl(listOf("", "https://player.example/2")),
        )
        assertEquals(null, firstAvailablePlayerUrl(emptyList()))
    }

    @Test
    fun `cryptojsAESHandler safely handles null and empty data without throwing NPE`() {
        val provider = UASerialsProProvider()
        assertEquals("", provider.cryptojsAESHandler(null, "297796CCB81D255125", false))
        assertEquals(
            "",
            provider.cryptojsAESHandler(
                UASerialsProProvider.AesData(ct = null, s = null, iv = null),
                "297796CCB81D255125",
                false,
            ),
        )
        assertEquals(
            "",
            provider.cryptojsAESHandler(
                UASerialsProProvider.AesData(ct = "abc", s = "", iv = "def"),
                "297796CCB81D255125",
                false,
            ),
        )
        assertEquals(
            "",
            provider.cryptojsAESHandler(
                UASerialsProProvider.AesData(ct = "abc", s = "invalid_hex", iv = "def"),
                "297796CCB81D255125",
                false,
            ),
        )
    }

    @Test
    fun `cryptojsAESHandler successfully decrypts valid AES payload`() {
        val provider = UASerialsProProvider()
        val data = UASerialsProProvider.AesData(
            ct = "EULhoDxeD6prBjfUjNQ0FbkxN3HZcfrzuJ0P4LoDbGyXEC83821LHasqihhUBRRizhcchKRUaCSQWcSSAXC5w9MSfG7RyLu+RQxFtaLEH4FW7xQIY28jebrH53zsrebq1CFSRXrsT4uiz8G6x7NFaWPoB+q3NCFFSpphDoYXOhR1wK3pFhYlhisZZjYTckRb",
            iv = "ee0f91795433deb9b8239c6f2c2301d8",
            s = "4cbe71dd4dc28ee326a4c9816bb5d08676268f66db9f740401f10ab625d7b9b065e4188ad7fdbaa431119435959a167341ddd8e0910c5090faf5fd2a41a0ee107f37b1e16f4aab028c4de6a4892bc4540fccde3b332b5d8994d6fd38dbd9eb70535ab444639615585993759f58235a8d1884242599cebe4bc5f8ac299a7f98c6661363a7f919b5a9a59ecabd68b46c2b15127f76a4c71e53189b013e4cd179dd3e87147f3da975cab68e953c9e6fecf1b24fd9f40e592e0dd1520a326376b6a53baae7d81996c1429cfa092697653cc1ecc434c081e9c49e550dcc9b0756431dc974c41b5bd842bc42897c66664cedf8a4149bb4f38915c8fafa4e877a43a059",
        )
        val decrypted = provider.cryptojsAESHandler(data, "297796CCB81D255125", false)
        assertTrue(decrypted.contains("tortuga.tw"))
    }
}

