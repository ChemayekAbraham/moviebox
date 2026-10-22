import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * App version. Bump APP_VERSION for every release (e.g. "1.4"), then tag the commit "v1.4".
 * GitHub Actions overrides it from the tag, so the tag and the app version always match.
 * versionCode is derived from it (1.3 -> 10300, 1.3.1 -> 10301) so it always increases.
 */
val appVersion: String = (System.getenv("APP_VERSION") ?: "1.4").removePrefix("v")
val appVersionCode: Int = appVersion.split(".").map { it.toIntOrNull() ?: 0 }
    .let { p -> (p.getOrElse(0) { 0 } * 10000) + (p.getOrElse(1) { 0 } * 100) + p.getOrElse(2) { 0 } }

/**
 * Signing secrets are NOT stored in the repo. Locally they come from keystore.properties
 * (git-ignored); on GitHub Actions they come from repository secrets.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)

android {
    namespace = "com.moviebox.webview"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moviebox.webview"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(secret("storeFile", "KEYSTORE_FILE") ?: "moviebox-release.jks")
            storePassword = secret("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = secret("keyAlias", "KEY_ALIAS")
            keyPassword = secret("keyPassword", "KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
