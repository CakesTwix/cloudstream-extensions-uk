// use an integer for version numbers
version = 7

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Тут на вас чекають різноманітні жанри, в яких ви зможете переживати разом із героями все їхнє життя від моментів радості та закоханості з жанру романтики до неймовірних пригод фентазійних серіалів. "
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

    iconUrl = "https://www.google.com/s2/favicons?domain=animeua.club&sz=%size%"
}