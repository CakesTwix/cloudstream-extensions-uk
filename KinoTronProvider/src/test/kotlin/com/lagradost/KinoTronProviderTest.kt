package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KinoTronProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(KinoTronProvider())
        providerTester.testAll()
    }
}