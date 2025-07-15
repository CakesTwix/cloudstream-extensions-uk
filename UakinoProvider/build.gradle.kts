// use an integer for version numbers
version = 13

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Дивіться фільми та серіали онлайн в HD якості. У нас можна дивитися кіно онлайн безкоштовно, у високій якості та з якісним українським дубляжем."
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
        "TvSeries",
        "Movie",
        "AsianDrama"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=uakino.club&sz=%size%"
}