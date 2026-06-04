// use an integer for version numbers
version = 1

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Сайт СІМПСОНИ UA - на якому можна дивитися Сімпсони всі сезони  українською мовою онлайн"
    authors = listOf("deleteBlack666")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified
tvTypes = listOf(
        "Мультфільми",
        "Мультсеріали",
    )
    iconUrl = "https://simpsonsua.tv/templates/simpsonsua/images/favicon.ico"
}