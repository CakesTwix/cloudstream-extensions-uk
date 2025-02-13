import com.lagradost.UATuTFunProvider
import com.lagradost.cloudstream3.MainPageRequest

suspend fun main() {
//    val providerTester = com.lagradost.cloudstreamtest.ProviderTester(UATuTFunProvider())
//    providerTester.testMainPage()
    UATuTFunProvider().getMainPage(1, MainPageRequest("Серіали","https://uk.uatut.fun/serie/",false))

}