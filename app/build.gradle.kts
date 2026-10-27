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

    val hasReleaseSigning = project.hasProperty("KEYSTORE_FILE")
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(project.property("KEYSTORE_FILE") as String)
                storePassword = project.property("KEYSTORE_PASSWORD") as String
                keyAlias = project.property("KEY_ALIAS") as String
                keyPassword = project.property("KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SUPABASE_URL", "\"" + (project.findProperty("SUPABASE_URL") ?: "https://YOUR_PROJECT.supabase.co") + "\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"" + (project.findProperty("SUPABASE_ANON_KEY") ?: "YOUR_SUPABASE_ANON_KEY") + "\"")
            // Left blank on purpose: the tamper check is skipped for debug builds.
            buildConfigField("String", "RELEASE_CERT_SHA256", "\"" + (project.findProperty("RELEASE_CERT_SHA256") ?: "") + "\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("String", "SUPABASE_URL", "\"" + (project.findProperty("SUPABASE_URL") ?: "https://YOUR_PROJECT.supabase.co") + "\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"" + (project.findProperty("SUPABASE_ANON_KEY") ?: "YOUR_SUPABASE_ANON_KEY") + "\"")
            // Fill RELEASE_CERT_SHA256 in gradle.properties with your release keystore's
            // SHA-256 fingerprint once you have one, so TamperGuard can reject re-signed APKs.
            buildConfigField("String", "RELEASE_CERT_SHA256", "\"" + (project.findProperty("RELEASE_CERT_SHA256") ?: "") + "\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
