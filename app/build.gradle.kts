plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.bilto.gochmott"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.bilto.gochmott"
        minSdk = 26
        targetSdk = 37
        versionCode = 16
        versionName = "1.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val envKeystorePath = System.getenv("KEYSTORE_PATH")
            if (envKeystorePath != null && file(envKeystorePath).exists()) {
                storeFile = file(envKeystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                val releaseRequested = gradle.startParameter.taskNames.any {
                    it.contains("Release", ignoreCase = true)
                }
                check(!(System.getenv("CI") == "true" && releaseRequested)) {
                    "Релизный ключ не найден. Задай KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD."
                }
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lucene)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)

    // Lifecycle / ViewModel
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Coroutines
    implementation(libs.coroutines.android)

    // Room
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Понятные имена: GochMott-v1.0.5-15-release.apk / .aab
val artifactBaseName = "GochMott-v${android.defaultConfig.versionName}_${android.defaultConfig.versionCode}"

fun Task.renameOutput(dir: Provider<Directory>, from: String, to: String) = doLast {
    val source = File(dir.get().asFile, from)
    if (source.exists()) {
        val target = File(source.parentFile, to)
        target.delete()
        source.renameTo(target)
    }
}

val renameReleaseApk = tasks.register("renameReleaseApk") {
    description = ""
    renameOutput(
        layout.buildDirectory.dir("outputs/apk/release"),
        "app-release.apk",
        "$artifactBaseName-release.apk"
    )
}

val renameReleaseBundle = tasks.register("renameReleaseBundle") {
    description = ""
    renameOutput(
        layout.buildDirectory.dir("outputs/bundle/release"),
        "app-release.aab",
        "$artifactBaseName-release.aab"
    )
}

tasks.configureEach {
    when (name) {
        "assembleRelease" -> finalizedBy(renameReleaseApk)
        "bundleRelease" -> finalizedBy(renameReleaseBundle)
    }
}