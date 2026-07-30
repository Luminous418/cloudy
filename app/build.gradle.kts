plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.ncatt.ota"
    compileSdk = 36          // SESL8 requires compileSdk >= 34

    defaultConfig {
        applicationId = "dev.ncatt.ota"
        minSdk = 26
        targetSdk = 34
        versionCode = 80604
        versionName = "8.6.4 Beta"
    }

    buildTypes {
        release {
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
