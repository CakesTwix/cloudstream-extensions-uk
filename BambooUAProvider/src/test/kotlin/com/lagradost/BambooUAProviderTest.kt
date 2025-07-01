package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BambooUAProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(BambooUAProvider())
        providerTester.testAll()
    }
}