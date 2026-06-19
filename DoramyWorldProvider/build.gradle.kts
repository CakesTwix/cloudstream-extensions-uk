// use an integer for version numbers
version = 1

dependencies {
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

cloudstream {
    language = "uk"
    // All of these properties are optional, you can safely remove them

    description = "СВІТ ДОРАМ – платформа для українського шанувальника азійської культури, яка відкрилася 22 лютого 2021."
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
        "AsianDrama",
        "Movie",
    )

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSrZAw8JCRhrw1pDMb5_nYDPYQOPVC4T1JDH3PpLB5Bf35ts32ohXNFsRic&s=10"
}