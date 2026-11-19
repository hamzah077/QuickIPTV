plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quickiptv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quickiptv.app"
        minSdk = 21            // أندرويد 5 — يشتغل على أغلب أجهزة TV Box القديمة
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // نخلي العربي والإنجليزي بس (يصغّر حجم التطبيق)
        resourceConfigurations += listOf("ar", "en")
        vectorDrawables.useSupportLibrary = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // توقيع بمفتاح التطوير عشان الـ APK يتثبت مباشرة (للنشر في المتجر استخدم مفتاحك الخاص)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    // APK لكل معالج (أصغر حجم) + APK عام يشتغل على كل الأجهزة
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil:2.7.0")
}
