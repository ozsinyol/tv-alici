// app modulunun build.gradle.kts dosyasi
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Workflow, VERSION_CODE ortam degiskenine run numarasini verir; yerelde 1 kalir.
val appVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()

// Imza anahtari yalnizca GitHub Secrets'ta durur; workflow KEYSTORE_PATH ve sifreleri ortama verir.
// Yerel derlemede (anahtar yok) Android'in varsayilan debug anahtari kullanilir; bu APK
// uygulama ici guncelleme dogrulamasindan gecmez.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.example.tvalici"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tvalici"
        minSdk = 23
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "0.$appVersionCode"

        // Guncelleme APK'sinin imza sertifikasinin SHA-256 parmak izi (herkese acik bilgi).
        buildConfigField("String", "UPDATE_CERT_SHA256", "\"4ff3b66b76f949d1487218949edda4ece828282dcd2474ff3c4a7e3f06c58c57\"")

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

    signingConfigs {
        if (keystorePath != null) {
            create("ci") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("ci")
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
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("net.i2p.crypto:eddsa:0.3.0")
}
