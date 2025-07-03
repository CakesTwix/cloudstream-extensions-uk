package com.lagradost

import com.lagradost.cloudstreamtest.ProviderTester
import kotlinx.coroutines.runBlocking
import org.junit.Test


class TestUATuTProvider {
    @Test
    fun testProvider() = runBlocking {
        val providerTester = ProviderTester(UATuTFunProvider())
        providerTester.testAll()
    }
}