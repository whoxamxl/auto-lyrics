plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun loadDotEnv(file: java.io.File): Map<String, String> {
    if (!file.exists()) return emptyMap()

    return file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val (key, rawValue) = line.split('=', limit = 2)
            val value = rawValue.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            key.trim() to value
        }
}

// Local configuration precedence:
//   process environment > .env > .env.example
// .env.example is used only when .env itself does not exist.
val localEnvFile = rootProject.file(".env").takeIf { it.exists() }
    ?: rootProject.file(".env.example")
val dotEnv = loadDotEnv(localEnvFile)

fun providerConfig(name: String): String {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: dotEnv[name].orEmpty()
}

fun buildConfigString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$escaped\""
}

// Release signing material must stay outside the repository. CI creates the
// keystore in a temporary path and provides these values through environment
// variables. Ordinary debug builds continue to use Android's standard debug
// signing configuration.
val releaseStoreFilePath = System.getenv("RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseSigningValues = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
)
val releaseSigningConfigured = releaseSigningValues.all { it != null }

check(releaseSigningValues.none { it != null } || releaseSigningConfigured) {
    "Release signing configuration is incomplete. Set RELEASE_STORE_FILE, " +
        "RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD together."
}

android {
    namespace = "com.autolyrics"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.autolyrics"
        minSdk = 26
        targetSdk = 34
        versionCode = 38
        versionName = "1.12.1"

        buildConfigField(
            "String",
            "PETITLYRICS_USER_ID",
            buildConfigString(providerConfig("PETITLYRICS_USER_ID"))
        )
        buildConfigField(
            "String",
            "PETITLYRICS_APP_NAME",
            buildConfigString(providerConfig("PETITLYRICS_APP_NAME"))
        )
        buildConfigField(
            "String",
            "PETITLYRICS_PKG_NAME",
            buildConfigString(providerConfig("PETITLYRICS_PKG_NAME"))
        )
        buildConfigField(
            "String",
            "PETITLYRICS_CLIENT_APP_ID",
            buildConfigString(providerConfig("PETITLYRICS_CLIENT_APP_ID"))
        )
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Use the standard Android debug keystore. Never use release keys here.
        }
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Android Auto - MediaBrowserService + projection connection state
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.car.app:app:1.7.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Color extraction from album art
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ML Kit — on-device language detection & translation
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests for lyric candidate matching heuristics and provider parsers
    testImplementation("junit:junit:4.13.2")
}
