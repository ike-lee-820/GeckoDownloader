plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

// Version is injected by .github/workflows/release.yml via
// -PappVersionName / -PappVersionCode so the APK matches the GitHub Release tag.
// Local builds default to 26.11.0 (YY.MM.当月序号), versionCode 2611000.
val appVersionName: String = providers.gradleProperty("appVersionName").getOrElse("26.11.0")
val appVersionCode: Int = providers.gradleProperty("appVersionCode").map { it.toInt() }.getOrElse(2611000)

android {
    namespace = "com.ikelee.geckodl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ikelee.geckodl"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        // AGP 8 no longer generates BuildConfig.VERSION_NAME / VERSION_CODE automatically.
        // Custom names keep the in-app updater independent of PackageManager differences.
        buildConfigField("String", "APP_VERSION", "\"$appVersionName\"")
        buildConfigField("int", "APP_VERSION_CODE", "$appVersionCode")

        // Ship arm64-v8a (ARMv8-A 64-bit) only. GeckoView's AAR contains all ABIs;
        // abiFilters strips the others from the packaged APK. The release workflow
        // additionally inspects lib/<abi>/ and fails if any other ABI leaked in.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // R8 shrinking and obfuscation
            isShrinkResources = true    // drops unreferenced resources
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // GeckoView release channel, resolved from maven.mozilla.org (see settings.gradle.kts).
    implementation(libs.geckoview)
}
