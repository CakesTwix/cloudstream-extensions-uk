// use an integer for version numbers
version = 10

dependencies{
    implementation("com.google.code.gson:gson:2.9.0")
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Перший нормальний сайт, який зроблено з нуля без використання руснявих технологій. Поки що мало контенту."
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
        "AnimeMovie",
        "OVA",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=aniage.net&sz=%size%"
}