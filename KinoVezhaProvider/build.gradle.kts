// use an integer for version numbers
version = 1


cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Наша мета — популяризація нашої рідної мови, шляхом збору в одному місці величезної колекції найкращих і найпопулярніших фільмів, серіалів, мультфільмів та мультсеріалів українською мовою, які ви можете дивитися онлайн в HD якості безкоштовно. Приємного перегляду!"
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
        "Cartoon",
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=kinovezha.com&sz=%size%"
}