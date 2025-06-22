package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class KinoVezhaProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(KinoVezhaProvider())
        providerTester.testAll()
    }
}