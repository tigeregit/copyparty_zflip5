plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.copyparty.zflip5"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.copyparty.zflip5"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"

        ndk {
            // Z Flip5 / modern phones: arm64 only (smaller APK)
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compress .so inside the APK (smaller download/sideload; slightly slower first extract)
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython("/usr/bin/python3.13")
        pip {
            // Full upstream copyparty from PyPI (pinned). Size OK (Drive delivery).
            install("Jinja2==3.1.4")
            install("copyparty==1.20.23")
        }
        // Extract full package (includes .py for easier debugging + data files on disk)
        extractPackages("copyparty")
        pyc {
            src = true
            pip = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.zxing:core:3.5.3")
}
