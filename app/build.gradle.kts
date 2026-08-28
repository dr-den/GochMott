import org.gradle.language.nativeplatform.internal.Dimensions.applicationVariants

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
        versionCode = 9
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
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

val renameReleaseApks = tasks.register("renameReleaseApks") {
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        if (apkDir.exists()) {
            val appName = "GochMott"
            val versionName = android.defaultConfig.versionName ?: "1.0.0"
            val versionCode = android.defaultConfig.versionCode ?: 1

            apkDir.listFiles()?.filter { it.extension == "apk" }?.forEach { apkFile ->
                // Пропускаем уже переименованные файлы, чтобы избежать циклов
                if (!apkFile.name.startsWith(appName)) {
                    val abi = when {
                        apkFile.name.contains("arm64-v8a") -> "arm64-v8a"
                        apkFile.name.contains("armeabi-v7a") -> "armeabi-v7a"
                        apkFile.name.contains("x86_64") -> "x86_64"
                        apkFile.name.contains("x86") -> "x86"
                        apkFile.name.contains("universal") -> "universal"
                        else -> "universal"
                    }
                    val newFile = File(apkDir, "$appName-v$versionName($versionCode)-$abi-release.apk")
                    apkFile.renameTo(newFile)
                }
            }
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease") {
        finalizedBy(renameReleaseApks)
    }
}