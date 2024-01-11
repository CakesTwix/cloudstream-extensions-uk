// use an integer for version numbers
version = 6

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Unimay Media є однією з провідних команд, що спеціалізується на локалізації та озвученні аніме для україномовної аудиторії."
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
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=unimay.media&sz=%size%"
}
