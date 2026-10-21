// صفحة (Safha PDF) — قارئ PDF أصلي لأندرويد بلا إعلانات وبلا إذن إنترنت
// برمجة: د. محمد توفيق · kmkhol01@gmail.com
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.safha"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.safha"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
    // نسخة لكل معالج (أخف) + نسخة شاملة
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        resources { excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.pdfium)
    implementation(libs.pdfbox)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.exifinterface)
    implementation(libs.mlkit.docscanner)
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0")
}
