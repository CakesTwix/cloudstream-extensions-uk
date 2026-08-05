@file:Suppress("UnstableApiUsage")

version = 3

dependencies {
    implementation(libs.gson)
    implementation("com.google.android.material:material:1.14.0")
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

cloudstream {
    description = "Синхронізація CloudStream між пристроями (закладки, продовження перегляду, налаштування, пошук, розширення). Працює з власним сервером CloudStream Sync Server."
    authors = listOf("CakesTwix")
    status = 1
    // Плагін не є медіапровайдером, тому він має бути у фільтрі «Інші».
    tvTypes = listOf("Others")
    requiresResources = true
    language = "uk"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
