plugins {
    id("com.android.application")
}

android {
    namespace = "ru.hhbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.hhbot"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
