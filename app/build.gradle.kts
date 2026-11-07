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

// 纯下载器：不依赖 GeckoView / WebView，不内置浏览器引擎。

android {
    namespace = "com.ikelee.geckodl"
    // androidx.core 1.15.0 requires compileSdk >= 35. targetSdk stays 34 to avoid
    // opting into Android 15 runtime behaviour changes.
    compileSdk = 35

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

        // 只发 arm64-v8a (ARMv8-A 64-bit) 单架构包。纯下载器已移除 GeckoView，
        // APK 内不含 .so；release workflow 仍会检查 lib/ 目录防止其它架构混入。
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
}
