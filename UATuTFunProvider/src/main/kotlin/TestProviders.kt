import com.lagradost.UATuTFunProvider

suspend fun main() {
    val providerTester = com.lagradost.cloudstreamtest.ProviderTester(UATuTFunProvider())
    providerTester.testMainPage()
}