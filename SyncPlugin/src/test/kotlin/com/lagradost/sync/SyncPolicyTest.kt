package com.lagradost.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncPolicyTest {
    @Test
    fun `legacy timestamp in milliseconds is normalized to server seconds`() {
        assertEquals(1_785_428_192L, SyncTime.toEpochSeconds(1_785_428_192_061L))
        assertEquals(1_785_428_192L, SyncTime.toEpochSeconds(1_785_428_192L))
    }

    @Test
    fun `newer server timestamp is fetched despite legacy local milliseconds`() {
        assertTrue(
            SyncTime.shouldFetch(
                cloudTimestamp = 1_785_428_193.0,
                localTimestamp = 1_785_428_192_061L,
            )
        )
    }

    @Test
    fun `older server timestamp is not fetched`() {
        assertFalse(
            SyncTime.shouldFetch(
                cloudTimestamp = 1_785_428_191.0,
                localTimestamp = 1_785_428_192L,
            )
        )
    }

    @Test
    fun `account-prefixed bookmark key keeps id and metadata path`() {
        val key = "0/result_watch_state/-1348785472"

        assertEquals(-1_348_785_472, SyncKeyPath.itemId(key))
        assertEquals(
            listOf("0/result_watch_state_data/-1348785472"),
            SyncKeyPath.relatedTimestampKeys(key, SyncCategory.BOOKMARKS),
        )
    }

    @Test
    fun `bookmark status uses timestamp from matching metadata`() {
        val key = "0/result_watch_state/1282108252"
        val metadata = mapOf(
            "0/result_watch_state_data/1282108252" to
                """{"latestUpdatedTime":1785428192061,"name":"Test"}"""
        )

        assertEquals(
            1_785_428_192L,
            SyncKeyPath.itemTimestamp(key, SyncCategory.BOOKMARKS, metadata),
        )
    }

    @Test
    fun `current resume-watching key with account prefix exposes its id`() {
        assertEquals(
            1_879_154_550,
            SyncKeyPath.itemId("0/result_resume_watching_2/1879154550"),
        )
    }

    @Test
    fun `shared timestamp parser reads restore metadata`() {
        assertEquals(
            1_785_428_192_061L,
            SyncKeyPath.extractTimestamp("""{"updateTime":1785428192061}"""),
        )
        assertEquals(0L, SyncKeyPath.extractTimestamp(null))
    }
}
