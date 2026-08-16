plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties

// Release signing: reads keystore.properties (gitignored) at the repo root. When absent,
// the release build falls back to the debug keystore so CI/any machine can still assemble.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.cloudy.ota"
    compileSdk = 36          // SESL8 requires compileSdk >= 34

    signingConfigs {
        create("release") {
            if (keystoreProperties.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.cloudy.ota"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            signingConfig = if (keystoreProperties.containsKey("storeFile"))
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        aidl = true            // persistent root worker (IRootIpc / IFlashCallback)
    }
}

dependencies {
    // OneUI 8 / SESL8 UI stack (replaces upstream appcompat/material/core/fragment).
    implementation(libs.bundles.sesl)

    // Root execution
    implementation(libs.bundles.libsu)

    // Networking + JSON
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Standard AndroidX / coroutines (compatible alongside SESL)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)   // lifecycleScope
    implementation(libs.kotlinx.coroutines.android)
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.core:core"))
            .using(module("sesl.androidx.core:core:1.17.0+1.0.7-sesl8+rev1"))
        substitute(module("androidx.core:core-ktx"))
            .using(module("sesl.androidx.core:core-ktx:1.17.0+1.0.0-sesl8+rev0"))
    }
}
