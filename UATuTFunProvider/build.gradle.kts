// use an integer for version numbers
version = 2

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "uatut.fun - практичний та ексклюзивний кінотеатр для перегляду відео у комфортній обстановці."
    authors = listOf("CakesTwix")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 3 // will be 3 if unspecified

    iconUrl = "https://www.google.com/s2/favicons?domain=uatut.fun&sz=%size%"
}
