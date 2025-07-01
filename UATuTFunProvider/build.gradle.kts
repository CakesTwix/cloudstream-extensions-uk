// use an integer for version numbers
version = 1

dependencies {
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-databind
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.1")
    // https://mvnrepository.com/artifact/junit/junit
    testImplementation("junit:junit:4.13.2")
// https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-test
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation(kotlin("test"))
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
    status = 2 // will be 3 if unspecified

    iconUrl = "https://www.google.com/s2/favicons?domain=uatut.fun&sz=%size%"
}
