package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SerialnoProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(SerialnoProvider())
        providerTester.testAll()
    }
}