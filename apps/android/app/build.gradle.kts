import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.noahprail.kairos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noahprail.kairos"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.0"
    }

    // Release signing (the Play upload key). The values come from the environment (CI) or from
    // apps/android/keystore.properties (a local file, never committed); with neither, the release build is
    // left unsigned, so `assembleDebug`, the tests and forks that do not publish keep working.
    // See docs/releasing-android.md.
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    fun signingValue(env: String, key: String): String? =
        System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

    val keystorePath = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = rootProject.file(keystorePath).takeIf { it.exists() } ?: file(keystorePath)
                storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
