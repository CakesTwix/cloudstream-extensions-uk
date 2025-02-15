// use an integer for version numbers
version = 5

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "BambooUA - Ми команда, що дарує переклади азійських фільмів / шоу / кліпів / дорам українською мовою. Приєднуйтеся до нас! З нами ти завжди будеш першим!"
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
        "Anime",
        "AsianDrama",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=bambooua.com&sz=%size%"
}