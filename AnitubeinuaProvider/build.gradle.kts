// use an integer for version numbers
version = 8


cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "На нашому сайті ви можете подивитися аніме онлайн українською безкоштовно. Великий список аніме онлайн тільки у нас."
    authors = listOf("CakesTwix")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=anitube.in.ua&sz=%size%"
}