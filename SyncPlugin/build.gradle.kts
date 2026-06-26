@file:Suppress("UnstableApiUsage")

version = 1

dependencies {
    implementation(libs.gson)
    implementation("com.google.android.material:material:1.14.0")
}

cloudstream {
    description = "Синхронізація CloudStream між пристроями (закладки, продовження перегляду, налаштування, пошук, розширення). Працює з власним сервером CloudStream Sync Server."
    authors = listOf("CakesTwix")
    status = 1
    tvTypes = listOf("All")
    requiresResources = true
    language = "uk"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
