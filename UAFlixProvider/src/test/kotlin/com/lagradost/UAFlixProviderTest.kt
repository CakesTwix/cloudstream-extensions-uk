package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UAFlixProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(UAFlixProvider())
        providerTester.testAll()
    }
}