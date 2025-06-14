package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EneyidaProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(EneyidaProvider())
        providerTester.testAll()
    }
}