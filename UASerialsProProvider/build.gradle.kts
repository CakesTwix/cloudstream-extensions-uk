// use an integer for version numbers
version = 12

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "UASerials.pro — твої улюблені серіали українською мовою онлайн."
    authors = listOf("CakesTwix")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified

    iconUrl = "https://www.google.com/s2/favicons?domain=uaserials.pro&sz=%size%"
}
