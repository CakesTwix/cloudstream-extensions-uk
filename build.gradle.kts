import com.lagradost.cloudstream3.gradle.CloudstreamExtension 
import com.android.build.gradle.BaseExtension

buildscript {
    dependencies {
        classpath(libs.recloudstream.gradle)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin) apply false
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        // you can modify it to use other git hosting services, like gitlab
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/CakesTwix/cloudstream-extensions-uk")
        authors = listOf("CakesTwix")
    }

    android {
        namespace = "ua.CakesTwix"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35

        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions {
                jvmTarget = "1.8" // Required
                // Disables some unnecessary features
                freeCompilerArgs = freeCompilerArgs +
                        "-Xno-call-assertions" +
                        "-Xno-param-assertions" +
                        "-Xno-receiver-assertions"
            }
        }
    }

    dependencies {
        val apk by configurations
        val implementation by configurations
        val libs = rootProject.libs
        val apkTasks = listOf("deployWithAdb", "build")
        val useApk = gradle.startParameter.taskNames.any { taskName ->
            apkTasks.any { apkTask ->
                taskName.contains(apkTask, ignoreCase = true)
            }
        }

        // If the task is specifically to compile the app then use the stubs, otherwise us the library.
        if (useApk) {
            // Stubs for all Cloudstream classes
            apk(libs.cloudstream3)
        } else {
            // For running locally
            implementation(libs.cloudstreamapi)
        }

        // these dependencies can include any of those which are added by the app,
        // but you dont need to include any of them if you dont need them
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle
        implementation(kotlin("stdlib")) // adds standard kotlin features, like listOf, mapOf etc
        implementation(libs.nicehttp) // http library
        implementation(libs.jsoup) // html parser
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
