plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.edl.flasher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yas.flashedl"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "4.0-YAS-X216B-Odin"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Keep firmware assets uncompressed in the APK: this lets AssetFileDescriptor
    // report an exact length (needed by EmbeddedFirmware.sizeOf) and lets large
    // files such as sec_efs.bin be streamed directly without loading them fully
    // into memory first.
    androidResources {
        noCompress += listOf("elf", "bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
