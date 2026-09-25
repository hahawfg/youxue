plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.youxue.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.youxue.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // ML Kit 的 OCR 管线是原生库，四个 ABI 合计约 41MB。
            // 真机只有 arm 两种，x86 系列纯粹是给模拟器用的，正式包没必要带。
            // debug 不设限：Android Studio 的模拟器是 x86_64，过滤掉就跑不起来了。
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
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
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)

    // 拍照识字：捆绑式中文 OCR，模型与原生库都在 APK 内，完全离线
    implementation(libs.mlkit.text.recognition.chinese)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
