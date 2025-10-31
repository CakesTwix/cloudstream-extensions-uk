// use an integer for version numbers
version = 12

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Команда Klon.TV створила безкоштовний онлайн-сервіс, щоб кожен наш глядач з легкістю та без зайвих суперечок вибрав цікавий фільм, або одразу продовжив дивитися улюблений серіал. Минула та пора, коли треба стежити за афішами твого міста, і вгадувати якість майбутньої стрічки. Також вже можна забути про трату часу на довгу дорогу в кінотеатр, півгодинного перегляду трейлерів перед показом, сусідами, що чавкають, яким не зрозуміти, що фільм треба іноді й слухати, а не тільки дивитися. Вибравши наш сервіс для перегляду фільмів онлайн безкоштовно, ви будете приємно здивовані: українське озвучення, перегляд без реєстрації, ніякої реклами на весь екран, і багато інших цікавих можливостей. Klon.TV - цінує кожного глядача, і зробить все можливе, щоб Ви до нас повернулися:)."
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
        "Cartoon",
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=klon.tv&sz=%size%"
}