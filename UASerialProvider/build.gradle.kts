// use an integer for version numbers
version = 13

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Дивись онлайн серіали тільки найкращих світових студій Netflix, HBO, Disney, Amazon та інших. Серіали із українською озвучкою та онлайн, у HD якості."
    authors = listOf("CakesTwix")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=uaserial.online&sz=%size%"
}
