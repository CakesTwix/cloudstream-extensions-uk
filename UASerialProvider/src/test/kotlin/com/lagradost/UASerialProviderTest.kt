package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UASerialProviderTest {

    @Test
    fun testProvider() = runTest {
        val providerTester = ProviderTester(UASerialProvider("https://uaserial.com", "UASerial"))
        providerTester.testAll()
    }
}