// use an integer for version numbers
version = 9

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Відкриваємо світ серіалів, фентезі, містики, екшну, драми та ще безлічі інших жанрів українською мовою - все це Серіально.ТВ. Можна багато чого писати, але сенсу особливо немає. Ви тут, а значить прийшли дивитися серіали. То ж, вперед!:)"
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
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=serialno.tv&sz=%size%"
}
