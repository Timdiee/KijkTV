plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.kijktv"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.kijktv"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "2.2"
    }

    signingConfigs {
        create("kijktv") {
            storeFile = file("kijktv.keystore")
            storePassword = "kijktv123"
            keyAlias = "kijktv"
            keyPassword = "kijktv123"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("kijktv") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kijktv")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.annotation:annotation:1.8.2")
}
