package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TeleportalProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(TeleportalProvider())
        providerTester.testAll()
    }
}