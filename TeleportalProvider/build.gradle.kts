// use an integer for version numbers
version = 3

dependencies{
    implementation("com.google.code.gson:gson:2.9.0")
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Telepotral - Серіали. шоу, Док. фільми"
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
        "Series",
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=teleportal.ua&sz=%size%"
}