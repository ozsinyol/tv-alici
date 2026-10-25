// app modulunun build.gradle.kts dosyasi
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Workflow, VERSION_CODE ortam degiskenine run numarasini verir; yerelde 1 kalir.
val appVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "com.example.tvalici"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tvalici"
        minSdk = 23
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "0.$appVersionCode"

        // playfair (FairPlay yayin anahtari cozucu) icin yerel kutuphane.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Her derleme ayni anahtarla imzalanir; yoksa guncelleme "uygulama yuklenemedi" der.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("net.i2p.crypto:eddsa:0.3.0")
}
