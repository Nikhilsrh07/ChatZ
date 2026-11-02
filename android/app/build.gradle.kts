import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun buildConfigString(name: String): String {
    val value = localProperties.getProperty(name, "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return "\"$value\""
}

android {
    namespace = "com.chatz.localchat"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    defaultConfig {
        applicationId = "com.chatz.localchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SYNC_API_BASE_URL", buildConfigString("chatz.syncUrl"))
        buildConfigField("String", "SYNC_API_TOKEN", buildConfigString("chatz.syncToken"))
    }

    buildFeatures {
        buildConfig = true
    }
}
