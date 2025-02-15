// use an integer for version numbers
version = 8

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "Мета проекту «Енеїда» - популяризація української мови, демонстрація її різнобарвності та сучасності. Ми плануємо робити це через ретрансляцію якісного кіно, мультфільмів, телесеріалів та різноманітних телешоу в якісному українському перекладі. Тож, у добрий шлях дорогі конфіденти!."
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
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=eneyida.tv&sz=%size%"
}
