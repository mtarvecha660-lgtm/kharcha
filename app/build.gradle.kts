import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing settings: keystore.properties (git-ignored) first, then environment variables.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSetting(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "app.kharcha"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.kharcha"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Release signing is read from keystore.properties (git-ignored) or environment
        // variables, so no key or password ever lives in the repo. Without either,
        // release builds fall back to the debug key so `assembleRelease` still works.
        create("release") {
            val store = signingSetting("storeFile", "KHARCHA_STORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingSetting("storePassword", "KHARCHA_STORE_PASSWORD")
                keyAlias = signingSetting("keyAlias", "KHARCHA_KEY_ALIAS") ?: "kharcha"
                keyPassword = signingSetting("keyPassword", "KHARCHA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
