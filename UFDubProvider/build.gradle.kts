// use an integer for version numbers
version = 2


cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "UFDUB.com - Це команда любителів, що озвучують з душею те, що бажають глядачі й учасники проєкту! \uD83E\uDDE1"
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
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=ufdub.com&sz=%size%"
}