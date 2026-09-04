// Пакет java.security иначе не виден: в Kotlin DSL `java` занято расширением Gradle.
import java.security.MessageDigest

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
        versionCode = 18
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

// --------------------------------------------------------------------------
// Словарная база
// --------------------------------------------------------------------------
// dict.db весит больше сотни мегабайт, а GitHub не принимает файлы тяжелее ста,
// поэтому в git её нет — она собирается из rawSources/work/*.jsonl.
//
// Собранная база лежит в src/main/assets, а не в build/: сборка занимает около
// получаса, и `clean` не должен её стоить.
//
// Пересобирать надо только когда менялись jsonl или сам сборщик. Чтобы не
// держать это в голове, задача сравнивает отпечаток входов с сохранённым рядом
// и молча пропускается, если он совпал. Отпечаток по содержимому, а не по датам
// файлов: git при клоне ставит всем файлам время выгрузки, и по датам база
// пересобиралась бы на каждой чистой машине и в каждом прогоне CI.

val dictSources = linkedMapOf(
    "maciev1961" to "maciev.jsonl",
    "karasaev1978" to "karasaev1978.jsonl",
    "math1997_ce" to "math1997_ce.jsonl",
    "math1997_ru" to "math1997_ru.jsonl",
    "comp2017_ce" to "comp2017_ce.jsonl",
    "comp2017_ru" to "comp2017_ru.jsonl",
)

val dictWorkDir = rootProject.file("rawSources/work")
val dictBuilder = rootProject.file("tools/db_builders/build_app_db.py")
val dictReviewed = File(dictWorkDir, "reviewed.tsv")
val dictDb = file("src/main/assets/dict.db")
val dictStamp = rootProject.file(".dictdb-stamp")

val dictInputs: List<File> =
    dictSources.values.map { File(dictWorkDir, it) } + dictReviewed + dictBuilder

/** Отпечаток входов: имя, размер и содержимое каждого файла. */
fun dictFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    dictInputs.sortedBy { it.name }.forEach { source ->
        digest.update(source.name.toByteArray())
        digest.update(source.length().toString().toByteArray())
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Python зовётся по-разному: `python` в Windows, `python3` в Linux и macOS,
 * а в Windows ещё и лежит заглушка из Microsoft Store с тем же именем. Поэтому
 * мало найти команду — надо услышать от неё «Python 3».
 */
fun findPython(): String? {
    val configured = (findProperty("pythonExecutable") as String?) ?: System.getenv("PYTHON")
    return (listOfNotNull(configured) + listOf("python3", "python")).firstOrNull { candidate ->
        runCatching {
            val process = ProcessBuilder(candidate, "--version")
                .redirectErrorStream(true)
                .start()
            val greeting = process.inputStream.bufferedReader().readText().trim()
            process.waitFor() == 0 && greeting.startsWith("Python 3")
        }.getOrDefault(false)
    }
}

val buildDictDb = tasks.register("buildDictDb") {
    group = "build"
    description = "Собирает app/src/main/assets/dict.db из rawSources/work/*.jsonl"

    inputs.files(dictInputs)
    outputs.file(dictDb)

    onlyIf("данные словаря изменились") {
        !dictDb.exists() || !dictStamp.exists() ||
            dictStamp.readText().trim() != dictFingerprint()
    }

    doLast {
        val python = findPython() ?: error(
            "Не найден Python 3 — без него не собрать словарную базу.\n" +
                "Поставьте Python 3, укажите его через -PpythonExecutable=<путь>\n" +
                "или положите готовый dict.db в ${dictDb.path}."
        )

        val command = buildList {
            add(python)
            add(dictBuilder.absolutePath)
            add(dictDb.absolutePath)
            dictSources.forEach { (code, jsonl) ->
                add("--dict")
                add("$code=${File(dictWorkDir, jsonl).absolutePath}")
            }
            add("--links")
            add("--reviewed")
            add(dictReviewed.absolutePath)
        }

        logger.lifecycle("Собираю словарную базу — это примерно полчаса")
        dictDb.parentFile.mkdirs()
        val exitCode = ProcessBuilder(command)
            .directory(rootProject.projectDir)
            .inheritIO()
            .start()
            .waitFor()
        if (exitCode != 0) error("сборка dict.db не удалась, код $exitCode")

        dictStamp.writeText(dictFingerprint())
    }
}

tasks.named("preBuild") { dependsOn(buildDictDb) }

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